package net.shurui.shuruisutilities.regions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full WorldGuard-style flag catalogue this module understands. Each flag has an id (the WorldGuard id
 * where one exists, so admins familiar with WorldGuard feel at home), a display label, a category and a
 * {@link Type}.
 *
 * <p>{@link Type#STATE} flags are tri-state ({@code allow} / {@code deny} / unset) and are what the in-game GUI
 * cycles. The remaining types hold a value (a message, a number, a game-mode, …) and are edited through
 * {@code /serverclaim flag <region> <flag> <value>}. Everything is stored on the {@link Region} as plain
 * strings so it serializes cleanly.
 *
 * <p>Coverage note: the state flags with a matching Forge event are actively enforced by
 * {@link RegionEventHandler} / {@link RegionResidencyHandler}; a handful of purely world-generation flags
 * (fluid flow, ice/snow, leaf-decay, …) have no Forge hook and are catalogued for parity / future use but not
 * yet enforced. The DragonMineZ flags ({@code ki-griefing}, {@code dmz-gravity}, {@code dmz-heal}) are routed
 * through DMZ's own WorldGuard detection by {@link RegionDmzHook}.
 */
public final class RegionFlag
{
    private RegionFlag() {}

    public enum Type { STATE, MESSAGE, DECIMAL, INTEGER, WORD, ENTITY_SET, GAMEMODE, WEATHER, TIME, LOCATION }

    public record Flag(String id, String label, String category, Type type)
    {
        public boolean isState() { return type == Type.STATE; }
    }

    public static final String BUILD = "build";
    public static final String BLOCK_BREAK = "block-break";
    public static final String BLOCK_PLACE = "block-place";
    public static final String INTERACT = "interact";
    public static final String USE = "use";
    public static final String CHEST_ACCESS = "chest-access";
    public static final String DAMAGE_ANIMALS = "damage-animals";
    public static final String RIDE = "ride";
    public static final String VEHICLE_PLACE = "vehicle-place";
    public static final String VEHICLE_DESTROY = "vehicle-destroy";
    public static final String LIGHTER = "lighter";
    public static final String SLEEP = "sleep";
    public static final String RESPAWN_ANCHORS = "respawn-anchors";
    public static final String ITEM_PICKUP = "item-pickup";
    public static final String ITEM_DROP = "item-drop";
    public static final String BLOCK_TRAMPLING = "block-trampling";
    public static final String ENTITY_PAINTING_DESTROY = "entity-painting-destroy";
    public static final String ENTITY_ITEM_FRAME_DESTROY = "entity-item-frame-destroy";

    public static final String PVP = "pvp";
    public static final String MOB_DAMAGE = "mob-damage";
    public static final String FALL_DAMAGE = "fall-damage";
    public static final String INVINCIBLE = "invincible";
    public static final String FIREWORK_DAMAGE = "firework-damage";
    public static final String POTION_SPLASH = "potion-splash";
    public static final String EXP_DROPS = "exp-drops";

    public static final String MOB_SPAWNING = "mob-spawning";
    /** SU extra: only naturally-spawning mobs (NATURAL / CHUNK_GENERATION / STRUCTURE / …). */
    public static final String MOB_SPAWN_NATURAL = "mob-spawn-natural";
    /** SU extra: only player/command-spawned mobs (spawner / egg / summon / dispenser / …). */
    public static final String MOB_SPAWN_UNNATURAL = "mob-spawn-unnatural";
    /** SU extra: whether NPC-region ambient spawns may land inside this protection region. */
    public static final String NPCREGION_SPAWNS = "npcregion-spawns";
    /**
     * SU extra: ALL mob block-griefing in one flag, whatever the mob.
     *
     * <p>The per-mob flags below each cover one creature, so protecting a build meant knowing every mob that can
     * take a block and setting each one. This is the catch-all: it gates the same
     * {@code EntityMobGriefingEvent} every one of them asks, so it also covers the ones with no flag of their own
     * (zombies opening doors, silverfish, sheep eating grass, villagers farming) and any modded mob that asks the
     * vanilla question. Denying a specific flag still works and still wins; this only adds a way to say "none of it".
     */
    public static final String MOB_GRIEFING = "mob-griefing";
    public static final String ENDERMAN_GRIEF = "enderman-grief";
    public static final String SNOWMAN_TRAILS = "snowman-trails";
    public static final String RAVAGER_GRIEF = "ravager-grief";
    public static final String WITHER_DAMAGE = "wither-damage";

    public static final String CREEPER_EXPLOSION = "creeper-explosion";
    public static final String OTHER_EXPLOSION = "other-explosion";
    public static final String TNT = "tnt";
    public static final String GHAST_FIREBALL = "ghast-fireball";
    public static final String ENDERDRAGON_BLOCK_DAMAGE = "enderdragon-block-damage";
    public static final String ENDERPEARL = "enderpearl";
    public static final String CHORUS_FRUIT_TELEPORT = "chorus-fruit-teleport";
    public static final String PISTONS = "pistons";
    public static final String LIGHTNING = "lightning";

    public static final String FIRE_SPREAD = "fire-spread";
    public static final String LAVA_FIRE = "lava-fire";
    public static final String CROP_GROWTH = "crop-growth";

    public static final String WATER_FLOW = "water-flow";
    public static final String LAVA_FLOW = "lava-flow";
    /** Deny to stop sand and gravel falling here, so a built map keeps its shape through block updates. */
    public static final String SAND_FALL = "sand-fall";
    /**
     * Allow to have combat damage inside this region repair itself, without the {@code terrainRegen} gamerule being on
     * for the whole world. An arena can rebuild between fights while the world outside keeps whatever the fight did to
     * it, which is what a region flag is for. Deny to force it OFF here even when the gamerule is on world wide.
     */
    public static final String TERRAIN_REGEN = "terrain-regen";
    public static final String SNOW_FALL = "snow-fall";
    public static final String SNOW_MELT = "snow-melt";
    public static final String ICE_FORM = "ice-form";
    public static final String ICE_MELT = "ice-melt";
    public static final String FROSTED_ICE_FORM = "frosted-ice-form";
    public static final String FROSTED_ICE_MELT = "frosted-ice-melt";
    public static final String MUSHROOM_GROWTH = "mushroom-growth";
    public static final String LEAF_DECAY = "leaf-decay";
    public static final String GRASS_GROWTH = "grass-growth";
    public static final String MYCELIUM_SPREAD = "mycelium-spread";
    public static final String VINE_GROWTH = "vine-growth";
    public static final String SCULK_GROWTH = "sculk-growth";
    public static final String SOIL_DRY = "soil-dry";
    public static final String CORAL_FADE = "coral-fade";

    public static final String ENTRY = "entry";
    public static final String EXIT = "exit";
    public static final String SEND_CHAT = "send-chat";
    public static final String RECEIVE_CHAT = "receive-chat";
    public static final String NOTIFY_ENTER = "notify-enter";
    public static final String NOTIFY_LEAVE = "notify-leave";

    /** Whether DragonMineZ ki blasts may grief (destroy blocks). Routed through DMZ via {@link RegionDmzHook}. */
    public static final String KI_GRIEFING = "ki-griefing";
    /** Whether DragonMineZ health regeneration (ki/stat healing) is allowed. */
    public static final String DMZ_HEAL = "dmz-heal";
    /**
     * Whether a player may START a DragonMineZ quest (or re-summon its mob) while standing here. Enforced server
     * side by {@code MixinDmzQuestStartRegion} at DMZ's {@code QuestService} start funnel, before the quest is
     * accepted or any quest mob is spawned, so the practical abuse (starting a saga at spawn so its mob appears at
     * spawn) is closed. The player's position at the moment of the request is what is checked. Progressing,
     * turning in, completing, claiming, abandoning and tracking are NOT affected, and quests already active keep
     * ticking. Default unset = allow, so no region changes behaviour until an admin denies it. Staff with region
     * bypass are exempt, and DMZ's own admin quest commands are not routed through this funnel.
     */
    public static final String QUEST_START = "quest-start";

    public static final String GREETING = "greeting";
    public static final String FAREWELL = "farewell";
    public static final String GREETING_TITLE = "greeting-title";
    public static final String FAREWELL_TITLE = "farewell-title";
    public static final String ENTRY_DENY_MESSAGE = "entry-deny-message";
    public static final String EXIT_DENY_MESSAGE = "exit-deny-message";
    public static final String GAME_MODE = "game-mode";
    public static final String TIME_LOCK = "time-lock";
    public static final String WEATHER_LOCK = "weather-lock";
    public static final String HEAL_AMOUNT = "heal-amount";
    public static final String HEAL_DELAY = "heal-delay";
    public static final String HEAL_MIN_HEALTH = "heal-min-health";
    public static final String HEAL_MAX_HEALTH = "heal-max-health";
    public static final String FEED_AMOUNT = "feed-amount";
    public static final String FEED_DELAY = "feed-delay";
    public static final String FEED_MIN_HUNGER = "feed-min-hunger";
    public static final String FEED_MAX_HUNGER = "feed-max-hunger";
    public static final String BLOCKED_CMDS = "blocked-cmds";
    public static final String ALLOWED_CMDS = "allowed-cmds";
    public static final String DENY_SPAWN = "deny-spawn";
    /** DragonMineZ gravity multiplier override for the region (DoubleFlag {@code dmz-gravity}). */
    public static final String DMZ_GRAVITY = "dmz-gravity";

    private static final Map<String, Flag> BY_ID = new LinkedHashMap<>();

    private static Flag reg(String id, String label, String category, Type type)
    {
        Flag f = new Flag(id, label, category, type);
        BY_ID.put(id, f);
        return f;
    }

    static
    {
        String c;
        c = "Build";
        reg(BUILD, "Build (break/place/interact)", c, Type.STATE);
        reg(BLOCK_BREAK, "Block break", c, Type.STATE);
        reg(BLOCK_PLACE, "Block place", c, Type.STATE);
        reg(INTERACT, "Interact (doors/buttons)", c, Type.STATE);
        reg(USE, "Use (functional blocks)", c, Type.STATE);
        reg(CHEST_ACCESS, "Chest access", c, Type.STATE);
        reg(DAMAGE_ANIMALS, "Damage animals", c, Type.STATE);
        reg(RIDE, "Ride entities", c, Type.STATE);
        reg(VEHICLE_PLACE, "Vehicle place", c, Type.STATE);
        reg(VEHICLE_DESTROY, "Vehicle destroy", c, Type.STATE);
        reg(LIGHTER, "Flint & steel", c, Type.STATE);
        reg(SLEEP, "Sleep", c, Type.STATE);
        reg(RESPAWN_ANCHORS, "Respawn anchors", c, Type.STATE);
        reg(ITEM_PICKUP, "Item pickup", c, Type.STATE);
        reg(ITEM_DROP, "Item drop", c, Type.STATE);
        reg(BLOCK_TRAMPLING, "Block trampling", c, Type.STATE);
        reg(ENTITY_PAINTING_DESTROY, "Painting destroy", c, Type.STATE);
        reg(ENTITY_ITEM_FRAME_DESTROY, "Item frame destroy", c, Type.STATE);

        c = "Combat";
        reg(PVP, "PvP", c, Type.STATE);
        reg(MOB_DAMAGE, "Mob damage to players", c, Type.STATE);
        reg(FALL_DAMAGE, "Fall damage", c, Type.STATE);
        reg(INVINCIBLE, "Invincible", c, Type.STATE);
        reg(FIREWORK_DAMAGE, "Firework damage", c, Type.STATE);
        reg(POTION_SPLASH, "Potion splash", c, Type.STATE);
        reg(EXP_DROPS, "Experience drops", c, Type.STATE);

        c = "Mobs";
        reg(MOB_SPAWNING, "Mob spawning (all)", c, Type.STATE);
        reg(MOB_SPAWN_NATURAL, "Natural mob spawning", c, Type.STATE);
        reg(MOB_SPAWN_UNNATURAL, "Spawned mobs (egg/cmd/NPC)", c, Type.STATE);
        reg(NPCREGION_SPAWNS, "NPC region spawns", c, Type.STATE);
        reg(MOB_GRIEFING, "Mob griefing (all)", c, Type.STATE);
        reg(ENDERMAN_GRIEF, "Enderman grief", c, Type.STATE);
        reg(SNOWMAN_TRAILS, "Snowman trails", c, Type.STATE);
        reg(RAVAGER_GRIEF, "Ravager grief", c, Type.STATE);
        reg(WITHER_DAMAGE, "Wither block damage", c, Type.STATE);
        reg(DENY_SPAWN, "Deny spawn (entity ids)", c, Type.ENTITY_SET);

        c = "Explosions";
        reg(CREEPER_EXPLOSION, "Creeper explosion", c, Type.STATE);
        reg(OTHER_EXPLOSION, "Other explosion", c, Type.STATE);
        reg(TNT, "TNT", c, Type.STATE);
        reg(GHAST_FIREBALL, "Ghast fireball", c, Type.STATE);
        reg(ENDERDRAGON_BLOCK_DAMAGE, "Ender dragon block damage", c, Type.STATE);
        reg(ENDERPEARL, "Enderpearl", c, Type.STATE);
        reg(CHORUS_FRUIT_TELEPORT, "Chorus fruit teleport", c, Type.STATE);
        reg(PISTONS, "Pistons", c, Type.STATE);
        reg(LIGHTNING, "Lightning strikes", c, Type.STATE);

        c = "World";
        reg(FIRE_SPREAD, "Fire spread", c, Type.STATE);
        reg(LAVA_FIRE, "Lava fire", c, Type.STATE);
        reg(CROP_GROWTH, "Crop growth", c, Type.STATE);
        reg(WATER_FLOW, "Water flow", c, Type.STATE);
        reg(LAVA_FLOW, "Lava flow", c, Type.STATE);
        reg(SAND_FALL, "Sand fall", c, Type.STATE);
        reg(TERRAIN_REGEN, "Terrain regen", c, Type.STATE);
        reg(SNOW_FALL, "Snow fall", c, Type.STATE);
        reg(SNOW_MELT, "Snow melt", c, Type.STATE);
        reg(ICE_FORM, "Ice form", c, Type.STATE);
        reg(ICE_MELT, "Ice melt", c, Type.STATE);
        reg(FROSTED_ICE_FORM, "Frosted ice form", c, Type.STATE);
        reg(FROSTED_ICE_MELT, "Frosted ice melt", c, Type.STATE);
        reg(MUSHROOM_GROWTH, "Mushroom growth", c, Type.STATE);
        reg(LEAF_DECAY, "Leaf decay", c, Type.STATE);
        reg(GRASS_GROWTH, "Grass growth", c, Type.STATE);
        reg(MYCELIUM_SPREAD, "Mycelium spread", c, Type.STATE);
        reg(VINE_GROWTH, "Vine growth", c, Type.STATE);
        reg(SCULK_GROWTH, "Sculk growth", c, Type.STATE);
        reg(SOIL_DRY, "Soil dry", c, Type.STATE);
        reg(CORAL_FADE, "Coral fade", c, Type.STATE);

        c = "Movement";
        reg(ENTRY, "Entry", c, Type.STATE);
        reg(EXIT, "Exit", c, Type.STATE);
        reg(SEND_CHAT, "Send chat", c, Type.STATE);
        reg(RECEIVE_CHAT, "Receive chat", c, Type.STATE);
        reg(NOTIFY_ENTER, "Notify staff on enter", c, Type.STATE);
        reg(NOTIFY_LEAVE, "Notify staff on leave", c, Type.STATE);
        // WorldGuard's "passthrough" is intentionally NOT catalogued: it only affects WorldGuard's implicit
        // member-build protection, which this module doesn't have (all protection is explicit flags), so
        // listing it would offer a flag that can never do anything.

        c = "DragonMineZ";
        reg(KI_GRIEFING, "Ki griefing (block destruction)", c, Type.STATE);
        reg(DMZ_HEAL, "DMZ health regen", c, Type.STATE);
        reg(QUEST_START, "Start DMZ quests", c, Type.STATE);
        reg(DMZ_GRAVITY, "DMZ gravity multiplier", c, Type.DECIMAL);

        c = "Messages";
        reg(GREETING, "Greeting message", c, Type.MESSAGE);
        reg(FAREWELL, "Farewell message", c, Type.MESSAGE);
        reg(GREETING_TITLE, "Greeting title", c, Type.MESSAGE);
        reg(FAREWELL_TITLE, "Farewell title", c, Type.MESSAGE);
        reg(ENTRY_DENY_MESSAGE, "Entry-denied message", c, Type.MESSAGE);
        reg(EXIT_DENY_MESSAGE, "Exit-denied message", c, Type.MESSAGE);

        c = "Session";
        reg(GAME_MODE, "Game mode", c, Type.GAMEMODE);
        reg(TIME_LOCK, "Time lock", c, Type.TIME);
        reg(WEATHER_LOCK, "Weather lock", c, Type.WEATHER);
        reg(HEAL_AMOUNT, "Heal amount / tick", c, Type.DECIMAL);
        reg(HEAL_DELAY, "Heal delay (ticks)", c, Type.INTEGER);
        reg(HEAL_MIN_HEALTH, "Heal min health", c, Type.DECIMAL);
        reg(HEAL_MAX_HEALTH, "Heal max health", c, Type.DECIMAL);
        reg(FEED_AMOUNT, "Feed amount / tick", c, Type.INTEGER);
        reg(FEED_DELAY, "Feed delay (ticks)", c, Type.INTEGER);
        reg(FEED_MIN_HUNGER, "Feed min hunger", c, Type.INTEGER);
        reg(FEED_MAX_HUNGER, "Feed max hunger", c, Type.INTEGER);
        reg(BLOCKED_CMDS, "Blocked commands", c, Type.WORD);
        reg(ALLOWED_CMDS, "Allowed commands", c, Type.WORD);
    }

    /** Every flag, in registration (category) order. */
    public static List<Flag> all()
    {
        return new ArrayList<>(BY_ID.values());
    }

    public static Flag get(String id)
    {
        return id == null ? null : BY_ID.get(id);
    }

    public static boolean exists(String id)
    {
        return BY_ID.containsKey(id);
    }

    public static boolean isState(String id)
    {
        Flag f = get(id);
        return f != null && f.isState();
    }

    public static String label(String id)
    {
        Flag f = get(id);
        return f == null ? id : f.label();
    }

    public static Type type(String id)
    {
        Flag f = get(id);
        return f == null ? null : f.type();
    }

    /**
     * {@code {id, label}} rows for every STATE flag, in category order. This is what the in-game GUI cycles and
     * what {@code /serverclaim info} lists; kept as a {@code String[][]} for the generic GUI transport.
     */
    public static final String[][] ALL;

    static
    {
        List<String[]> state = new ArrayList<>();
        for (Flag f : BY_ID.values())
            if (f.isState())
                state.add(new String[] { f.id(), f.label() });
        ALL = state.toArray(new String[0][]);
    }
}
