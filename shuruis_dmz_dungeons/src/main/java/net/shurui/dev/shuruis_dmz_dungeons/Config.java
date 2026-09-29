package net.shurui.dev.shuruis_dmz_dungeons;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

// The dungeon-dimension rules (PvP, ki block destruction, time limit, re-entry cooldown) are seeded from
// here into the DungeonRules SavedData at world load.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_dungeons", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Config {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue REQUIRE_OP_TO_EDIT = BUILDER
            .comment("Require permission level 2 (op) or creative mode to open the spawner admin GUI and run privileged /rg dungeon subcommands.")
            .define("requireOpToEdit", true);

    private static final ForgeConfigSpec.IntValue MAX_HIGHLIGHT_RADIUS = BUILDER
            .comment("Maximum radius (blocks) allowed for /rg dungeon highlight.")
            .defineInRange("maxHighlightRadius", 128, 1, 512);

    private static final ForgeConfigSpec.BooleanValue DUNGEON_PVP = BUILDER
            .comment("Allow players to damage each other inside the dungeon dimension.")
            .define("dungeonPvp", false);

    private static final ForgeConfigSpec.BooleanValue DUNGEON_KI_BLOCK_DESTRUCTION = BUILDER
            .comment("Allow DMZ ki attacks to break blocks inside the dungeon dimension.")
            .define("dungeonKiBlockDestruction", false);

    private static final ForgeConfigSpec.BooleanValue DUNGEON_BLOCK_EDITING = BUILDER
            .comment("Allow ORDINARY players to break and place blocks by hand inside the dungeon dimensions.",
                    "Off by default, and this is the setting that stops a boss arena being mined out from under a",
                    "fight: the ki-destruction rule above only covers DMZ ki attacks, so hand mining was never",
                    "checked at all. Anyone in CREATIVE mode is never restricted, so an arena can still be built and",
                    "dressed by staff; an operator playing in survival is held to the rule like everyone else.")
            .define("dungeonBlockEditing", false);

    private static final ForgeConfigSpec.IntValue DUNGEON_TIME_LIMIT_SECONDS = BUILDER
            .comment("How long a non-bypass player may stay in the dungeon dimension, in seconds. 0 disables the time limit.")
            .defineInRange("dungeonTimeLimitSeconds", 1800, 0, 86400);

    private static final ForgeConfigSpec.IntValue DUNGEON_COOLDOWN_SECONDS = BUILDER
            .comment("Cooldown (seconds) after a player is timed out of the dungeon before they may re-enter. 0 disables the cooldown.")
            .defineInRange("dungeonCooldownSeconds", 600, 0, 86400);

    // Procedural dungeon floors. These seed DungeonFloors the first time it is created for a world; after that
    // the on-disk model is authoritative and these apply only to newly added floors. Require Shurui's Key AND
    // Shurui's Utilities at runtime; without either the dungeon falls back to existing behaviour and these do nothing.
    private static final ForgeConfigSpec.IntValue DUNGEON_FLOOR_COUNT = BUILDER
            .comment("Number of procedural dungeon floors seeded into a fresh world. 0 seeds none.")
            .defineInRange("dungeonFloorCount", 5, 0, 256);

    private static final ForgeConfigSpec.IntValue DUNGEON_FLOOR_SIZE = BUILDER
            .comment("Default side length (blocks) of a seeded floor's terrain disc.",
                    "Kept at 250 on purpose: the barrier box grows with size squared, so a large default would make",
                    "every floor expensive to build. Raise a single floor per floor via /rg dungeon floor size when a big",
                    "archetype (the promenade needs ~1024) is wanted.")
            .defineInRange("dungeonFloorSize", 250, 64, 1120);

    private static final ForgeConfigSpec.IntValue DUNGEON_FLOOR_DEPTH = BUILDER
            .comment("Default depth (blocks) a seeded floor's solid body extends below its surface.")
            .defineInRange("dungeonFloorDepth", 96, 16, 124);

    private static final ForgeConfigSpec.IntValue DUNGEON_ROOM_BLOCKS_PER_TICK = BUILDER
            .comment("Per-tick block-write budget for the room-placement layer. Higher builds a floor faster but",
                    "costs more per server tick. The whole floor is placed over multiple ticks regardless.")
            .defineInRange("dungeonRoomBlocksPerTick", 40000, 1024, 500000);

    private static final ForgeConfigSpec.ConfigValue<String> DUNGEON_DEFAULT_LAYOUT_STYLE = BUILDER
            .comment("Default room-layout algorithm for floors that do not set their own.",
                    "One of: VARIED (roll a different archetype per floor), SPINE_AND_RIBS (prison labyrinth),",
                    "ROADS_AND_BUILDINGS (promenade town), INSUFFERABLE_CRYPT (enclosed labyrinth).",
                    "VARIED is the default because naming a single archetype here gives EVERY floor of the",
                    "dungeon the same one, which is the main reason floors read as copies of each other.",
                    "Naming an archetype still pins every floor to it, and a per-floor style always wins.")
            .define("dungeonDefaultLayoutStyle", "VARIED");

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    // live copies, refreshed on (re)load below.
    public static boolean requireOpToEdit = true;
    public static int maxHighlightRadius = 128;
    public static boolean dungeonPvp = false;
    public static boolean dungeonKiBlockDestruction = false;
    public static boolean dungeonBlockEditing = false;
    public static int dungeonTimeLimitSeconds = 1800;
    public static int dungeonCooldownSeconds = 600;
    public static int dungeonFloorCount = 5;
    public static int dungeonFloorSize = 250;
    public static int dungeonFloorDepth = 96;
    public static int dungeonRoomBlocksPerTick = 40000;
    public static String dungeonDefaultLayoutStyle = "SPINE_AND_RIBS";

    private Config() {
    }

    // gen enum for the configured default style, or the labyrinth if the string is bad.
    public static net.shurui.dev.shuruis_dmz_dungeons.dungeon.gen.LayoutStyle defaultLayoutStyle() {
        return net.shurui.dev.shuruis_dmz_dungeons.dungeon.gen.LayoutStyle.byName(
                dungeonDefaultLayoutStyle,
                net.shurui.dev.shuruis_dmz_dungeons.dungeon.gen.LayoutStyle.SPINE_AND_RIBS);
    }

    // true when the default is VARIED (each floor rolls its own archetype).
    public static boolean layoutStyleVaries() {
        return "VARIED".equalsIgnoreCase(dungeonDefaultLayoutStyle);
    }

    // archetype for a floor with no style of its own. With VARIED it is rolled from the floor's seed, stable
    // per floor for ever.
    public static net.shurui.dev.shuruis_dmz_dungeons.dungeon.gen.LayoutStyle defaultLayoutStyle(long floorSeed) {
        if (!layoutStyleVaries()) {
            return defaultLayoutStyle();
        }
        net.shurui.dev.shuruis_dmz_dungeons.dungeon.gen.LayoutStyle[] all =
                net.shurui.dev.shuruis_dmz_dungeons.dungeon.gen.LayoutStyle.values();
        return all[Math.floorMod((int) (floorSeed ^ (floorSeed >>> 32)), all.length)];
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        // Fires for EVERY spec this mod registers, not just ours. Reading a ConfigValue before its owning spec
        // is loaded throws, so guard by spec. One spec today, but a second added later must not crash.
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        requireOpToEdit = REQUIRE_OP_TO_EDIT.get();
        maxHighlightRadius = MAX_HIGHLIGHT_RADIUS.get();
        dungeonPvp = DUNGEON_PVP.get();
        dungeonKiBlockDestruction = DUNGEON_KI_BLOCK_DESTRUCTION.get();
        dungeonBlockEditing = DUNGEON_BLOCK_EDITING.get();
        dungeonTimeLimitSeconds = DUNGEON_TIME_LIMIT_SECONDS.get();
        dungeonCooldownSeconds = DUNGEON_COOLDOWN_SECONDS.get();
        dungeonFloorCount = DUNGEON_FLOOR_COUNT.get();
        dungeonFloorSize = DUNGEON_FLOOR_SIZE.get();
        dungeonFloorDepth = DUNGEON_FLOOR_DEPTH.get();
        dungeonRoomBlocksPerTick = DUNGEON_ROOM_BLOCKS_PER_TICK.get();
        dungeonDefaultLayoutStyle = DUNGEON_DEFAULT_LAYOUT_STYLE.get();
    }

    // admin gate: op (perm 2) or creative
    public static boolean canEdit(net.minecraft.world.entity.player.Player player) {
        if (!requireOpToEdit) {
            return true;
        }
        return player.hasPermissions(2) || player.isCreative();
    }
}
