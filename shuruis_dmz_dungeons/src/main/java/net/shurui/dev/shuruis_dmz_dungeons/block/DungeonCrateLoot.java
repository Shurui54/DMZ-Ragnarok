package net.shurui.dev.shuruis_dmz_dungeons.block;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

// per-floor crate loot config, FOUR rarity tiers (common / uncommon / rare / mythic). A promoted container gets a tier
// rolled from these weights; opening it yields ONE item from that tier's pool (rollReward) plus an optional zeni payout.
// Chests and barrels share these pools and the crate itself; only the promoted share differs (chestChancePercent /
// barrelChancePercent).
//
// Plain-data object (mirrors SpawnerConfig / DungeonFloorConfig) so the /rg dungeon edit GUI can drive it. Carries NO
// shuruisutilities types: zeni is a plain int range, handed to SU's economy only at pay time (DungeonCrates).
public class DungeonCrateLoot {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int TIER_COUNT = 4;

    // one rarity tier's editable pool: item drops (each drop's `chance` is a PICK WEIGHT, one reward selected across
    // the pool), a zeni range, and the tier's assignment weight (how likely a container is this tier). weight defaults
    // to CrateTier.DEFAULT_WEIGHTS, the common-heavy distribution.
    public static final class TierPool {
        public final List<CustomDrop> drops = new ArrayList<>();
        public int zeniMin = 0;
        public int zeniMax = 0;
        public int weight;

        public TierPool(int weight) {
            this.weight = weight;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            ListTag dropList = new ListTag();
            for (CustomDrop d : drops) {
                dropList.add(d.save());
            }
            tag.put("Drops", dropList);
            tag.putInt("ZeniMin", zeniMin);
            tag.putInt("ZeniMax", zeniMax);
            tag.putInt("Weight", weight);
            return tag;
        }

        static TierPool load(CompoundTag tag, int defaultWeight) {
            TierPool p = new TierPool(tag.contains("Weight") ? tag.getInt("Weight") : defaultWeight);
            if (tag.contains("Drops")) {
                ListTag dropList = tag.getList("Drops", Tag.TAG_COMPOUND);
                for (int i = 0; i < dropList.size(); i++) {
                    p.drops.add(CustomDrop.load(dropList.getCompound(i)));
                }
            }
            p.zeniMin = tag.getInt("ZeniMin");
            p.zeniMax = tag.getInt("ZeniMax");
            return p;
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeInt(drops.size());
            for (CustomDrop d : drops) {
                d.encode(buf);
            }
            buf.writeInt(zeniMin);
            buf.writeInt(zeniMax);
            buf.writeInt(weight);
        }

        static TierPool decode(FriendlyByteBuf buf, int defaultWeight) {
            TierPool p = new TierPool(defaultWeight);
            int n = buf.readInt();
            for (int i = 0; i < n; i++) {
                p.drops.add(CustomDrop.decode(buf));
            }
            p.zeniMin = buf.readInt();
            p.zeniMax = buf.readInt();
            p.weight = buf.readInt();
            return p;
        }
    }

    // one pool per tier, indexed by CrateTier.ordinal(). never null, never resized.
    public final TierPool[] tiers = new TierPool[TIER_COUNT];

    // metal axis (bronze / iron / gold), indexed by CrateMetal.ordinal(). Only the ASSIGNMENT WEIGHT is read here (how
    // likely a crate is that metal); drops and zeni live in the per-metal override below, same shape for one editor row.
    public final TierPool[] metals = new TierPool[CrateMetal.METAL_COUNT];

    // optional loot per (rarity, metal), indexed [rarity][metal]. EMPTY by default; an empty override falls back to the
    // rarity's own pool, so a floor configured before metals existed behaves identically until somebody fills one in.
    public final TierPool[][] tierMetals = new TierPool[TIER_COUNT][CrateMetal.METAL_COUNT];

    // per-player re-roll cooldown in real hours. 0 (default) => roll PERSISTS FOREVER: loot once, tier frozen too,
    // matching run-once floors. > 0 => container tier AND player contents re-roll after this many hours, for a farmable
    // floor. Drives CrateTier.epoch.
    public int refreshHours = 0;

    // share of barrels (0..100) promoted to crates. Default 5. Which barrels is deterministic in position
    // (CrateTier.barrelSelected), so the same ones are crates for every player and on both server and client.
    public int barrelChancePercent = 5;

    // same share for CHESTS (0..100). Default 100 (every placed chest became a crate, as before). Turn it down to leave
    // some as ordinary chests. Deterministic in position (CrateTier.chestSelected), on its own stream.
    public int chestChancePercent = 100;

    public DungeonCrateLoot() {
        for (int i = 0; i < TIER_COUNT; i++) {
            tiers[i] = new TierPool(CrateTier.DEFAULT_WEIGHTS[i]);
            for (int m = 0; m < CrateMetal.METAL_COUNT; m++) {
                tierMetals[i][m] = new TierPool(0);
            }
        }
        for (int m = 0; m < CrateMetal.METAL_COUNT; m++) {
            metals[m] = new TierPool(CrateMetal.DEFAULT_WEIGHTS[m]);
        }
    }

    public TierPool pool(CrateTier tier) {
        return tiers[tier.ordinal()];
    }

    public TierPool pool(CrateMetal metal) {
        return metals[metal.ordinal()];
    }

    /** The per (rarity, metal) override pool. Always present, usually empty, never a fallback decision itself. */
    public TierPool pool(CrateTier tier, CrateMetal metal) {
        return tierMetals[tier.ordinal()][metal.ordinal()];
    }

    // the three metal assignment weights, in ordinal order, for CrateMetal.roll.
    public int[] metalWeights() {
        int[] w = new int[CrateMetal.METAL_COUNT];
        for (int i = 0; i < CrateMetal.METAL_COUNT; i++) {
            w[i] = metals[i].weight;
        }
        return w;
    }

    // the four tier assignment weights, in ordinal order, for CrateTier.roll.
    public int[] weights() {
        int[] w = new int[TIER_COUNT];
        for (int i = 0; i < TIER_COUNT; i++) {
            w[i] = tiers[i].weight;
        }
        return w;
    }

    // roll ONE reward for a tier: pick a drop weighted by each drop's `chance`, build its stack. Falls back to the
    // theme+tier defaults when the pool is empty, so a crate is never empty unless even the defaults resolve to nothing.
    public ItemStack rollReward(RandomSource random, CrateTier tier, String theme) {
        return rollReward(random, tier, null, theme);
    }

    /**
     * As above, preferring the pool configured for this exact (rarity, metal) crate. Three steps, most specific first:
     * (rarity, metal) override, rarity's own pool, theme defaults. A null metal or unconfigured override skips step one,
     * so a floor written before metals existed rolls what it always did.
     */
    public ItemStack rollReward(RandomSource random, CrateTier tier, CrateMetal metal, String theme) {
        List<CustomDrop> source = source(tier, metal, theme);
        if (source.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // A single unresolvable entry (an item id from a mod that is not installed, a typo, or a zero count) must not
        // swallow the whole reward. A player who rolls loot gets loot whenever the pool holds ANY buildable entry:
        // pick weighted, build, and on an empty result LOG the bad entry, drop it, and pick again from the rest.
        // Only a pool with no buildable entry at all returns EMPTY, and every dead id it held has been logged by then.
        List<CustomDrop> remaining = new ArrayList<>(source);
        while (!remaining.isEmpty()) {
            CustomDrop chosen = pickWeighted(remaining, random);
            ItemStack stack = chosen.build(random);
            if (!stack.isEmpty()) {
                return stack;
            }
            LOGGER.warn("Dungeon crate loot: could not resolve reward entry (item '{}', count {}-{}, tier {}, theme {}); "
                    + "skipping it. Fix the item id in the floor's crate loot so players get this reward.",
                    chosen.itemId, chosen.minCount, chosen.maxCount, tier, theme);
            remaining.remove(chosen);
        }
        return ItemStack.EMPTY;
    }

    /** Weighted pick over a drop list by each drop's {@code chance}. Uniform when every weight is zero. */
    private static CustomDrop pickWeighted(List<CustomDrop> source, RandomSource random) {
        double total = 0.0;
        for (CustomDrop d : source) {
            total += Math.max(0.0f, d.chance);
        }
        if (total <= 0.0) {
            return source.get(random.nextInt(source.size()));
        }
        double r = random.nextDouble() * total;
        double acc = 0.0;
        for (CustomDrop d : source) {
            acc += Math.max(0.0f, d.chance);
            if (r < acc) {
                return d;
            }
        }
        return source.get(source.size() - 1);
    }

    /**
     * Which drop list a crate of this rarity and metal rolls out of (override, then rarity pool, then theme defaults).
     * Shared with {@link #rollReward} so the reward and the items the reveal BLURS THROUGH come from the same pool;
     * otherwise the cycle would show items the crate could never give.
     */
    private List<CustomDrop> source(CrateTier tier, CrateMetal metal, String theme) {
        List<CustomDrop> source = metal == null ? List.of() : pool(tier, metal).drops;
        if (source.isEmpty()) {
            source = pool(tier).drops;
        }
        if (source.isEmpty()) {
            source = DungeonCrateDefaults.dropsFor(theme, tier);
        }
        return source;
    }

    /**
     * A handful of items from this crate's pool for the reveal to cycle through before it lands. Presentation only,
     * never given out, but built with the real {@link CustomDrop#build} so counts and NBT look right. Drawn from a
     * shuffled copy so a long pool does not always advertise its first entries. EMPTY when the pool is empty.
     */
    public List<ItemStack> sampleCandidates(RandomSource random, CrateTier tier, CrateMetal metal, String theme,
                                            int max) {
        List<CustomDrop> source = source(tier, metal, theme);
        if (source.isEmpty() || max <= 0) {
            return List.of();
        }
        List<CustomDrop> shuffled = new ArrayList<>(source);
        for (int i = shuffled.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            CustomDrop swap = shuffled.get(i);
            shuffled.set(i, shuffled.get(j));
            shuffled.set(j, swap);
        }
        List<ItemStack> out = new ArrayList<>();
        for (CustomDrop d : shuffled) {
            if (out.size() >= max) {
                break;
            }
            ItemStack stack = d.build(random);
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }

    // roll the zeni amount for one player at a tier, using the configured range or the theme+tier default when unset.
    public long rollZeni(RandomSource random, CrateTier tier, String theme) {
        return rollZeni(random, tier, null, theme);
    }

    /** As above, preferring the (rarity, metal) override's range when one is set. */
    public long rollZeni(RandomSource random, CrateTier tier, CrateMetal metal, String theme) {
        TierPool p = metal == null ? pool(tier) : pool(tier, metal);
        if (p.zeniMax <= 0) {
            p = pool(tier);
        }
        int lo = p.zeniMin;
        int hi = p.zeniMax;
        if (hi <= 0) {
            int[] def = DungeonCrateDefaults.zeniFor(theme, tier);
            lo = def[0];
            hi = def[1];
        }
        lo = Math.max(0, Math.min(lo, hi));
        hi = Math.max(0, Math.max(lo, hi));
        if (hi <= 0) {
            return 0L;
        }
        return lo >= hi ? lo : lo + random.nextInt(hi - lo + 1);
    }

    public void encode(FriendlyByteBuf buf) {
        for (int i = 0; i < TIER_COUNT; i++) {
            tiers[i].encode(buf);
        }
        buf.writeInt(refreshHours);
        buf.writeInt(barrelChancePercent);
        // appended, so the client and server must ship together (they always do: one jar).
        for (int m = 0; m < CrateMetal.METAL_COUNT; m++) {
            metals[m].encode(buf);
        }
        for (int i = 0; i < TIER_COUNT; i++) {
            for (int m = 0; m < CrateMetal.METAL_COUNT; m++) {
                tierMetals[i][m].encode(buf);
            }
        }
        buf.writeInt(chestChancePercent);
    }

    public static DungeonCrateLoot decode(FriendlyByteBuf buf) {
        DungeonCrateLoot l = new DungeonCrateLoot();
        for (int i = 0; i < TIER_COUNT; i++) {
            l.tiers[i] = TierPool.decode(buf, CrateTier.DEFAULT_WEIGHTS[i]);
        }
        l.refreshHours = buf.readInt();
        l.barrelChancePercent = buf.readInt();
        for (int m = 0; m < CrateMetal.METAL_COUNT; m++) {
            l.metals[m] = TierPool.decode(buf, CrateMetal.DEFAULT_WEIGHTS[m]);
        }
        for (int i = 0; i < TIER_COUNT; i++) {
            for (int m = 0; m < CrateMetal.METAL_COUNT; m++) {
                l.tierMetals[i][m] = TierPool.decode(buf, 0);
            }
        }
        l.chestChancePercent = buf.readInt();
        return l;
    }

    public CompoundTag save(CompoundTag tag) {
        ListTag tierList = new ListTag();
        for (int i = 0; i < TIER_COUNT; i++) {
            tierList.add(tiers[i].save());
        }
        tag.put("Tiers", tierList);
        ListTag metalList = new ListTag();
        for (int m = 0; m < CrateMetal.METAL_COUNT; m++) {
            metalList.add(metals[m].save());
        }
        tag.put("Metals", metalList);
        ListTag overrideList = new ListTag();
        for (int i = 0; i < TIER_COUNT; i++) {
            for (int m = 0; m < CrateMetal.METAL_COUNT; m++) {
                overrideList.add(tierMetals[i][m].save());
            }
        }
        tag.put("TierMetals", overrideList);
        tag.putInt("RefreshHours", refreshHours);
        tag.putInt("BarrelChance", barrelChancePercent);
        tag.putInt("ChestChance", chestChancePercent);
        return tag;
    }

    public static DungeonCrateLoot load(CompoundTag tag) {
        DungeonCrateLoot l = new DungeonCrateLoot();
        if (tag.contains("Tiers")) {
            ListTag tierList = tag.getList("Tiers", Tag.TAG_COMPOUND);
            for (int i = 0; i < TIER_COUNT && i < tierList.size(); i++) {
                l.tiers[i] = TierPool.load(tierList.getCompound(i), CrateTier.DEFAULT_WEIGHTS[i]);
            }
        } else if (tag.contains("Drops")) {
            // migrate the pre-tier format: the flat drop list + zeni range become the COMMON pool; the other three
            // tiers keep default weights and fall back to theme+tier defaults until edited.
            TierPool common = l.tiers[CrateTier.COMMON.ordinal()];
            ListTag dropList = tag.getList("Drops", Tag.TAG_COMPOUND);
            for (int i = 0; i < dropList.size(); i++) {
                common.drops.add(CustomDrop.load(dropList.getCompound(i)));
            }
            common.zeniMin = tag.getInt("ZeniMin");
            common.zeniMax = tag.getInt("ZeniMax");
        }
        // absent before metals existed: the constructor defaults stand (bronze-heavy, no overrides).
        if (tag.contains("Metals")) {
            ListTag metalList = tag.getList("Metals", Tag.TAG_COMPOUND);
            for (int m = 0; m < CrateMetal.METAL_COUNT && m < metalList.size(); m++) {
                l.metals[m] = TierPool.load(metalList.getCompound(m), CrateMetal.DEFAULT_WEIGHTS[m]);
            }
        }
        if (tag.contains("TierMetals")) {
            ListTag overrideList = tag.getList("TierMetals", Tag.TAG_COMPOUND);
            for (int i = 0; i < TIER_COUNT; i++) {
                for (int m = 0; m < CrateMetal.METAL_COUNT; m++) {
                    int idx = i * CrateMetal.METAL_COUNT + m;
                    if (idx < overrideList.size()) {
                        l.tierMetals[i][m] = TierPool.load(overrideList.getCompound(idx), 0);
                    }
                }
            }
        }
        l.refreshHours = tag.getInt("RefreshHours");
        l.barrelChancePercent = tag.contains("BarrelChance") ? tag.getInt("BarrelChance") : 5;
        // absent before chests were selectable: 100, as before.
        l.chestChancePercent = tag.contains("ChestChance") ? tag.getInt("ChestChance") : 100;
        return l;
    }
}
