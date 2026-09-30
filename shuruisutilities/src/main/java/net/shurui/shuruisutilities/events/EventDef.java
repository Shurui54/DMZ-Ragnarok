package net.shurui.shuruisutilities.events;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * One timed event definition and its DETERMINISTIC NBT codec. This is the shared data model both halves of the
 * event system read: the CORE editor (which edits a local copy and ships it over packet 129) and the Ragnarok
 * Key's {@code EventDefs} SavedData (which persists the set and syncs it across shards). It lives in core so both
 * can use it without the key importing a client screen or the editor importing the key.
 *
 * <p><b>Why the codec is deliberately Minecraft NBT and nothing else.</b> The def travels the shard state sync as
 * a {@link CompoundTag} (that is what {@code ShardStateSync} carries) and rides the editor packets the same way.
 * This class therefore imports ONLY {@code net.minecraft.nbt} and {@code java.*}: no {@code ResourceLocation},
 * no {@code ItemStack}, no registry. Every id (item ids, entity ids, region names, raid/rift ids) is a plain
 * string, and the one opaque payload (a dungeon floor config) is stored as a nested {@link CompoundTag} verbatim.
 * That keeps the codec resolvable without the game registry, so the standalone harness under tools/events-tests
 * can round-trip it against the mapped Minecraft jar with no Bootstrap.
 *
 * <p><b>Determinism, the republish-loop guard.</b> {@code ShardStateSync} republishes on an NBT hash change, so a
 * read supplier that serialises the same logical def to different bytes on two shards makes them republish at each
 * other for ever (the {@code DungeonFloors.saveEpochs} bug). NBT compound keys hash-order identically for an equal
 * key set across JVMs, so the only real risk is LIST order. Every top-level list is therefore sorted by a stable
 * key at {@link #toNbt} time. Element sub-lists that the operator authors in a meaningful order (reward token
 * grammars) are left in that order, because they come from one stored source and so are already identical on
 * every shard.
 */
public final class EventDef
{
    /** The staff override on the def itself (rides the synced def, so it takes effect network wide). */
    public enum ManualState
    {
        /** Follow the schedule. */
        AUTO,
        /** Force the event on regardless of the schedule. */
        FORCE_ON,
        /** Force the event off regardless of the schedule. */
        FORCE_OFF;

        public static ManualState parse(String s)
        {
            if (s == null)
                return AUTO;
            try
            {
                return valueOf(s);
            }
            catch (IllegalArgumentException e)
            {
                return AUTO;
            }
        }
    }

    /** The loot hosts an event can add drops to. Raid/tournament use the reward-token grammar; the rest CustomDrop. */
    public enum LootHost
    {
        RAID, TOURNAMENT, DUNGEON_CRATE, DUNGEON_BOSS, SU_CRATE, AIRDROP, REGION_MOB, MOB_ANY;

        static LootHost parse(String s)
        {
            if (s == null)
                return MOB_ANY;
            try
            {
                return valueOf(s);
            }
            catch (IllegalArgumentException e)
            {
                return MOB_ANY;
            }
        }
    }

    // ---- identity + lifecycle ----------------------------------------------------------------------------
    public String id = "";
    public String name = "";
    public String description = "";
    public boolean enabled = true;
    public ManualState manualState = ManualState.AUTO;

    /** A forced-start override instant (DB clock millis), or 0 for none. Set by {@code /event start [min]}. */
    public long overrideStartMillis;
    /** A forced-stop override instant (DB clock millis), or 0 for none. Set by {@code /event stop}. */
    public long overrideEndMillis;

    /** Monotonic edit revision, the primary key of the merged per-id sync (higher rev wins). */
    public long rev;
    /** The serverId of the shard that last wrote this def (the sync tie-break after rev). */
    public String updatedBy = "";
    /** A tombstone: a deleted def travels as a delete marker and is pruned after 30 days (see EventDefs, E1). */
    public boolean deleted;
    /** When this def was marked deleted (DB clock millis), for the 30-day prune. 0 while live. */
    public long deletedAtMillis;

    // ---- sections ----------------------------------------------------------------------------------------
    public final Schedule schedule = new Schedule();
    public final Theme theme = new Theme();
    public final Announce announce = new Announce();
    public final Content content = new Content();

    public EventDef() {}

    public EventDef(String id)
    {
        this.id = id == null ? "" : id;
    }

    // ---- codec -------------------------------------------------------------------------------------------

    /** Serialise deterministically. Every top-level list is sorted by a stable key so two shards agree byte for byte. */
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        t.putString("name", name);
        t.putString("description", description);
        t.putBoolean("enabled", enabled);
        t.putString("manualState", manualState.name());
        t.putLong("overrideStartMillis", overrideStartMillis);
        t.putLong("overrideEndMillis", overrideEndMillis);
        t.putLong("rev", rev);
        t.putString("updatedBy", updatedBy);
        t.putBoolean("deleted", deleted);
        t.putLong("deletedAtMillis", deletedAtMillis);
        t.put("schedule", schedule.toNbt());
        t.put("theme", theme.toNbt());
        t.put("announce", announce.toNbt());
        t.put("content", content.toNbt());
        return t;
    }

    public static EventDef fromNbt(CompoundTag t)
    {
        EventDef d = new EventDef();
        d.id = t.getString("id");
        d.name = t.getString("name");
        d.description = t.getString("description");
        d.enabled = !t.contains("enabled") || t.getBoolean("enabled");
        d.manualState = ManualState.parse(t.getString("manualState"));
        d.overrideStartMillis = t.getLong("overrideStartMillis");
        d.overrideEndMillis = t.getLong("overrideEndMillis");
        d.rev = t.getLong("rev");
        d.updatedBy = t.getString("updatedBy");
        d.deleted = t.getBoolean("deleted");
        d.deletedAtMillis = t.getLong("deletedAtMillis");
        d.schedule.readNbt(t.getCompound("schedule"));
        d.theme.readNbt(t.getCompound("theme"));
        d.announce.readNbt(t.getCompound("announce"));
        d.content.readNbt(t.getCompound("content"));
        return d;
    }

    // ---- sections as small value holders -----------------------------------------------------------------

    /** The authored window: the absolute instants are authoritative; the locals and zone drive display and annual. */
    public static final class Schedule
    {
        public long startEpochMillis;
        public long endEpochMillis;
        public String zone = "UTC";
        public String startLocal = "";
        public String endLocal = "";
        public boolean annual;

        CompoundTag toNbt()
        {
            CompoundTag t = new CompoundTag();
            t.putLong("startEpochMillis", startEpochMillis);
            t.putLong("endEpochMillis", endEpochMillis);
            t.putString("zone", zone);
            t.putString("startLocal", startLocal);
            t.putString("endLocal", endLocal);
            t.putBoolean("annual", annual);
            return t;
        }

        void readNbt(CompoundTag t)
        {
            startEpochMillis = t.getLong("startEpochMillis");
            endEpochMillis = t.getLong("endEpochMillis");
            zone = t.contains("zone") ? t.getString("zone") : "UTC";
            startLocal = t.getString("startLocal");
            endLocal = t.getString("endLocal");
            annual = t.getBoolean("annual");
        }
    }

    /** The web/bot theme contract: a key plus three hex colours, a banner caption and an https banner url. */
    public static final class Theme
    {
        public String key = "";
        public String primary = "#ffffff";
        public String accent = "#ffffff";
        public String background = "#000000";
        public String bannerText = "";
        public String banner = "";

        CompoundTag toNbt()
        {
            CompoundTag t = new CompoundTag();
            t.putString("key", key);
            t.putString("primary", primary);
            t.putString("accent", accent);
            t.putString("background", background);
            t.putString("bannerText", bannerText);
            t.putString("banner", banner);
            return t;
        }

        void readNbt(CompoundTag t)
        {
            key = t.getString("key");
            primary = t.contains("primary") ? t.getString("primary") : "#ffffff";
            accent = t.contains("accent") ? t.getString("accent") : "#ffffff";
            background = t.contains("background") ? t.getString("background") : "#000000";
            bannerText = t.getString("bannerText");
            banner = t.getString("banner");
        }
    }

    /** In-game and Discord announcement copy. The Discord channel/role and their env come from the bot. */
    public static final class Announce
    {
        public boolean announce = true;
        public int reminderMinutes = 1440;
        public String inGameStartTitle = "";
        public String inGameStartMessage = "";
        public String inGameEndMessage = "";

        CompoundTag toNbt()
        {
            CompoundTag t = new CompoundTag();
            t.putBoolean("announce", announce);
            t.putInt("reminderMinutes", reminderMinutes);
            t.putString("inGameStartTitle", inGameStartTitle);
            t.putString("inGameStartMessage", inGameStartMessage);
            t.putString("inGameEndMessage", inGameEndMessage);
            return t;
        }

        void readNbt(CompoundTag t)
        {
            announce = !t.contains("announce") || t.getBoolean("announce");
            reminderMinutes = t.contains("reminderMinutes") ? t.getInt("reminderMinutes") : 1440;
            inGameStartTitle = t.getString("inGameStartTitle");
            inGameStartMessage = t.getString("inGameStartMessage");
            inGameEndMessage = t.getString("inGameEndMessage");
        }
    }

    /** Everything the event adds on top of normal content. Every list is optional and independent. */
    public static final class Content
    {
        /** Names of the eventOnly NPC regions the event brings live. */
        public final List<String> eventRegions = new ArrayList<>();
        /** Ids of the eventOnly raid defs the event makes runnable. */
        public final List<String> eventRaids = new ArrayList<>();
        /** Ids of the eventOnly rift defs the event makes spawnable. */
        public final List<String> eventRifts = new ArrayList<>();

        public final List<RegionMobAdd> regionMobs = new ArrayList<>();
        public final List<EventFloor> eventFloors = new ArrayList<>();
        public final List<LootAdd> loot = new ArrayList<>();
        public final List<EventQuest> quests = new ArrayList<>();
        public final List<ShopOffer> shop = new ArrayList<>();
        /** Inventory loot boxes this event configures (the Halloween Box and the Pumpkin Bag), keyed by boxType. */
        public final List<LootBox> lootBoxes = new ArrayList<>();
        /** Temporary world holograms the event spawns at start and despawns at end (never persisted). */
        public final List<EventHologram> holograms = new ArrayList<>();

        public final Tokens tokens = new Tokens();
        public final Leaderboard leaderboard = new Leaderboard();
        public final Boosts boosts = new Boosts();

        CompoundTag toNbt()
        {
            CompoundTag t = new CompoundTag();
            t.put("eventRegions", strings(eventRegions));
            t.put("eventRaids", strings(eventRaids));
            t.put("eventRifts", strings(eventRifts));

            t.put("regionMobs", list(regionMobs, RegionMobAdd::sortKey, RegionMobAdd::toNbt));
            t.put("eventFloors", list(eventFloors, f -> f.label, EventFloor::toNbt));
            t.put("loot", list(loot, LootAdd::sortKey, LootAdd::toNbt));
            t.put("quests", list(quests, q -> q.id, EventQuest::toNbt));
            t.put("shop", list(shop, ShopOffer::sortKey, ShopOffer::toNbt));
            t.put("lootBoxes", list(lootBoxes, lb -> lb.boxType, LootBox::toNbt));
            t.put("holograms", list(holograms, EventHologram::sortKey, EventHologram::toNbt));

            t.put("tokens", tokens.toNbt());
            t.put("leaderboard", leaderboard.toNbt());
            t.put("boosts", boosts.toNbt());
            return t;
        }

        void readNbt(CompoundTag t)
        {
            readStrings(t, "eventRegions", eventRegions);
            readStrings(t, "eventRaids", eventRaids);
            readStrings(t, "eventRifts", eventRifts);

            regionMobs.clear();
            for (int i = 0; i < t.getList("regionMobs", Tag.TAG_COMPOUND).size(); i++)
                regionMobs.add(RegionMobAdd.fromNbt(t.getList("regionMobs", Tag.TAG_COMPOUND).getCompound(i)));
            eventFloors.clear();
            for (int i = 0; i < t.getList("eventFloors", Tag.TAG_COMPOUND).size(); i++)
                eventFloors.add(EventFloor.fromNbt(t.getList("eventFloors", Tag.TAG_COMPOUND).getCompound(i)));
            loot.clear();
            for (int i = 0; i < t.getList("loot", Tag.TAG_COMPOUND).size(); i++)
                loot.add(LootAdd.fromNbt(t.getList("loot", Tag.TAG_COMPOUND).getCompound(i)));
            quests.clear();
            for (int i = 0; i < t.getList("quests", Tag.TAG_COMPOUND).size(); i++)
                quests.add(EventQuest.fromNbt(t.getList("quests", Tag.TAG_COMPOUND).getCompound(i)));
            shop.clear();
            for (int i = 0; i < t.getList("shop", Tag.TAG_COMPOUND).size(); i++)
                shop.add(ShopOffer.fromNbt(t.getList("shop", Tag.TAG_COMPOUND).getCompound(i)));
            lootBoxes.clear();
            for (int i = 0; i < t.getList("lootBoxes", Tag.TAG_COMPOUND).size(); i++)
                lootBoxes.add(LootBox.fromNbt(t.getList("lootBoxes", Tag.TAG_COMPOUND).getCompound(i)));
            holograms.clear();
            for (int i = 0; i < t.getList("holograms", Tag.TAG_COMPOUND).size(); i++)
                holograms.add(EventHologram.fromNbt(t.getList("holograms", Tag.TAG_COMPOUND).getCompound(i)));

            tokens.readNbt(t.getCompound("tokens"));
            leaderboard.readNbt(t.getCompound("leaderboard"));
            boosts.readNbt(t.getCompound("boosts"));
        }
    }

    /** A mob roster addition/swap for one or more regions. Extra spawn configs come from a template region. */
    public static final class RegionMobAdd
    {
        /** Region names the addition applies to, or a single "*" for every region. */
        public final List<String> targets = new ArrayList<>();
        /** Dimension ids the addition is limited to, or empty for all. */
        public final List<String> dims = new ArrayList<>();
        /** The name of the NPC region whose spawn configs are appended to each target while the event runs. */
        public String templateRegion = "";

        String sortKey()
        {
            return templateRegion + "\u0000" + String.join(",", sorted(targets)) + "\u0000" + String.join(",", sorted(dims));
        }

        CompoundTag toNbt()
        {
            CompoundTag t = new CompoundTag();
            t.put("targets", strings(targets));
            t.put("dims", strings(dims));
            t.putString("templateRegion", templateRegion);
            return t;
        }

        static RegionMobAdd fromNbt(CompoundTag t)
        {
            RegionMobAdd r = new RegionMobAdd();
            readStrings(t, "targets", r.targets);
            readStrings(t, "dims", r.dims);
            r.templateRegion = t.getString("templateRegion");
            return r;
        }
    }

    /** One additive event dungeon floor: a label plus the opaque floor config the Dungeons module produced. */
    public static final class EventFloor
    {
        public String label = "";
        /** Opaque to core: a Dungeons {@code DungeonFloorConfig} NBT, stored and handed back verbatim. */
        public CompoundTag floorCfg = new CompoundTag();

        CompoundTag toNbt()
        {
            CompoundTag t = new CompoundTag();
            t.putString("label", label);
            t.put("floorCfg", floorCfg.copy());
            return t;
        }

        static EventFloor fromNbt(CompoundTag t)
        {
            EventFloor f = new EventFloor();
            f.label = t.getString("label");
            f.floorCfg = t.getCompound("floorCfg").copy();
            return f;
        }
    }

    /** Drops an event adds to a host. Raid/tournament tokens use the reward grammar; the rest CustomDrop SNBT. */
    public static final class LootAdd
    {
        public LootHost host = LootHost.MOB_ANY;
        /** The host instance the add applies to (a def id, a crate name, a tier, ...), or "*" for all. */
        public String hostId = "*";
        /** An optional extra filter (a raid rank, a crate tier, a mob id), host-specific, or empty. */
        public String filter = "";
        /** Reward token grammar (raids/tournaments) or CustomDrop SNBT lines (the rest), in author order. */
        public final List<String> tokens = new ArrayList<>();

        String sortKey()
        {
            return host.name() + "\u0000" + hostId + "\u0000" + filter;
        }

        CompoundTag toNbt()
        {
            CompoundTag t = new CompoundTag();
            t.putString("host", host.name());
            t.putString("hostId", hostId);
            t.putString("filter", filter);
            t.put("tokens", stringsInOrder(tokens));
            return t;
        }

        static LootAdd fromNbt(CompoundTag t)
        {
            LootAdd l = new LootAdd();
            l.host = LootHost.parse(t.getString("host"));
            l.hostId = t.contains("hostId") ? t.getString("hostId") : "*";
            l.filter = t.getString("filter");
            readStrings(t, "tokens", l.tokens);
            return l;
        }
    }

    /** An event quest: a threshold on one of the event's own counters, paying reward tokens once. */
    public static final class EventQuest
    {
        public String id = "";
        public String title = "";
        /** The counter this quest tracks (an event metric name, e.g. "candy", "event_mob_kills"). */
        public String metric = "";
        /** An optional filter narrowing the metric (a mob id, a crate name, a floor label), or empty. */
        public String filter = "";
        public int threshold = 1;
        /** Reward token grammar granted on completion, in author order. */
        public final List<String> rewardTokens = new ArrayList<>();

        CompoundTag toNbt()
        {
            CompoundTag t = new CompoundTag();
            t.putString("id", id);
            t.putString("title", title);
            t.putString("metric", metric);
            t.putString("filter", filter);
            t.putInt("threshold", threshold);
            t.put("rewardTokens", stringsInOrder(rewardTokens));
            return t;
        }

        static EventQuest fromNbt(CompoundTag t)
        {
            EventQuest q = new EventQuest();
            q.id = t.getString("id");
            q.title = t.getString("title");
            q.metric = t.getString("metric");
            q.filter = t.getString("filter");
            q.threshold = t.getInt("threshold");
            readStrings(t, "rewardTokens", q.rewardTokens);
            return q;
        }
    }

    /** A shop line: give this item stack (SNBT / grammar) for this many event tokens. */
    public static final class ShopOffer
    {
        /** What the player receives (a reward-grammar token, e.g. "item:minecraft:jack_o_lantern:1"). */
        public String give = "";
        /** The event-token cost. */
        public int cost = 1;

        String sortKey()
        {
            return give + "\u0000" + cost;
        }

        CompoundTag toNbt()
        {
            CompoundTag t = new CompoundTag();
            t.putString("give", give);
            t.putInt("cost", cost);
            return t;
        }

        static ShopOffer fromNbt(CompoundTag t)
        {
            ShopOffer o = new ShopOffer();
            o.give = t.getString("give");
            o.cost = t.getInt("cost");
            return o;
        }
    }

    /**
     * One inventory loot box (the Halloween Box, the Pumpkin Bag): its box type, a display name, an announcement
     * toggle plus template, and a weighted list of reward bundles. Each bundle is a weight, an optional rarity label
     * and a list of reward-grammar tokens (the SAME grammar the rest of the event loot uses, plus {@code token:} for
     * a themed event token and {@code cosmetic:} for a wardrobe cosmetic), so opening the box grants exactly one
     * rolled bundle. The roll, grant and announce are the Ragnarok Key's ({@code EventRewards}); this is only data.
     */
    public static final class LootBox
    {
        /** Matches a {@code LootBoxItem.boxType()}: "halloween_box" or "pumpkin_bag". The box's own id. */
        public String boxType = "";
        public String displayName = "";
        /** Whether opening broadcasts the announcement to everyone. */
        public boolean announce = true;
        /** The broadcast, '&amp;' colour codes and {player} / {box} / {item} placeholders. */
        public String announceTemplate = "&6{player} opened {box} and got {item}!";
        /** The weighted reward bundles, in author order. */
        public final List<Reward> rewards = new ArrayList<>();

        /** One weighted reward bundle: a weight, an optional rarity tag, and the reward-grammar tokens it grants. */
        public static final class Reward
        {
            public int weight = 1;
            /** An optional rarity label (e.g. "common", "rare"), for per-rarity announcement styling. */
            public String rarity = "";
            /** The reward-grammar tokens granted together when this bundle rolls, in author order. */
            public final List<String> tokens = new ArrayList<>();

            CompoundTag toNbt()
            {
                CompoundTag t = new CompoundTag();
                t.putInt("weight", Math.max(0, weight));
                t.putString("rarity", rarity);
                t.put("tokens", stringsInOrder(tokens));
                return t;
            }

            static Reward fromNbt(CompoundTag t)
            {
                Reward r = new Reward();
                r.weight = Math.max(0, t.getInt("weight"));
                r.rarity = t.getString("rarity");
                readStrings(t, "tokens", r.tokens);
                return r;
            }
        }

        CompoundTag toNbt()
        {
            CompoundTag t = new CompoundTag();
            t.putString("boxType", boxType);
            t.putString("displayName", displayName);
            t.putBoolean("announce", announce);
            t.putString("announceTemplate", announceTemplate);
            // Bundles keep author order (a single stored source, identical on every shard, like reward token lists).
            ListTag list = new ListTag();
            for (Reward r : rewards)
                if (r != null)
                    list.add(r.toNbt());
            t.put("rewards", list);
            return t;
        }

        static LootBox fromNbt(CompoundTag t)
        {
            LootBox b = new LootBox();
            b.boxType = t.getString("boxType");
            b.displayName = t.getString("displayName");
            b.announce = !t.contains("announce") || t.getBoolean("announce");
            b.announceTemplate = t.contains("announceTemplate") ? t.getString("announceTemplate")
                    : "&6{player} opened {box} and got {item}!";
            ListTag list = t.getList("rewards", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++)
                b.rewards.add(Reward.fromNbt(list.getCompound(i)));
            return b;
        }

        /** The denominator of the weighted roll. Zero means this box has nothing to give. */
        public int totalWeight()
        {
            int sum = 0;
            for (Reward r : rewards)
                if (r != null && r.weight > 0)
                    sum += r.weight;
            return sum;
        }
    }

    /** A temporary world hologram: an anchor in a dimension and one text line. Spawned at start, despawned at end. */
    public static final class EventHologram
    {
        public String dim = "minecraft:overworld";
        public double x;
        public double y;
        public double z;
        /** The text line, raw '&' colour codes kept and translated at draw. */
        public String text = "";

        String sortKey()
        {
            return dim + "\u0000" + x + "\u0000" + y + "\u0000" + z + "\u0000" + text;
        }

        CompoundTag toNbt()
        {
            CompoundTag t = new CompoundTag();
            t.putString("dim", dim);
            t.putDouble("x", x);
            t.putDouble("y", y);
            t.putDouble("z", z);
            t.putString("text", text);
            return t;
        }

        static EventHologram fromNbt(CompoundTag t)
        {
            EventHologram h = new EventHologram();
            h.dim = t.contains("dim") ? t.getString("dim") : "minecraft:overworld";
            h.x = t.getDouble("x");
            h.y = t.getDouble("y");
            h.z = t.getDouble("z");
            h.text = t.getString("text");
            return h;
        }
    }

    /** The event token look, bound to the shipped {@code event_token} item by an NBT Variant + CustomModelData. */
    public static final class Tokens
    {
        /** The Variant tag written on a granted {@code event_token} (e.g. "halloween"), driving its model. */
        public String variant = "";
        public String displayName = "";

        CompoundTag toNbt()
        {
            CompoundTag t = new CompoundTag();
            t.putString("variant", variant);
            t.putString("displayName", displayName);
            return t;
        }

        void readNbt(CompoundTag t)
        {
            variant = t.getString("variant");
            displayName = t.getString("displayName");
        }
    }

    /** The event leaderboard: rank players by one metric and pay the top N reward-token bundles. */
    public static final class Leaderboard
    {
        public boolean enabled;
        public String metric = "";
        public int topN;
        /** Reward tokens for ranks 1..topN, one bundle per rank in order (rank 1 first). */
        public final List<String> rewards = new ArrayList<>();

        CompoundTag toNbt()
        {
            CompoundTag t = new CompoundTag();
            t.putBoolean("enabled", enabled);
            t.putString("metric", metric);
            t.putInt("topN", topN);
            t.put("rewards", stringsInOrder(rewards));
            return t;
        }

        void readNbt(CompoundTag t)
        {
            enabled = t.getBoolean("enabled");
            metric = t.getString("metric");
            topN = t.getInt("topN");
            readStrings(t, "rewards", rewards);
        }
    }

    /** Multiplicative boosts applied while the event runs. 1.0 means no change. */
    public static final class Boosts
    {
        public double tpMultiplier = 1.0;
        public double spawnRateMultiplier = 1.0;

        CompoundTag toNbt()
        {
            CompoundTag t = new CompoundTag();
            t.putDouble("tpMultiplier", tpMultiplier);
            t.putDouble("spawnRateMultiplier", spawnRateMultiplier);
            return t;
        }

        void readNbt(CompoundTag t)
        {
            tpMultiplier = t.contains("tpMultiplier") ? t.getDouble("tpMultiplier") : 1.0;
            spawnRateMultiplier = t.contains("spawnRateMultiplier") ? t.getDouble("spawnRateMultiplier") : 1.0;
        }
    }

    // ---- shared codec helpers ----------------------------------------------------------------------------

    private interface ToNbt<E>
    {
        CompoundTag apply(E e);
    }

    private interface Key<E>
    {
        String of(E e);
    }

    /** A ListTag of strings sorted alphabetically (for collections with no meaningful author order). */
    private static ListTag strings(List<String> values)
    {
        ListTag list = new ListTag();
        for (String s : sorted(values))
            list.add(StringTag.valueOf(s));
        return list;
    }

    /** A ListTag of strings in the given (author) order, for reward-grammar lists where order is meaningful. */
    private static ListTag stringsInOrder(List<String> values)
    {
        ListTag list = new ListTag();
        for (String s : values)
            list.add(StringTag.valueOf(s == null ? "" : s));
        return list;
    }

    /** A ListTag of compounds, sorted by a stable key so the same set serialises identically on every shard. */
    private static <E> ListTag list(List<E> values, Key<E> key, ToNbt<E> encode)
    {
        List<E> copy = new ArrayList<>(values);
        copy.sort(Comparator.comparing(e -> nullToEmpty(key.of(e))));
        ListTag list = new ListTag();
        for (E e : copy)
            list.add(encode.apply(e));
        return list;
    }

    private static void readStrings(CompoundTag t, String key, List<String> out)
    {
        out.clear();
        ListTag list = t.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++)
            out.add(list.getString(i));
    }

    private static List<String> sorted(List<String> in)
    {
        List<String> copy = new ArrayList<>();
        for (String s : in)
            copy.add(s == null ? "" : s);
        copy.sort(Comparator.naturalOrder());
        return copy;
    }

    private static String nullToEmpty(String s)
    {
        return s == null ? "" : s;
    }
}
