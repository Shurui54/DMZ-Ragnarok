package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// the Lootr-style per-player crate side table. Chosen (over a per-crate block entity or custom block): every player
// who opens a crate gets their OWN rolled inventory, so one player looting never empties it for anyone else, and the
// record lives in ONE global SavedData rather than hundreds of chunk-bound block entities.
//
// keyed by crate position (packed long) then player UUID. A crate position is globally unique: floor N sits at
// x = N * DungeonFloorLayout.CELL_SPACING, so two floors never share an X column and a BlockPos alone identifies one
// crate. Stored on the LEGACY dungeon level like DungeonFloors, so it travels with the save and stays loaded while
// the server runs, independent of whether the themed floor chunks are (a per-BE map would unload with its chunk).
//
// pure storage. The roll (drops + zeni), the key gate and the menu all live in DungeonCrates.
public class DungeonCrateData extends SavedData {

    public static final String NAME = "shuruis_dmz_dungeons_dungeon_crates";

    // one player's rolled reward for one crate: the SINGLE item rolled (crate-style, one item, no 27-slot inventory),
    // the rarity tier, the refresh window (epoch) it belongs to, and whether it has been taken. When the container's
    // window advances (see CrateTier.epoch), the next open re-rolls both tier and reward, so a common crate can become mythic later.
    public static final class PlayerCrate {
        public ItemStack reward = ItemStack.EMPTY;
        public int tier;
        public long rolledEpoch;
        public boolean claimed;

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            CompoundTag item = new CompoundTag();
            // Write the CANONICAL empty stack as a FIXED tag, never by serialising an ItemStack. An air stack is empty
            // whatever its count, but {id:air,Count:5} and {id:air,Count:0} hash differently, so two servers holding
            // the same claimed-but-unrewarded crate serialised one byte apart, each read the other as a change, and
            // republished for ever. Seen live between OW1 and OW2 on this field. Writing the constant removes the judgement.
            if (reward.isEmpty())
            {
                CompoundTag empty = new CompoundTag();
                empty.putString("id", "minecraft:air");
                empty.putByte("Count", (byte) 0);
                tag.put("reward", empty);
            }
            else
            {
                reward.save(item);
                tag.put("reward", item);
            }
            tag.putInt("tier", tier);
            tag.putLong("epoch", rolledEpoch);
            tag.putBoolean("claimed", claimed);
            return tag;
        }

        static PlayerCrate load(CompoundTag tag) {
            PlayerCrate pc = new PlayerCrate();
            ItemStack decoded = tag.contains("reward") ? ItemStack.of(tag.getCompound("reward")) : ItemStack.EMPTY;
            // Normalise on the way in too, so a stored air-with-a-count never survives a load and is written back non-canonical.
            pc.reward = decoded.isEmpty() ? ItemStack.EMPTY : decoded;
            pc.tier = tag.getInt("tier");
            pc.rolledEpoch = tag.getLong("epoch");
            pc.claimed = tag.getBoolean("claimed");
            return pc;
        }
    }

    private final Map<Long, Map<UUID, PlayerCrate>> crates = new HashMap<>();

    public DungeonCrateData() {
    }

    // resolve the table off the legacy dungeon level (where DungeonFloors lives), or null if unavailable. Never
    // touches the themed floor levels, so it is safe to call from any dimension's handler.
    public static DungeonCrateData get(MinecraftServer server) {
        ServerLevel dungeon = DungeonDimensions.level(server);
        return dungeon == null ? null : dungeon.getDataStorage().computeIfAbsent(DungeonCrateData::load,
                DungeonCrateData::new, NAME);
    }

    // Force-creating resolver for the cross-server WRITE path. A crate claim merged from a sibling must land on a
    // server that never materialised the legacy dungeon dim, or the claim is dropped. get() stays non-creating so the
    // read/publish path sends nothing on an idle server. Returns null only when the dimension has no LevelStem at all.
    public static DungeonCrateData getOrCreate(MinecraftServer server) {
        ServerLevel dungeon = DungeonDimensions.getOrCreateLevel(server, DungeonDimensions.DUNGEON);
        return dungeon == null ? null : dungeon.getDataStorage().computeIfAbsent(DungeonCrateData::load,
                DungeonCrateData::new, NAME);
    }

    // a player's rolled crate at a position, or null if they have never opened it (or it was refreshed away).
    public PlayerCrate get(long posKey, UUID player) {
        Map<UUID, PlayerCrate> byPlayer = crates.get(posKey);
        return byPlayer == null ? null : byPlayer.get(player);
    }

    public void put(long posKey, UUID player, PlayerCrate crate) {
        crates.computeIfAbsent(posKey, k -> new HashMap<>()).put(player, crate);
        setDirty();
    }

    // cross-server state sync write path: MERGE a sibling's rolled crates per (crate, player) rather than replacing. A
    // replace would drop a claim another server made for a different player in the same window; a merge keeps every
    // player's record. Crate positions are globally unique and identical across servers (floor N at the same X), so
    // the same crate for the same player means the same thing everywhere, which makes carrying "you already looted
    // this" across a hop correct.
    //
    // Conflict rule for one (crate, player): keep the entry from the LATER refresh window (higher rolledEpoch); on an
    // equal window, keep a claimed record over an unclaimed one so a taken reward is never handed out twice.
    public void mergeInto(CompoundTag tag) {
        boolean changed = false;
        ListTag list = tag.getList("crates", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            long posKey = entry.getLong("pos");
            ListTag players = entry.getList("players", Tag.TAG_COMPOUND);
            for (int j = 0; j < players.size(); j++) {
                CompoundTag pe = players.getCompound(j);
                UUID id = pe.getUUID("uuid");
                PlayerCrate incoming = PlayerCrate.load(pe.getCompound("crate"));
                Map<UUID, PlayerCrate> byPlayer = crates.computeIfAbsent(posKey, k -> new HashMap<>());
                PlayerCrate current = byPlayer.get(id);
                if (current == null || supersedes(incoming, current)) {
                    byPlayer.put(id, incoming);
                    changed = true;
                }
            }
        }
        if (changed) {
            setDirty();
        }
    }

    // does the incoming roll win over the one already held? Later window wins; an equal window breaks toward the
    // claimed record, so a taken reward survives.
    //
    // THIS MUST BE A TOTAL ORDER. Both servers run this on the same pair with arguments swapped, so the rule must name
    // the same winner from either side. Epoch and claimed alone did not: two unclaimed records in the SAME window with
    // DIFFERENT rewards made supersedes() false in BOTH directions, so each server kept its own, republished, and
    // rejected the other's for ever. That was the crate half of the echo loop between smp and OW1. The tie-breaks
    // below only run when epoch and claimed are already equal, so they cannot change which roll is live.
    private static boolean supersedes(PlayerCrate incoming, PlayerCrate current) {
        if (incoming.rolledEpoch != current.rolledEpoch) {
            return incoming.rolledEpoch > current.rolledEpoch;
        }
        if (incoming.claimed != current.claimed) {
            return incoming.claimed;
        }
        // A real rolled reward beats a blank record: an empty stack here means this server never rolled one.
        if (incoming.reward.isEmpty() != current.reward.isEmpty()) {
            return current.reward.isEmpty();
        }
        // Two EMPTY rewards are equivalent; nothing below may separate them. An air stack can carry a leftover count,
        // so comparing counts read air(5) and air(0) as different records and the pair republished for ever (seen live
        // between smp and OW1). Both empty means nothing to choose, so skip to the tier.
        if (!incoming.reward.isEmpty() || !current.reward.isEmpty()) {
            int byItem = rewardKey(incoming).compareTo(rewardKey(current));
            if (byItem != 0) {
                return byItem > 0;
            }
            if (incoming.reward.getCount() != current.reward.getCount()) {
                return incoming.reward.getCount() > current.reward.getCount();
            }
        }
        // Equivalent records: answer false so nothing is marked dirty and nothing is republished.
        return incoming.tier > current.tier;
    }

    // stable identity for the rolled item, a deterministic tie-break only. The registry name is the same string on
    // every server; an unregistered or empty stack collapses to "" and orders first.
    private static String rewardKey(PlayerCrate crate) {
        if (crate.reward.isEmpty()) {
            return "";
        }
        return String.valueOf(ForgeRegistries.ITEMS.getKey(crate.reward.getItem()));
    }

    public static DungeonCrateData load(CompoundTag tag) {
        DungeonCrateData data = new DungeonCrateData();
        ListTag list = tag.getList("crates", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            long posKey = entry.getLong("pos");
            Map<UUID, PlayerCrate> byPlayer = new HashMap<>();
            ListTag players = entry.getList("players", Tag.TAG_COMPOUND);
            for (int j = 0; j < players.size(); j++) {
                CompoundTag pe = players.getCompound(j);
                byPlayer.put(pe.getUUID("uuid"), PlayerCrate.load(pe.getCompound("crate")));
            }
            if (!byPlayer.isEmpty()) {
                data.crates.put(posKey, byPlayer);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        // SORTED, not HashMap order. This save doubles as the read supplier for the cross-server state sync, which
        // publishes when the serialised bytes change. HashMap order depends on insertion history, so two servers with
        // the SAME crates after a merge could write them in different orders, each see a change, and republish for
        // ever (seen live on smp and OW1). Sorting makes the bytes a function of content alone. Order carries no meaning on load.
        List<Long> positions = new ArrayList<>(crates.keySet());
        Collections.sort(positions);
        for (Long posKey : positions) {
            Map<UUID, PlayerCrate> byPlayer = crates.get(posKey);
            CompoundTag entry = new CompoundTag();
            entry.putLong("pos", posKey);
            ListTag players = new ListTag();
            List<UUID> ids = new ArrayList<>(byPlayer.keySet());
            ids.sort(Comparator.comparing(UUID::toString));
            for (UUID id : ids) {
                CompoundTag pt = new CompoundTag();
                pt.putUUID("uuid", id);
                pt.put("crate", byPlayer.get(id).save());
                players.add(pt);
            }
            entry.put("players", players);
            list.add(entry);
        }
        tag.put("crates", list);
        return tag;
    }
}
