package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import com.google.common.collect.ImmutableList;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.level.progress.ChunkProgressListenerFactory;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;

import java.util.Map;
import java.util.concurrent.Executor;

// dimension keys and level resolution for the dungeon addon.
//
// TWO generations of dimension live here:
//   * the LEGACY dungeon dim ("dungeon"), a superflat void world defined under
//     data/shuruis_dmz_dungeons/dimension[_type]/. /rg dungeon tp, warps and the per-dim rules (DungeonRules /
//     DungeonRuleEvents) are scoped to it via isDungeon(). UNTOUCHED by the themed-dimension pivot.
//   * the SEVEN themed floor dims (overworld, namek, kai, stony, otherworld, nether, end), noise/flat worlds under
//     the same data/ tree. Procedural floors live here: a floor's theme picks its dimension (levelForTheme) and
//     terrain comes from that dimension's own generator instead of a stamp.
public final class DungeonDimensions {

    // Dungeon dimension id, now in the dmz_ragnarok namespace. The dimension/biome rename moved the dungeon dims (this
    // legacy one and the seven themed floor dims below), plus their dimension_type, biome and noise_settings files,
    // from shuruis_dmz_dungeons to dmz_ragnarok. The old shuruis_dmz_dungeons JSON files are kept as datapack shims so
    // a pre-migration world still loads; unmigrated worlds must be migrated with world-tools/ns-rename. NOTE the three
    // dungeon SavedData (DungeonRules, DungeonCrateData, DungeonFloors) live in this dimension's storage folder, so
    // their .dat files travel when the tool renames the dimension folder; their NAME strings are unchanged.
    public static final ResourceLocation ID = new ResourceLocation("dmz_ragnarok", "dungeon");

    public static final ResourceKey<Level> DUNGEON = ResourceKey.create(Registries.DIMENSION, ID);

    public static final ResourceKey<DimensionType> DUNGEON_TYPE = ResourceKey.create(Registries.DIMENSION_TYPE, ID);

    // themed floor-dimension level keys, one per authored dimension JSON.
    public static final ResourceKey<Level> OVERWORLD = themeLevel("overworld");
    public static final ResourceKey<Level> NAMEK = themeLevel("namek");
    public static final ResourceKey<Level> KAI = themeLevel("kai");
    public static final ResourceKey<Level> STONY = themeLevel("stony");
    public static final ResourceKey<Level> OTHERWORLD = themeLevel("otherworld");
    public static final ResourceKey<Level> NETHER = themeLevel("nether");
    public static final ResourceKey<Level> END = themeLevel("end");
    // planet floor dims. Their dimension JSONs fix the biome to dmz_ragnarok:vegeta / :beerus so SU's per-biome tint
    // paints the shared namek_* surface set to each planet; the theme name matches the dimension path.
    public static final ResourceKey<Level> VEGETA = themeLevel("vegeta");
    public static final ResourceKey<Level> BEERUS = themeLevel("beerus");

    // every themed floor-dim level key, backing the "any dungeon dimension" predicate. The per-dim rules (ki-block
    // destruction, pvp, timers, no-natural-spawn) must reach these too.
    private static final java.util.Set<ResourceKey<Level>> THEMED = java.util.Set.of(
            OVERWORLD, NAMEK, KAI, STONY, OTHERWORLD, NETHER, END, VEGETA, BEERUS);

    private static ResourceKey<Level> themeLevel(String path) {
        // Themed floor dims are DIMENSION ids (see ID above), now in the dmz_ragnarok namespace after the rename. The
        // old shuruis_dmz_dungeons JSON files remain as datapack shims.
        return ResourceKey.create(Registries.DIMENSION, new ResourceLocation("dmz_ragnarok", path));
    }

    private DungeonDimensions() {
    }

    // map a floor's theme string (one of DungeonFloorConfig.THEMES) to its themed dimension. NOTE the deliberate name
    // mismatch: the SU-era theme KAIO maps to the "kai" dimension. An unknown or renamed theme degrades to overworld.
    public static ResourceKey<Level> levelForTheme(String theme) {
        if (theme == null) {
            return OVERWORLD;
        }
        switch (theme.toUpperCase()) {
            case "NAMEK":
                return NAMEK;
            case "KAIO":
            case "KAI":
                return KAI;
            case "STONY":
                // retired from the theme picker (VEGETA carries the "Saiyan" label now) but kept: an existing stony
                // floor must still resolve to its baked dmz_ragnarok:stony dimension, or its content is orphaned. Do
                // NOT remove this case or the STONY level key / THEMED entry.
                return STONY;
            case "OTHERWORLD":
                return OTHERWORLD;
            case "NETHER":
                return NETHER;
            case "END":
                return END;
            case "VEGETA":
            case "SAIYAN":
                return VEGETA;
            case "BEERUS":
                return BEERUS;
            case "OVERWORLD":
            default:
                return OVERWORLD;
        }
    }

    public static ServerLevel level(MinecraftServer server) {
        return server == null ? null : server.getLevel(DUNGEON);
    }

    // legacy-only test: singles out the superflat dungeon dim from a themed floor dim. No rule needs that distinction
    // (all use isAnyDungeon below), but /rg dungeon tp and the warps target the legacy dim specifically, so keep it.
    public static boolean isDungeon(Level level) {
        return level != null && level.dimension().equals(DUNGEON);
    }

    // true for the legacy superflat dungeon dim AND every themed floor dim. The scope the per-dim rules key off
    // (ki-block destruction, pvp toggle, time limit / cooldown, no-natural-spawn), so a rule set in the /rg dungeon
    // edit GUI applies on procedural floors, not just the legacy dim.
    public static boolean isAnyDungeon(ResourceKey<Level> key) {
        return key != null && (key.equals(DUNGEON) || THEMED.contains(key));
    }

    public static boolean isAnyDungeon(Level level) {
        return level != null && isAnyDungeon(level.dimension());
    }

    // ensure a datapack-defined dimension exists as a running ServerLevel, creating it on the fly if Forge did not.
    // Returns null only when the datapack does not define the dimension (LevelStem absent), which the caller must
    // treat as "this floor cannot generate" rather than crash.
    //
    // WHY: MinecraftServer only creates levels for the LEVEL_STEM entries it knew about, so a dimension added after a
    // world was made can be in the registry yet have no ServerLevel; server.getLevel then returns null and any
    // teleport/generation NPEs. We probe getLevel, and if null build the ServerLevel from the registered LevelStem and
    // register it into the live level map (the same steps MinecraftServer.createLevels performs).
    public static synchronized ServerLevel getOrCreateLevel(MinecraftServer server, ResourceKey<Level> levelKey) {
        // Delegates to Shurui's Utilities: the space and surface dimensions hit the identical problem, so creation
        // lives in one place and both callers get every fix. Returns null when there is no LevelStem.
        return net.shurui.shuruisutilities.util.DynamicLevels.getOrCreate(server, levelKey);
    }

}
