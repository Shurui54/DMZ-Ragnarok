package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.shurui.dev.shuruis_dmz_dungeons.Config;

// per-world rule state for the dungeon dim, persisted as SavedData on the DUNGEON level so it travels with the save.
// Read-only at runtime: every field seeds from the mod's Config the first time the SavedData is created for a world,
// and is read live by the enforcers (MixinDmzKiGriefDungeon, DungeonRuleEvents, DungeonTimeEvents).
public class DungeonRules extends SavedData {

    // Pinned literal: the on-disk data/<NAME>.dat filename. Deriving it from MODID would silently orphan saved dungeon rules if MODID is renamed.
    public static final String NAME = "shuruis_dmz_dungeons_dungeon_rules";

    // every field seeds from the Config defaults so a fresh world takes the server's configured rules; once written
    // they persist, and the Config value applies again only for keys absent from the NBT.
    public boolean pvp = Config.dungeonPvp;
    public boolean kiBlockDestruction = Config.dungeonKiBlockDestruction;
    // Hand-mining protection. Moved here from Config so it rides the dungeons:rules cross-server sync like pvp and
    // kiBlockDestruction: read LIVE off the unsynced toml, retuning it on one shard left the other three stale.
    // Enforced in DungeonRuleEvents.blockEditingDenied. Seeds from the toml on first world creation; after that this
    // SavedData is authoritative and the value travels with the sync.
    public boolean blockEditing = Config.dungeonBlockEditing;
    public int timeLimitSeconds = Config.dungeonTimeLimitSeconds;
    public int cooldownSeconds = Config.dungeonCooldownSeconds;

    // hard bounds for the two timers, matching the Config defineInRange ranges so a GUI/command edit can never push a
    // value past what the config allows. shared by the GUI save clamp below.
    public static final int MIN_SECONDS = 0;
    public static final int MAX_SECONDS = 86400;

    public DungeonRules() {
    }

    // GUI save path: overwrite the rule fields and persist. The two timers are clamped to their real range so the
    // client can never push an out-of-range value onto disk. This is the ONLY mutator on this class; fresh-world
    // seeding happens in the field initializers when the SavedData is first created, and this only runs on an explicit save.
    public void apply(boolean pvp, boolean kiBlockDestruction, boolean blockEditing,
                      int timeLimitSeconds, int cooldownSeconds) {
        this.pvp = pvp;
        this.kiBlockDestruction = kiBlockDestruction;
        this.blockEditing = blockEditing;
        this.timeLimitSeconds = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, timeLimitSeconds));
        this.cooldownSeconds = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, cooldownSeconds));
        setDirty();
    }

    // cross-server state sync write path: take rules a sibling edited into THIS live instance, reusing apply() so the
    // range clamp guards a networked value exactly as it guards the GUI. Every field is present in a synced tag
    // (straight from save()), so there is nothing to preserve.
    public void loadInto(CompoundTag tag) {
        // blockEditing may be absent in a tag from an older sibling; keep the current value rather than forcing it
        // off, so a sync never silently disables hand-mining protection nobody asked to disable.
        boolean be = tag.contains("blockEditing") ? tag.getBoolean("blockEditing") : this.blockEditing;
        apply(tag.getBoolean("pvp"), tag.getBoolean("kiBlockDestruction"), be,
                tag.getInt("timeLimitSeconds"), tag.getInt("cooldownSeconds"));
    }

    public static DungeonRules get(ServerLevel dungeonLevel) {
        // 1.20.1 signature: computeIfAbsent(deserializer, constructor, name)
        return dungeonLevel.getDataStorage().computeIfAbsent(DungeonRules::load, DungeonRules::new, NAME);
    }

    public static DungeonRules get(MinecraftServer server) {
        ServerLevel dungeon = DungeonDimensions.level(server);
        return dungeon == null ? null : get(dungeon);
    }

    // Force-creating resolver for the cross-server WRITE path. A rule edit made on a dungeon-having server must land
    // on a server that never materialised the legacy dungeon dim, or the edit is dropped. get() stays non-creating so
    // the read/publish path sends nothing on an idle server. Returns null only when the dimension has no LevelStem at all.
    public static DungeonRules getOrCreate(MinecraftServer server) {
        ServerLevel dungeon = DungeonDimensions.getOrCreateLevel(server, DungeonDimensions.DUNGEON);
        return dungeon == null ? null : get(dungeon);
    }

    public static DungeonRules load(CompoundTag tag) {
        DungeonRules r = new DungeonRules();
        // for any key absent from the NBT, keep the Config default already seeded in the field init
        if (tag.contains("pvp")) {
            r.pvp = tag.getBoolean("pvp");
        }
        if (tag.contains("kiBlockDestruction")) {
            r.kiBlockDestruction = tag.getBoolean("kiBlockDestruction");
        }
        if (tag.contains("blockEditing")) {
            r.blockEditing = tag.getBoolean("blockEditing");
        }
        if (tag.contains("timeLimitSeconds")) {
            r.timeLimitSeconds = tag.getInt("timeLimitSeconds");
        }
        if (tag.contains("cooldownSeconds")) {
            r.cooldownSeconds = tag.getInt("cooldownSeconds");
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("pvp", pvp);
        tag.putBoolean("kiBlockDestruction", kiBlockDestruction);
        tag.putBoolean("blockEditing", blockEditing);
        tag.putInt("timeLimitSeconds", timeLimitSeconds);
        tag.putInt("cooldownSeconds", cooldownSeconds);
        return tag;
    }
}
