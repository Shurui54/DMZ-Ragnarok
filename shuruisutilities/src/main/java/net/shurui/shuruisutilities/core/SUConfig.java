package net.shurui.shuruisutilities.core;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Set;

import net.shurui.shuruisutilities.core.config.ConfigData;
import net.shurui.shuruisutilities.core.config.ConfigLoaderBase;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.Builder;

public class SUConfig extends ConfigLoaderBase
{
    private static ForgeConfigSpec CORE_CONFIG;
    public static final ConfigData data = new ConfigData("main", CORE_CONFIG, new ForgeConfigSpec.Builder());

    public static final String CONFIG_MAIN_CORE = "Core";
    public static final String CONFIG_MAIN_MISC = "Misc";

    public static String modlistLocation = "modlist.txt";

    public static float majoritySleep;

    public static boolean checkSpacesInNames;

    // true (default) = swallow the client-side CustomNPCs "you logged into a dynamic dimension using a
    // non-vanilla dimensionType" chat warning while the local player is inside one of our space dimensions.
    // Our space/planet_surface dimensions genuinely need a custom dimension_type, so that warning is expected
    // noise there and the symptom it warns about does not actually occur. false = let the warning through.
    public static boolean muteCnpcDimensionWarning;

    // true = hostile spawns on regardless of global difficulty (not zeroed on peaceful). per-region deny flags
    // + per-dimension difficulty stay the fine-grained controls. false = follow global difficulty.
    public static boolean forceHostileSpawns;
    /** Where /buy sends players. Blank means this server has no store and the command is not registered. */
    public static String storeUrl = "";

    // How far from a region NPC's battle power a player may be, EITHER WAY, and still be auto-acquired as a
    // target, as a fraction (0.10 = a 10 percent band on both sides). A region NPC only UNPROMPTED-targets a
    // player whose BP is between (1 - this) and (1 + this) of its own: a weaker questing player is ignored, and
    // so is one who far outclasses it. Retaliation (a player who hits the NPC) is never gated by this. Read
    // server-side by NpcRegionBehavior. 0 means only an exact match is engaged; 1 disables the gate.
    public static double npcRegionAutoAggroBpFraction;

    public static int prestigeMax;
    // bonus TP gain % per prestige level (additive; 10 => +30% at prestige 3)
    public static int prestigeTpBonusPerLevel;
    // bonus to DMZ per-stat max cap % per prestige level (additive; 25 => +100% at prestige 4). raises both the
    // hard clamp and the purchase/level budget.
    public static int prestigeCapBonusPerLevel;

    public static boolean enableCommandAliases;

    public static boolean overwriteConflictingCommands;

    // master switch for the wish-tracking subsystem
    public static boolean wishTrackingEnabled;
    // server-wide number of dragon ball uses before the swap
    public static int wishTrackingThreshold;
    // extra ticks added on top of the storm duration that the swap sequence derives from its own audio timeline;
    // this can only lengthen the storm, never shorten it below the point where the voice lines finish
    public static int corruptedStormExtraTicks;
    // how long the swap-sequence prop lingers after the smite, in ticks
    public static int corruptedPropLingerTicks;
    // how long a spawned corrupted-set boss remains before it despawns if never defeated, in ticks
    public static int corruptedDragonLifetimeTicks;

    // Minimum DMZ level a saiyan must reach before the "Knowledge of Super Saiyan God" wish is offered on Shenron's
    // list (and before the server will grant it). Read server-side by WishRitualManager, both for the per-player
    // visibility filter and for the grant-time gate, so lowering or raising it never depends on a trusted client.
    // Default 5000. A brand-new value is added to an existing config file with this default the next time the spec
    // loads, so an operator on a live server sees the key appear and edits it there.
    public static int ssgWishMinLevel;

    // How many OTHER saiyans (the chargers) must charge their ki in the ring around the centre saiyan for the Super
    // Saiyan God charge ritual to complete: the ritual needs this many chargers plus the centre, so a value of 5 means
    // six saiyans total. Read server-side by SsgRitualManager, both for the "is there even a ring's worth of chargers
    // here" early-out and for the per-centre ring-size check, so lowering or raising it never depends on a trusted
    // client. Default 5. A brand-new value is added to an existing config file with this default the next time the spec
    // loads, so an operator on a live server sees the key appear and edits it there.
    public static int ssgRitualRequiredChargers;

    // Zeni charged, server-side, to awaken one dormant rune at the rune bench. Read by RuneBenchMenu.doAwaken; the
    // charge is taken before the dormant rune is consumed, so a player who cannot afford it loses neither the rune
    // nor any Zeni. Zero means no charge, which is the old free awakening. Default 5000. A brand-new value is added
    // to an existing config file with this default the next time the spec loads, so an operator on a live server
    // sees the key appear and edits it there.
    public static int runeAwakenZeniCost;

    // Freeze overworld fluid spread, and overworld sand and gravel falling. Both exist for ONE reason: Shurui's live
    // overworld is a hand-built imported map, and a single misplaced water source or an unsupported sand column
    // would erode a build nobody can regenerate. That is a property of THAT map, not of the mod, so both default to
    // false and vanilla physics is what anyone else gets. They were previously hardcoded true with no way to turn
    // them off, which froze water, lava, sand and gravel in every singleplayer world and on every third-party
    // server. Read by WorldPhysicsModule via bake() at the bottom of this file; nothing else may set them.
    public static boolean holdOverworldFluids;
    public static boolean holdOverworldFallingBlocks;

    // Dimension ids whose hand-built terrain is protected from ordinary players: they may not break or place blocks,
    // harm non-hostile NPCs, or strip armor stands / item frames there. Hostile and saga quest mobs stay fightable and
    // PvP is untouched, so the dimension still plays. For fixed, hand-authored bodies (Planet Vegeta, dungeon builds)
    // that have no generated-planet id and so cannot be self-claimed. Parsed from a comma-separated string; baked into
    // an immutable Set read by BuildDimensionProtection. Staff bypass with the su.protection.builddim.bypass node.
    public static java.util.Set<String> protectedBuildDimensions = java.util.Collections.emptySet();

    // Half-side (in blocks) of the square DMZ scatters overworld dragon balls into, measured from world spawn.
    // DMZ's scatter picks x/z in [spawn - range, spawn + range), so this is effectively the radius of the area
    // balls may land in. Our overworld is an imported custom map roughly x/z -3072..3071, so the default of 3000
    // keeps every scattered ball (earth plus our own overworld sets) inside the map with a small margin instead of
    // flinging some out into the ordinary generated terrain beyond it, where they are effectively lost. Exposed as
    // config because the operator may resize the imported map later.
    public static int overworldDragonBallScatterRange;

    // Minimum spacing, in blocks, between two hand-placed Super dragon balls. The Super model is about 2.9 blocks
    // across, so a placement within this many blocks of another Super ball is refused to keep the oversized spheres
    // from visually overlapping. 3 is the smallest value that clears the model; 1 disables the rule.
    public static int superDragonBallMinPlacementDistance;

    // Periodic clearing of dropped item entities ("clearlag"). OFF by default: it deletes players' property, so it
    // is never something a server starts doing because it updated the mod.
    public static boolean itemClearEnabled;
    // seconds between one clear and the next
    public static int itemClearIntervalSeconds;
    // comma-separated seconds-before-the-clear at which a warning is broadcast, e.g. "60,30,10,5"
    public static String itemClearWarnSeconds;
    // broadcast lines; {seconds} and {count} are substituted, and & colour codes are honoured
    public static String itemClearWarnMessage;
    public static String itemClearDoneMessage;
    // comma-separated item ids never cleared, on top of the dragon balls, which are always spared
    public static String itemClearExcludedItems;

    // Opacity of a dragon ball's translucent shell (the Super Dragon Ball planet bodies), 0..1: 1 is fully solid and
    // 0.7 is the 30-percent-transparent glass look the shell ships with, so the stars on the billboard inside read
    // through it. Lower it toward 0 for a clearer view of the inner stars, raise it toward 1 for a more solid ball.
    public static double dragonBallAlpha;

    // How a placed dragon ball is drawn. SPHERE (default) is the translucent glass sphere with its stars on an inner
    // billboard; CUBE is the original faceted model and real texture, made translucent at dragonBallAlpha. Read on the
    // client render thread by the ball renderers. Both styles share dragonBallAlpha for their opacity.
    public static DragonBallRenderStyle dragonBallRenderStyle;

    /**
     * The two ways Shurui's Utilities can draw a placed dragon ball. Lives here (not on the client) so the common
     * config that carries it stays loadable on a dedicated server; only the client renderers read the baked value.
     */
    public enum DragonBallRenderStyle
    {
        // one translucent glass sphere with an inner star billboard (the current shipped look).
        SPHERE,
        // the ball's original faceted model and real texture, drawn translucent with no stacked inner faces.
        CUBE
    }

    // Radius, in blocks, of the approximate area the NORMAL dragon ball radars point at (every set except Super and
    // Black Star: DMZ's Earth and Namek, plus our Cerulean). The server fuzzes each normal ball's position by a
    // deterministic horizontal offset within this radius before it goes out in the radar packet, so those radars
    // lead to a rough spot rather than the exact block, and a modified client cannot recover the true coordinate.
    // The offset is a stable function of the ball's own position, so the dot holds still instead of jittering. 0
    // disables fuzzing (all radars exact). Super and Black Star are always exact regardless of this value.
    public static int radarFuzzRadius;

    // false (default, recommended) = SU world data stored once per install under ShuruisUtilities/, so it
    // doesn't diverge between integrated and dedicated runs. true = legacy per-world storage.
    public static boolean worldScopedData;

    // true = the client replaces Minecraft's vanilla title screen with Shurui's DMZ Ragnarok custom main menu.
    // false (default) = keep the vanilla title screen, so the custom menu is opt-in. Client-only cosmetic; carried
    // in the common config so a dedicated server still loads it without error, and only the client ever reads it.
    public static boolean customMainMenu;

    // true (default) = the client draws SU's custom scouter-style stat HUD (orb player preview + health/ki/stamina/
    // transform bars) and suppresses DragonMineZ's own xenoverse/alternative stat HUD. false = SU draws nothing and
    // leaves DMZ's HUD alone, so players get vanilla DMZ behaviour back. Client-only cosmetic; carried in the common
    // config so a dedicated server still loads it without error, and only the client ever reads it.
    public static boolean customStatHud;

    // Uniform scale factor for the custom scouter HUD (the assembled panel is 216x80 GUI-scaled pixels at 1.0). Raise
    // toward 4 for a larger panel, lower toward 0.25 for a smaller one, to taste for a given GUI scale. Client-only.
    public static double customStatHudScale;

    // true (default) = the client draws SU's quest tracker banner (the commissioned quest_info banner with the tracked
    // quest name on it and the objective lines beneath it) and suppresses DragonMineZ's own tracked-quest HUD. false =
    // SU draws nothing and leaves DMZ's tracker alone. Client-only cosmetic; carried in the common config so a dedicated
    // server still loads it without error, and only the client ever reads it.
    public static boolean customQuestTracker;
    // How many coordinate/info lines Xaero's Minimap draws under the map. Used only to find the BOTTOM of that block so
    // the banner sits just below it (Xaero exposes no count). Default 1 = the vanilla single coordinates line; raise it
    // if more info displays are enabled under the minimap.
    public static int questTrackerCoordLines;
    // Fallback anchor used when Xaero is absent, hidden, or has not drawn this frame: distance in GUI pixels from the
    // screen's RIGHT edge to the banner's right edge.
    public static int questTrackerFallbackRightMargin;
    // Fallback anchor used when Xaero is absent, hidden, or has not drawn this frame: the banner's top Y in GUI pixels,
    // chosen to clear a default-size top-right minimap plus its coordinate line.
    public static int questTrackerFallbackTopY;
    // Fallback panel WIDTH in GUI pixels used when Xaero is absent, hidden, or has not drawn this frame. The banner is
    // scaled uniformly to this width (scale = width / 354, clamped). With Xaero present the minimap's own width is used
    // instead, so this only bites without a fresh capture. Default 160, roughly a vanilla minimap's footprint.
    public static int questTrackerFallbackWidth;

    // true (default) = the streaks SU draws behind dashing and flying players are rendered. false = they are not drawn
    // at all and no positions are sampled for them. Purely a look, and the cheapest thing to turn off on a weak
    // machine, since the streak is many lines per player per frame. Client-only cosmetic.
    public static boolean auraTrails;

    // true (default) = while a player flies fast, SU lays its aura over the screen (and supplies one to lay over for a
    // player who never powered up). false = that laid-over fast-flight aura is not applied, so fast flight shows no
    // constant aura; the flight trail and the player's own powered-up DMZ aura are unaffected either way. Client-only
    // cosmetic; read live per client tick by FastFlightAura.
    public static boolean flightAura;

    // true (default) = SU's suite event announcements (the big on-screen title/subtitle we put up for rituals, the
    // corrupted-ball event, region entry, and similar) are shown to this player. false = they are suppressed for this
    // player only. DragonMineZ's own titles and vanilla titles are never touched. Because SUConfig is a COMMON config
    // (per client installation, not synced by Forge), the client tells the server this preference over a packet, and
    // the server skips only OUR announcements for that player. Client-driven cosmetic preference.
    public static boolean suiteAnnouncements;

    // true (default) = the client runs the black hole gravitational-lensing screen pass in space, warping the starfield
    // around the void. false = the pass is skipped and a black hole still draws its sphere, spherical rim, discs and wrap
    // arcs (a strict upgrade on the old cube-rim look). Client-only cosmetic; auto-disabled at runtime when a shader mod
    // (oculus / iris / optifine) is present because they replace the whole pipeline and grabbing their G-buffer is garbage.
    public static boolean lensingEnabled;

    // Gravity multiplier seeded for the shuruisutilities:planet_vegeta dimension into DragonMineZ's own per-dimension
    // gravity config (GeneralServerConfig.gravity.gravityPerWorld). 10.0 = ten times normal, the Planet Vegeta feel.
    // Seeded once, only if the key is absent, so retuning it here or directly in DMZ's general-server.json is never
    // overwritten on a later boot. DMZ does all the actual gravity work (server tick + client sync); SU only supplies
    // the entry so the planet ships heavy without a hand-edited config file.
    public static double planetVegetaGravity;

    // Patreon account linking (all COMMON so the API key NEVER rides a SERVER config into a client save). The
    // Minecraft server is the only thing that ever talks to the backend Worker; the client only clicks a link.
    // The project's public backend, baked in so a supporter's perks follow them onto EVERY server running this mod,
    // not just the ones the author operates. Safe to ship: it is a plain URL, and the only thing it exposes without
    // a key is a read-only "what tier is this uuid", which the player already advertises as a crown in game.
    // The branded host. The Worker answers on its workers.dev name too, so an older config pointing there keeps
    // working; this is the one baked into new builds because a call to a *.workers.dev subdomain decompiles as a
    // phone-home to an anonymous endpoint, and the same call to the project's own domain does not.
    public static final String DEFAULT_PATREON_BACKEND_URL = "https://api.shuruidev.win";

    // Base URL of the backend that performs the Patreon OAuth (no trailing slash needed). Defaults to
    // DEFAULT_PATREON_BACKEND_URL; blanking it leaves the whole feature cleanly inert.
    public static String patreonBackendUrl;
    // Shared API key the server sends to the backend on every call. NEVER logged, never sent to a client. Empty
    // (default) also leaves the feature inert.
    public static String patreonServerApiKey;
    // How often, in minutes, a logged-in player's entitlement is re-fetched from the backend.
    public static int patreonRefreshMinutes;
    // Grace period, in hours, that a last-known-good tier keeps applying while the backend is unreachable, so a
    // transient outage never strips a paying supporter's rewards. Once this elapses with no successful refresh the
    // tier falls back to none.
    public static int patreonGraceHours;
    // Per-request HTTP timeout, in seconds, for every backend call (connect and read). Keeps a slow backend from
    // ever stalling the worker thread indefinitely; the server main thread is never involved in the wait.
    public static int patreonHttpTimeoutSeconds;

    public static SimpleDateFormat FORMAT_DATE = new SimpleDateFormat("yyyy-MM-dd");

    public static SimpleDateFormat FORMAT_DATE_TIME = new SimpleDateFormat("dd.MM HH:mm");

    public static SimpleDateFormat FORMAT_DATE_TIME_SECONDS = new SimpleDateFormat("dd.MM HH:mm:ss");

    public static SimpleDateFormat FORMAT_TIME = new SimpleDateFormat("HH:mm");

    public static SimpleDateFormat FORMAT_TIME_SECONDS = new SimpleDateFormat("HH:mm:ss");

    public static SimpleDateFormat FORMAT_GSON_COMPAT = new SimpleDateFormat("MMM d, yyyy h:mm:ss aa");

    static ForgeConfigSpec.ConfigValue<String> SUFORMAT_DATE;
    static ForgeConfigSpec.ConfigValue<String> SUFORMAT_DATE_TIME;
    static ForgeConfigSpec.ConfigValue<String> SUFORMAT_DATE_TIME_SECONDS;
    static ForgeConfigSpec.ConfigValue<String> SUFORMAT_TIME;
    static ForgeConfigSpec.ConfigValue<String> SUFORMAT_TIME_SECONDS;
    static ForgeConfigSpec.ConfigValue<String> SUFORMAT_GSON_COMPAT;
    static ForgeConfigSpec.ConfigValue<String> SUmodlistLocation;
    static ForgeConfigSpec.IntValue SUmajoritySleep;
    static ForgeConfigSpec.BooleanValue SUcheckSpacesInNames;
    static ForgeConfigSpec.BooleanValue SUmuteCnpcDimensionWarning;
    static ForgeConfigSpec.BooleanValue SUforceHostileSpawns;
    static ForgeConfigSpec.ConfigValue<String> SUstoreUrl;
    static ForgeConfigSpec.DoubleValue SUnpcRegionAutoAggroBpFraction;
    static ForgeConfigSpec.IntValue SUoverworldDragonBallScatterRange;
    static ForgeConfigSpec.IntValue SUsuperDragonBallMinPlacementDistance;
    static ForgeConfigSpec.BooleanValue SUitemClearEnabled;
    static ForgeConfigSpec.IntValue SUitemClearIntervalSeconds;
    static ForgeConfigSpec.ConfigValue<String> SUitemClearWarnSeconds;
    static ForgeConfigSpec.ConfigValue<String> SUitemClearWarnMessage;
    static ForgeConfigSpec.ConfigValue<String> SUitemClearDoneMessage;
    static ForgeConfigSpec.ConfigValue<String> SUitemClearExcludedItems;
    static ForgeConfigSpec.DoubleValue SUdragonBallAlpha;
    static ForgeConfigSpec.EnumValue<DragonBallRenderStyle> SUdragonBallRenderStyle;
    static ForgeConfigSpec.IntValue SUradarFuzzRadius;
    static ForgeConfigSpec.IntValue SUprestigeMax;
    static ForgeConfigSpec.IntValue SUprestigeTpBonusPerLevel;
    static ForgeConfigSpec.IntValue SUprestigeCapBonusPerLevel;
    static ForgeConfigSpec.BooleanValue SUenableCommandAliases;
    static ForgeConfigSpec.BooleanValue SUoverwriteConflictingCommands;
    static ForgeConfigSpec.BooleanValue SUworldScopedData;
    // Internal revision stamp read and written by the one-time migration runner below. Not mirrored into a
    // public static field on bake: nothing outside the migration machinery ever needs its value.
    static ForgeConfigSpec.IntValue SUconfigRevision;
    static ForgeConfigSpec.BooleanValue SUcustomMainMenu;
    static ForgeConfigSpec.BooleanValue SUcustomStatHud;
    static ForgeConfigSpec.DoubleValue SUcustomStatHudScale;
    static ForgeConfigSpec.BooleanValue SUcustomQuestTracker;
    static ForgeConfigSpec.IntValue SUquestTrackerCoordLines;
    static ForgeConfigSpec.IntValue SUquestTrackerFallbackRightMargin;
    static ForgeConfigSpec.IntValue SUquestTrackerFallbackTopY;
    static ForgeConfigSpec.IntValue SUquestTrackerFallbackWidth;
    static ForgeConfigSpec.BooleanValue SUauraTrails;
    static ForgeConfigSpec.BooleanValue SUflightAura;
    static ForgeConfigSpec.BooleanValue SUsuiteAnnouncements;
    static ForgeConfigSpec.BooleanValue SUlensingEnabled;
    static ForgeConfigSpec.DoubleValue SUplanetVegetaGravity;
    static ForgeConfigSpec.BooleanValue SUwishTrackingEnabled;
    static ForgeConfigSpec.IntValue SUwishTrackingThreshold;
    static ForgeConfigSpec.IntValue SUcorruptedStormExtraTicks;
    static ForgeConfigSpec.IntValue SUcorruptedPropLingerTicks;
    static ForgeConfigSpec.IntValue SUcorruptedDragonLifetimeTicks;
    static ForgeConfigSpec.IntValue SUssgWishMinLevel;
    static ForgeConfigSpec.IntValue SUssgRitualRequiredChargers;
    static ForgeConfigSpec.IntValue SUruneAwakenZeniCost;
    static ForgeConfigSpec.BooleanValue SUholdOverworldFluids;
    static ForgeConfigSpec.BooleanValue SUholdOverworldFallingBlocks;
    static ForgeConfigSpec.ConfigValue<String> SUprotectedBuildDimensions;
    static ForgeConfigSpec.ConfigValue<String> SUpatreonBackendUrl;
    static ForgeConfigSpec.ConfigValue<String> SUpatreonServerApiKey;
    static ForgeConfigSpec.IntValue SUpatreonRefreshMinutes;
    static ForgeConfigSpec.IntValue SUpatreonGraceHours;
    static ForgeConfigSpec.IntValue SUpatreonHttpTimeoutSeconds;

    /** Values that mean "switch supporter perks off on this server", matched case-insensitively. */
    private static final Set<String> PATREON_DISABLED_VALUES = Set.of("off", "none", "disabled", "-");

    /**
     * The backend URL actually used, or empty when an operator has switched the feature off.
     *
     * <p>A BLANK setting means "use the baked-in default", NOT "off". That distinction matters: a config file is
     * written once and then kept, so every server that ever ran a build from before this feature existed already has
     * {@code BackendUrl = ""} on disk. Reading blank as "off" would leave all of them permanently reporting supporter
     * linking as unavailable, no matter how many times they update, which is the opposite of perks working
     * everywhere with no setup. Switching it off is therefore an explicit act: put "off" in the field.
     */
    public static String patreonEffectiveBackendUrl()
    {
        String raw = patreonBackendUrl == null ? "" : patreonBackendUrl.trim();
        if (raw.isEmpty())
            return DEFAULT_PATREON_BACKEND_URL;
        if (PATREON_DISABLED_VALUES.contains(raw.toLowerCase(Locale.ROOT)))
            return "";
        return raw;
    }

    /**
     * True when supporter perks are active on this server, which is everywhere except where an operator explicitly
     * turned them off. Reading a tier and claiming a link need nothing else: no key, no setup.
     */
    public static boolean patreonConfigured()
    {
        return !patreonEffectiveBackendUrl().isEmpty();
    }

    /**
     * True when this server also holds the shared API key. The key is NOT required for perks: it only unlocks the
     * convenience path where the SERVER mints a one-time link code for a player ({@code /patreon link} posting a
     * ready-made clickable link). It is deliberately absent from the shipped jar, because whoever can mint a code
     * chooses which Minecraft account a pledge binds to, and a secret inside a public jar is not a secret. Servers
     * without it use the browser-first claim flow instead, which is safe precisely because the player starts it.
     */
    public static boolean patreonServerKeyed()
    {
        return patreonConfigured() && patreonServerApiKey != null && !patreonServerApiKey.isBlank();
    }

    /* One-time, versioned CLIENT config migration.                                                       */
    /*                                                                                                    */
    /* WHY THIS EXISTS: main.toml is shipped by the launcher with installOnce, so it is written only when */
    /* ABSENT. That is correct and must stay, because this file carries preferences a player has          */
    /* deliberately set, and an always-synced file would reset every one of them on every update. The     */
    /* consequence is that a CHANGED DEFAULT never reaches anyone who has launched the pack even once.     */
    /* This mechanism is the narrow escape hatch: an ordered list of migrations, each with a target       */
    /* revision and a single narrow action, applied to an EXISTING file when its stored stamp is behind,   */
    /* then the stamp is written forward. Anything a migration does not name is left exactly as the player */
    /* has it.                                                                                             */

    // Bump this by one for each new migration added to MIGRATIONS below.
    private static final int CURRENT_CONFIG_REVISION = 1;

    // True when main.toml already existed on disk when the mod started, BEFORE Forge could open (and
    // create) it. This is the only reliable way to tell an existing player's file, which a migration may
    // move forward, from a brand-new standalone install, which must keep the code defaults (CustomMainMenu
    // false). Set once from the constructor via noteMainConfigPreexisted, well before the file is read.
    private static boolean mainConfigPreexisted;

    // Guards the runner to once per launch, whatever calls bake.
    private static boolean migrationApplied;

    /** Called from the mod constructor with whether main.toml was present before Forge touched it. */
    public static void noteMainConfigPreexisted(boolean existed)
    {
        mainConfigPreexisted = existed;
    }

    /** A single narrow, idempotent change applied to move a config file to {@link ConfigMigration#targetRevision}. */
    @FunctionalInterface
    private interface MigrationAction
    {
        void apply();
    }

    /** A migration: the revision it brings a file up to, a human reason, and the narrow action that does it. */
    private record ConfigMigration(int targetRevision, String reason, MigrationAction action)
    {
    }

    // The migration list, in ascending targetRevision order. EACH ACTION MUST BE IDEMPOTENT: it may re-run on a
    // resumed launch if a previous run wrote its value but crashed before the stamp advanced, and it must be a
    // no-op when the value is already what it wants (a launcher install already ships CustomMainMenu true).
    private static final java.util.List<ConfigMigration> MIGRATIONS = java.util.List.of(
            // Revision 1: turn the custom main menu on for existing players. The shipped default became true, but
            // installOnce means an existing file kept the old false forever. Only this one key is touched; a player
            // who later turns it back off stays off, because by then the stamp is 1 and this migration never runs
            // again.
            new ConfigMigration(1, "enable CustomMainMenu for files that predate the true default",
                    () -> SUcustomMainMenu.set(true)));

    /**
     * Applies any pending one-time migrations to main.toml, then stamps the file forward. Runs once per launch,
     * from {@link #bakeConfig(boolean)} on the initial (non-reload) bake, which happens in FMLCommonSetupEvent,
     * long before the client opens the title screen. So a value a migration changes is baked at its migrated
     * value on THIS launch and is visible the same launch (the custom menu is swapped in on ScreenEvent.Opening).
     *
     * <p>We never open, truncate or rename the file ourselves. Every change here is a ConfigValue.set(), which
     * hands the new value to Forge's own autosave writer (the exact path every live setter in this class already
     * uses). A failed write therefore surfaces as an exception with the previous file left on disk by us, rather
     * than a truncated one we produced.
     */
    private static void runConfigMigrations()
    {
        if (migrationApplied)
            return;
        migrationApplied = true;

        // A brand-new file (nothing on disk when the mod started) was just written from the code defaults, which
        // ARE the current shipped defaults, so it needs no migration. We only STAMP it to the current revision so
        // that on its SECOND launch (when the file now exists) it is not mistaken for an old file and migrated.
        // Without this, a fresh standalone install would have CustomMainMenu forced on at its next start, defeating
        // the deliberately false default that standalone installs are meant to keep.
        if (!mainConfigPreexisted)
        {
            if (SUconfigRevision.get() != CURRENT_CONFIG_REVISION)
                SUconfigRevision.set(CURRENT_CONFIG_REVISION);
            return;
        }

        int stored = SUconfigRevision.get();
        if (stored >= CURRENT_CONFIG_REVISION)
            return; // already current: nothing to apply, and crucially nothing to re-force after an opt-out

        // Apply every migration the file is behind on, in order. Actions run before the stamp advances so that if a
        // crash lands between an action's autosave and the stamp's, the next launch simply re-runs the (idempotent)
        // action and retries the stamp.
        for (ConfigMigration migration : MIGRATIONS)
        {
            if (migration.targetRevision() > stored)
                migration.action().apply();
        }

        // Stamp forward LAST and unconditionally, even when every action above was a no-op. The stamp is the sole
        // guarantee a migration never runs twice, so it must be written whenever we were behind.
        SUconfigRevision.set(CURRENT_CONFIG_REVISION);
    }

    @Override
    public void load(Builder BUILDER, boolean isReload)
    {
        BUILDER.comment("Configure ShuruisUtilities Core.").push(CONFIG_MAIN_CORE);
        SUconfigRevision = BUILDER.comment(
                "Internal revision stamp for one-time config migrations. DO NOT EDIT BY HAND. It records which",
                "automatic migrations have already been applied to this file, so a changed default can reach an",
                "existing file exactly once without ever re-applying after you deliberately set something back.",
                "0 marks a file that predates the migration system.")
                .defineInRange("configRevision", 0, 0, Integer.MAX_VALUE);
        SUFORMAT_DATE = BUILDER.comment("Date-only format").define("format_date", "yyyy-MM-dd");
        SUFORMAT_DATE_TIME = BUILDER.comment("Date and time format").define("format_date_time", "dd.MM HH:mm");
        SUFORMAT_DATE_TIME_SECONDS = BUILDER.comment("Date and time format with seconds")
                .define("format_date_time_seconds", "dd.MM HH:mm:ss");
        SUFORMAT_TIME = BUILDER.comment("Time-only format").define("format_time", "HH:mm");
        SUFORMAT_TIME_SECONDS = BUILDER.comment("Time-only format with seconds").define("format_time_seconds",
                "HH:mm:ss");
        SUFORMAT_GSON_COMPAT = BUILDER
                .comment("Extra Time format to Load GSON data from a different locale and convert it!")
                .define("format_gson_compat", "MMM d, yyyy h:mm:ss aa");
        SUmodlistLocation = BUILDER.comment(
                "Specify the file where the modlist will be written to. This path is relative to the ShuruisUtilities folder.")
                .define("modlistLocation", "modlist.txt");
        SUenableCommandAliases = BUILDER.comment("Enable Command Ailases for SU commands").define("enableAilases",
                true);
        SUoverwriteConflictingCommands = BUILDER.comment("Should we overwrite conflicting commands?").define("overwriteConflicts",
                true);
        SUworldScopedData = BUILDER.comment(
                "false (default, recommended for servers): SU world data (permissions, warps, kits, guilds, etc.) is stored once "
                        + "per server installation under the stable ShuruisUtilities directory (ShuruisUtilities/SUData), so it no "
                        + "longer diverges between client/integrated and dedicated runs. On first start with an existing legacy "
                        + "world folder, SU copies (never moves/deletes) that data over once.",
                "true: legacy behaviour - SU world data is stored per-world under the active world folder.")
                .define("worldScopedData", false);
        SUcustomMainMenu = BUILDER.comment(
                "When true, the client replaces Minecraft's vanilla title screen with Shurui's DMZ Ragnarok custom",
                "main menu. Defaults to false, so the vanilla title screen is what players see unless they opt in.",
                "This is a client-only cosmetic preference; it is skipped automatically when mods failed to load so",
                "Forge's loading-issues screen stays reachable, and it is ignored entirely on a dedicated server.")
                .define("CustomMainMenu", false);
        SUcustomStatHud = BUILDER.comment(
                "When true (default), the client draws Shurui's Utilities custom scouter-style stat HUD (an orb with a",
                "live player preview plus health, ki, stamina and transformation bars) in the top-left corner, and",
                "suppresses DragonMineZ's own stat HUD so only SU's shows. Set to false to draw nothing and restore",
                "DragonMineZ's default HUD. This is a client-only cosmetic preference; it is ignored on a dedicated server.")
                .define("CustomStatHud", true);
        SUcustomStatHudScale = BUILDER.comment(
                "Uniform scale factor for the custom scouter HUD. The assembled panel is 216x80 GUI-scaled pixels at",
                "1.0; raise this toward 4 for a bigger panel or lower it toward 0.25 for a smaller one, to suit the",
                "chosen GUI scale. Client-only; only read when CustomStatHud is true.")
                .defineInRange("CustomStatHudScale", 1.0D, 0.25D, 4.0D);
        SUcustomQuestTracker = BUILDER.comment(
                "When true (default), the client draws Shurui's Utilities quest tracker banner (the commissioned",
                "quest_info banner carrying the tracked quest name, with the objective lines beneath it) and",
                "suppresses DragonMineZ's own tracked-quest HUD so only SU's shows. Set to false to draw nothing and",
                "restore DragonMineZ's default tracker. Client-only cosmetic; ignored on a dedicated server.")
                .define("CustomQuestTracker", true);
        SUquestTrackerCoordLines = BUILDER.comment(
                "How many coordinate/info lines Xaero's Minimap draws under the map. The quest tracker banner is",
                "anchored just below the bottom of that block; Xaero exposes no line count, so this supplies it.",
                "Default 1 is the vanilla single coordinates line. Raise it if more info displays are enabled under",
                "the minimap. Only used while Xaero is actually drawing; ignored for the fallback anchor.")
                .defineInRange("QuestTrackerXaeroCoordLines", 1, 0, 32);
        SUquestTrackerFallbackRightMargin = BUILDER.comment(
                "Fallback anchor when Xaero's Minimap is absent, hidden, or has not drawn this frame: distance in GUI",
                "pixels from the screen's right edge to the banner's right edge. Default 5.")
                .defineInRange("QuestTrackerFallbackRightMargin", 5, 0, 4096);
        SUquestTrackerFallbackTopY = BUILDER.comment(
                "Fallback anchor when Xaero's Minimap is absent, hidden, or has not drawn this frame: the banner's top",
                "Y in GUI pixels, chosen to clear a default-size top-right minimap plus its coordinate line. Default 120.")
                .defineInRange("QuestTrackerFallbackTopY", 120, 0, 4096);
        SUquestTrackerFallbackWidth = BUILDER.comment(
                "Fallback panel WIDTH in GUI pixels when Xaero's Minimap is absent, hidden, or has not drawn this frame.",
                "The banner is scaled uniformly to this width; with Xaero present the minimap's own width is used and",
                "this is ignored. Default 160, roughly a vanilla minimap's footprint.")
                .defineInRange("QuestTrackerFallbackWidth", 160, 32, 4096);
        SUauraTrails = BUILDER.comment(
                "When true (default), the client draws the aura streak that trails behind a player who is dashing or",
                "flying. Set to false to draw no trails at all: the aura itself is unaffected, only the streak left",
                "behind it. This is a client-only cosmetic preference and is ignored on a dedicated server. It is also",
                "the cheapest thing to turn off on a weak machine, since a trail is many lines per player per frame.")
                .define("AuraTrails", true);
        SUflightAura = BUILDER.comment(
                "When true (default), the client lays SU's aura over the screen while a player is flying fast, and",
                "supplies an aura to lay over for a player who never powered up. Set to false to show no constant aura",
                "during fast flight: the flight trail is unaffected, and so is the player's own powered-up DragonMineZ",
                "aura, which SU never touches. This is a client-only cosmetic preference and is ignored on a dedicated",
                "server.")
                .define("FlightAura", true);
        SUsuiteAnnouncements = BUILDER.comment(
                "When true (default), SU's suite event announcements (the big on-screen title/subtitle we put up for",
                "rituals, the corrupted-ball event, region entry, and similar) are shown. Set to false to suppress just",
                "OUR on-screen announcements: DragonMineZ's own titles and vanilla titles are never affected, and the",
                "chat lines that accompany these events are not touched either. The client tells the server this",
                "preference over a packet, so a dedicated server skips only our announcements for this player.")
                .define("SuiteAnnouncements", true);
        SUlensingEnabled = BUILDER.comment(
                "When true (default), the client runs the black hole gravitational-lensing screen pass in SU's space",
                "dimension, warping the starfield around the void. Set to false to skip it: the black hole then still",
                "draws its sphere, spherical orange rim, accretion discs and wrap arcs. This is a client-only cosmetic",
                "preference and is ignored on a dedicated server. It is also disabled automatically at runtime when a",
                "shader mod (oculus / iris / optifine) is loaded, or if the pass throws once, because those mods replace",
                "the render pipeline and sampling their framebuffer produces garbage.")
                .define("BlackHoleLensing", true);
        BUILDER = ShuruisUtilities.load(BUILDER, isReload);
        BUILDER.pop();

        BUILDER.push(CONFIG_MAIN_MISC);
        SUmajoritySleep = BUILDER
                .comment("Once this percent of player sleeps, allow the night to pass. Set to 100 to disable.")
                .defineInRange("MajoritySleepThreshold", 50, 0, 100);
        SUcheckSpacesInNames = BUILDER
                .comment("Check if a player's name contains spaces (can gum up some things in SU)")
                .define("CheckSpacesInNames", true);
        SUmuteCnpcDimensionWarning = BUILDER
                .comment("When true (default), the client hides the CustomNPCs chat warning about logging into a",
                        "dynamic dimension with a non-vanilla dimensionType, but ONLY while the local player is inside",
                        "one of SU's space dimensions (shuruisutilities:space / shuruisutilities:planet_surface).",
                        "Those dimensions require a custom dimension_type by design, so the warning is expected noise",
                        "there and the client-side symptom it describes does not actually occur. A genuine multiworld",
                        "misconfiguration in any other dimension still warns the operator. Set to false to always show it.")
                .define("MuteCnpcDimensionWarning", true);
        SUforceHostileSpawns = BUILDER
                .comment("When true, natural hostile mob spawns are enabled regardless of the global world difficulty",
                        "(they are no longer zeroed when the global difficulty is peaceful). Per-region mob-spawning",
                        "deny flags and per-dimension difficulty remain the fine-grained controls. When false, natural",
                        "hostile spawning follows the global difficulty (disabled only when the global difficulty is peaceful).")
                .define("ForceHostileSpawns", true);
        SUstoreUrl = BUILDER
                .comment("Where the /buy command sends players. Set this to your own store page, for example",
                        "https://example.com/store, and /buy (aliases /store and /shop) will hand players a",
                        "clickable link to it.",
                        "",
                        "LEAVE IT BLANK (the default) AND THE COMMAND IS NOT REGISTERED AT ALL, so a server with no",
                        "store does not get a command that apologises for not having one. That also means adding a",
                        "URL here needs a restart to take effect: a command cannot be added to the dispatcher after",
                        "it has been built.",
                        "",
                        "If you sell through Tebex and its mod is installed, that mod registers its own /buy showing",
                        "the Tebex hosted page. Set disable-buy-command=true in config/buycraft/config.properties so",
                        "the two do not both claim the name.")
                .define("StoreUrl", "");
        SUnpcRegionAutoAggroBpFraction = BUILDER
                .comment("How far from a region NPC's own battle power a player may be, EITHER WAY, and still be",
                        "attacked on sight by that NPC, as a fraction (0.10 = a 10 percent band above and below).",
                        "A hostile region NPC only acquires a player unprompted when the player's DMZ battle power is",
                        "between (1 - this) and (1 + this) of the NPC's own, so a weaker player questing nearby is left",
                        "alone and a player who far outclasses the NPC is not pestered by something that cannot threaten",
                        "them. This never affects retaliation: a player who hits the NPC is always fought back, whatever",
                        "their battle power. It also does not override an NPC set to a non-hostile behaviour, which stays",
                        "passive regardless. 0 engages only an exact battle-power match; 1 disables the gate and restores",
                        "the old behaviour of engaging every player in range. Applies to every NPC region on the server.")
                .defineInRange("NpcRegionAutoAggroBpFraction", 0.10D, 0.0D, 1.0D);
        SUprestigeMax = BUILDER
                .comment("Highest prestige level a character can reach (prestige NPC).")
                // no ceiling: this level count is what multiplies the DMZ per-stat cap through the two percentages
                // below, so an upper bound here is an upper bound on a stat, and 100 was a number somebody picked
                .defineInRange("PrestigeMaxLevel", 3, 0, Integer.MAX_VALUE);
        SUprestigeTpBonusPerLevel = BUILDER
                .comment("Bonus TP gain in percent granted per prestige level (additive; 10 => +30% at prestige 3).")
                .defineInRange("PrestigeTpBonusPercentPerLevel", 10, 0, 100000);
        SUprestigeCapBonusPerLevel = BUILDER
                .comment("Bonus to the DMZ per-stat max cap in percent granted per prestige level "
                        + "(additive; 25 => +100% = double the max at prestige 4).")
                .defineInRange("PrestigeCapBonusPercentPerLevel", 25, 0, 100000);
        SUwishTrackingEnabled = BUILDER
                .comment("When true, the server keeps a running count of dragon ball uses and swaps the "
                        + "available set once the threshold below is reached. Enabled by default so the corrupted "
                        + "dragon ball endgame functions on a fresh server; set to false to keep the whole subsystem "
                        + "inert.")
                .define("WishTrackingEnabled", true);
        SUwishTrackingThreshold = BUILDER
                .comment("Server-wide number of dragon ball uses at which the set is swapped.")
                .defineInRange("WishTrackingThreshold", 10, 1, 1000);
        SUcorruptedStormExtraTicks = BUILDER
                .comment("Extra ticks added on top of the swap-sequence storm (20 ticks = 1 second). The storm "
                        + "length is derived from the sequence's own audio timeline so it always outlasts the voice "
                        + "lines; this value can only make the storm last longer, never end it early. Defaults to 0.")
                .defineInRange("SwapStormExtraTicks", 0, 0, 72000);
        SUcorruptedPropLingerTicks = BUILDER
                .comment("How long the swap-sequence prop stays visible after the smite, in ticks "
                        + "(20 ticks = 1 second). Defaults to 40 (2 seconds).")
                .defineInRange("SwapPropLingerTicks", 40, 1, 72000);
        SUcorruptedDragonLifetimeTicks = BUILDER
                .comment("How long a swap-set boss stays in the world before it is removed if it is never defeated, "
                        + "in ticks (20 ticks = 1 second). This survives a server restart. Defaults to 216000 "
                        + "(3 hours).")
                .defineInRange("SwapBossLifetimeTicks", 216000, 20, 6048000);
        SUssgWishMinLevel = BUILDER
                .comment("Minimum DMZ level a saiyan must reach before Shenron offers the Knowledge of Super Saiyan",
                        "God wish (and before the server will grant it). Enforced server-side, so this gates both what",
                        "the wish screen shows and what a crafted packet may take. Only saiyans ever see the wish; this",
                        "sets how far a saiyan must have come first. Default 5000. On an existing server this key is",
                        "written into the config file with the default the first time this build loads, so change it",
                        "there.")
                .defineInRange("SsgWishMinLevel", 5000, 0, 1_000_000);
        SUssgRitualRequiredChargers = BUILDER
                .comment("How many OTHER saiyans must charge their ki in the ring around the centre saiyan for the Super",
                        "Saiyan God charge ritual to complete. The ritual needs this many chargers PLUS the centre, so the",
                        "default 5 means six saiyans total (the centre and five allies). Enforced server-side by",
                        "SsgRitualManager, so this gates the ring size no matter what a client claims. Set to 1 to allow a",
                        "two-saiyan ritual (the centre and a single charger). On an existing server this key is written",
                        "into the config file with the default the first time this build loads, so change it there.")
                .defineInRange("SsgRitualRequiredChargers", 5, 1, 100);
        SUruneAwakenZeniCost = BUILDER
                .comment("Zeni charged to awaken one dormant rune at the rune bench. Charged server-side, before the",
                        "dormant rune is consumed, so a player who cannot pay loses neither the rune nor any Zeni. Zero",
                        "means no charge, which is the old free awakening. Default 5000. On an existing server this key is",
                        "written into the config file with the default the first time this build loads, so change it",
                        "there.")
                .defineInRange("RuneAwakenZeniCost", 5000, 0, 1_000_000_000);
        SUholdOverworldFluids = BUILDER
                .comment("Stop water and lava spreading in the OVERWORLD. Off by default, so fluids behave exactly as",
                        "vanilla. Turn it on only for a hand-built imported overworld, where one misplaced source",
                        "block erodes a build that cannot be regenerated. It does not affect any other dimension, and",
                        "it does not stop placing or removing fluid by hand, only the outward spread.")
                .define("HoldOverworldFluids", false);
        SUholdOverworldFallingBlocks = BUILDER
                .comment("Stop sand and gravel falling in the OVERWORLD. Off by default, so gravity behaves exactly as",
                        "vanilla. Same purpose and same warning as HoldOverworldFluids: it exists for a hand-built",
                        "imported map where an unsupported column would collapse a build, and it is wrong anywhere",
                        "else. Our own dimensions already hold gravel and sand still by their own separate rule.")
                .define("HoldOverworldFallingBlocks", false);
        SUprotectedBuildDimensions = BUILDER
                .comment("Dimension ids, comma separated, whose hand-built terrain ordinary players may not alter, e.g.",
                        "\"dmz_ragnarok:planet_vegeta,dmz_ragnarok:some_dungeon\". Inside a listed dimension a non-staff",
                        "player cannot break or place blocks, harm non-hostile NPCs, or strip armor stands and item",
                        "frames. Hostile and saga quest mobs stay fully fightable and PvP is untouched, so the dimension",
                        "still plays normally; only griefing the build is stopped. This is for FIXED, hand-authored bodies",
                        "(Planet Vegeta, custom dungeon builds) that have no generated-planet id and so cannot be claimed",
                        "the way a generated planet is. Staff bypass by holding the su.protection.builddim.bypass permission",
                        "(op by default) or by being in region build bypass (/rg bypass). Leave blank to protect nothing.",
                        "Default protects Planet Vegeta; add your own dungeon dimension ids alongside it.")
                .define("ProtectedBuildDimensions", "dmz_ragnarok:planet_vegeta");
        SUoverworldDragonBallScatterRange = BUILDER
                .comment("Half-side, in blocks, of the square area DMZ scatters overworld dragon balls into, measured",
                        "from world spawn (so effectively the scatter radius). Keeps overworld balls inside the",
                        "imported custom map instead of flinging some out into the ordinary generated terrain beyond",
                        "it, where they cannot be found. The default 3000 fits the current map (about x/z -3072..3071);",
                        "raise it if the map is enlarged. Applies to DMZ's Earth set (clamped: a larger DMZ spawn",
                        "range is capped to this, a smaller one is left alone) and to our overworld sets (used as-is).")
                .defineInRange("OverworldDragonBallScatterRange", 3000, 64, 64000);
        SUsuperDragonBallMinPlacementDistance = BUILDER
                .comment("Minimum spacing, in blocks, between two Super Dragon Balls placed by hand. A Super ball's",
                        "model is about 2.9 blocks across, so placing one within this many blocks of another is",
                        "refused, which stops the oversized spheres from visually overlapping. 3 is the smallest value",
                        "that clears the model; seven balls still fit for a summon (a 3x3 grid at this spacing spans 6",
                        "blocks, inside DMZ's 11-block summon box). Applies only to Shurui's Super set, never to Earth,",
                        "Namek or the other sets. Set to 1 to disable the spacing rule. DMZ's random world scatter is",
                        "not affected (it places balls far apart at random and does not go through this check).")
                .defineInRange("SuperDragonBallMinPlacementDistance", 3, 1, 16);
        SUdragonBallAlpha = BUILDER
                .comment("Opacity of a dragon ball's translucent shell (the Super Dragon Ball planet bodies in space),",
                        "from 0 (invisible) to 1 (fully solid). The default 0.7 is a 30-percent-transparent glass look",
                        "so the stars on the billboard inside show through. Lower it for a clearer view of the inner",
                        "stars, raise it toward 1 for a more solid ball.")
                .defineInRange("DragonBallShellAlpha", 0.7D, 0.05D, 1.0D);
        SUdragonBallRenderStyle = BUILDER
                .comment("How placed dragon balls are drawn. SPHERE (default) draws each ball as one translucent glass",
                        "sphere with its stars on a billboard inside, the current look. CUBE draws each ball with its",
                        "original faceted model and real texture, so the painted stars show on the surface, made",
                        "translucent at DragonBallShellAlpha with no stacked inner faces showing through. Applies to",
                        "every set Shurui's Utilities draws: Super, Black Star, Cerulean, Earth, Namek and the corrupted",
                        "balls. The ball renderers read the live value every frame, so toggling it (in file or via the",
                        "in-game switch on DragonMineZ's config menu) applies immediately; no resource reload or restart.")
                .defineEnum("DragonBallRenderStyle", DragonBallRenderStyle.SPHERE);
        SUradarFuzzRadius = BUILDER
                .comment("Radius, in blocks, of the approximate area the NORMAL dragon ball radars point at (every",
                        "set except Super and Black Star: DMZ's Earth and Namek, plus Cerulean). The server offsets",
                        "each normal ball's position by a deterministic horizontal amount within this radius before it",
                        "is sent, so those radars lead to a rough area instead of the exact block and a modified client",
                        "cannot recover the true coordinate. The offset is fixed per ball, so the dot stays put rather",
                        "than jittering. The default 25 puts the dot within 25 blocks of the ball (the offset lands",
                        "between half the radius and the full radius, so 12 to 25 blocks), which is close enough to",
                        "search by eye on arrival. Set to 0 to disable fuzzing (all radars exact). Super and Black",
                        "Star radars are always exact regardless.")
                .defineInRange("RadarApproximateRadius", 25, 0, 4000);
        SUplanetVegetaGravity = BUILDER
                .comment("Gravity multiplier for the Planet Vegeta dimension (shuruisutilities:planet_vegeta). The",
                        "default 10.0 is ten times normal gravity. This is seeded once into DragonMineZ's own",
                        "per-dimension gravity config (general-server.json) at server start, and only if the entry is",
                        "not already present, so a value you tune here or directly in DMZ's config is never overwritten",
                        "on a later boot. DMZ performs all of the gravity work itself (server-side tick plus client",
                        "sync); SU only supplies the entry so the planet ships heavy without a hand-edited config file.")
                .defineInRange("PlanetVegetaGravity", 10.0D, 0.0D, 1000.0D);
        SUitemClearEnabled = BUILDER
                .comment("Periodically delete dropped items lying on the ground, the way a clearlag plugin does.",
                        "OFF by default and deliberately so: this destroys players' property, and no server should",
                        "start doing that merely because it updated the mod. Turn it on only if ground items are",
                        "actually costing you tick time. Dragon balls are NEVER cleared, whatever else is set here.")
                .define("ItemClearEnabled", false);
        SUitemClearIntervalSeconds = BUILDER
                .comment("Seconds between one clear and the next. The countdown restarts the moment a clear runs, so",
                        "300 means a clear every five minutes. Only counts while ItemClearEnabled is true; turning",
                        "the feature off resets the countdown rather than pausing it.")
                .defineInRange("ItemClearIntervalSeconds", 300, 10, 86400);
        SUitemClearWarnSeconds = BUILDER
                .comment("Seconds-before-the-clear at which a warning is broadcast, comma separated and in any order.",
                        "Each value must be smaller than ItemClearIntervalSeconds to ever be reached. Leave empty",
                        "for a silent clear with no warnings at all.")
                .define("ItemClearWarnSeconds", "60,30,10,5");
        SUitemClearWarnMessage = BUILDER
                .comment("The warning line. {seconds} is replaced with how many seconds remain. Colour codes are",
                        "written with & and are converted for you. Set to an empty string to suppress the warning",
                        "while still clearing on schedule.")
                .define("ItemClearWarnMessage", "&e[!] &fDropped items will be cleared in &c{seconds}&f seconds.");
        SUitemClearDoneMessage = BUILDER
                .comment("The line broadcast after a clear. {count} is replaced with how many item entities were",
                        "removed. Empty suppresses it. Nothing is broadcast when a clear removed nothing, so an idle",
                        "server stays quiet regardless of what is set here.")
                .define("ItemClearDoneMessage", "&e[!] &fCleared &c{count}&f dropped items.");
        SUitemClearExcludedItems = BUILDER
                .comment("Item ids never cleared, comma separated, e.g. \"minecraft:diamond,minecraft:netherite_ingot\".",
                        "Every dragon ball is spared regardless of this list and cannot be added to a clear: losing",
                        "one to a timer breaks a seven-ball set for everybody on the server.")
                .define("ItemClearExcludedItems", "");
        BUILDER.pop();

        BUILDER.comment("Patreon account linking. The Minecraft SERVER talks to the backend Worker with the shared",
                "API key below; the client never does. These live in this COMMON config on purpose: a Forge SERVER",
                "config is stored in the world folder and SYNCED to every client that joins, so an API key placed",
                "there would be shipped to players. COMMON config files stay on the server and are never synced.")
                .push("Patreon");
        SUpatreonBackendUrl = BUILDER
                .comment("Base URL of the backend that runs the Patreon OAuth, with no trailing slash. LEAVE THIS",
                        "EMPTY unless you run your own backend: empty means 'use the built-in one', so supporter perks",
                        "work on ANY server running this mod with no setup at all. Reading a player's tier needs no key",
                        "and no secret. To switch supporter linking OFF on this server, put the word off here.")
                .define("BackendUrl", DEFAULT_PATREON_BACKEND_URL);
        SUpatreonServerApiKey = BUILDER
                .comment("OPTIONAL, and only useful to the people who run the backend. Leave it empty: perks work",
                        "without it. It unlocks one convenience, the server minting a one-time link code so",
                        "/patreon link can post a ready-made clickable link. Everyone else uses the browser-first",
                        "flow (link on the website, then enter the code in the Cosmetics menu or /patreon claim),",
                        "which is safer anyway: whoever mints a code picks which Minecraft account a pledge binds to.",
                        "If you do set it, it is a SECRET: never logged, never sent to any client.")
                .define("ServerApiKey", "");
        SUpatreonRefreshMinutes = BUILDER
                .comment("How often, in minutes, a logged-in player's Patreon tier is re-fetched from the backend.")
                .defineInRange("RefreshMinutes", 30, 1, 1440);
        SUpatreonGraceHours = BUILDER
                .comment("Grace period, in hours, that a last-known-good tier keeps applying while the backend is",
                        "unreachable, so a transient outage never strips a paying supporter's rewards. Once this",
                        "elapses with no successful refresh, the tier falls back to none. Default 72 (three days).")
                .defineInRange("GraceHours", 72, 0, 8760);
        SUpatreonHttpTimeoutSeconds = BUILDER
                .comment("Per-request HTTP timeout, in seconds, for every backend call. All backend I/O happens off",
                        "the server thread, so this only bounds the worker thread, never a game tick.")
                .defineInRange("HttpTimeoutSeconds", 10, 1, 120);
        BUILDER.pop();

        BUILDER = ChatOutputHandler.load(BUILDER, isReload);
    }

    @Override
    public void bakeConfig(boolean reload)
    {
        // Apply pending one-time migrations BEFORE reading any value below, so a setting a migration changes
        // (for example CustomMainMenu) is baked at its migrated value on THIS launch rather than the next one.
        // Only on the initial bake; a later config reload must not re-run migrations (the stamp already blocks
        // them, but skipping on reload also avoids a needless rewrite).
        if (!reload)
            runConfigMigrations();
        FORMAT_DATE = new SimpleDateFormat(SUFORMAT_DATE.get());
        FORMAT_DATE_TIME = new SimpleDateFormat(SUFORMAT_DATE_TIME.get());
        FORMAT_DATE_TIME_SECONDS = new SimpleDateFormat(SUFORMAT_DATE_TIME_SECONDS.get());
        FORMAT_TIME = new SimpleDateFormat(SUFORMAT_TIME.get());
        FORMAT_TIME_SECONDS = new SimpleDateFormat(SUFORMAT_TIME_SECONDS.get());
        modlistLocation = SUmodlistLocation.get();

        majoritySleep = SUmajoritySleep.get();
        checkSpacesInNames = SUcheckSpacesInNames.get();
        muteCnpcDimensionWarning = SUmuteCnpcDimensionWarning.get();
        forceHostileSpawns = SUforceHostileSpawns.get();
        storeUrl = SUstoreUrl.get() == null ? "" : SUstoreUrl.get().trim();
        npcRegionAutoAggroBpFraction = SUnpcRegionAutoAggroBpFraction.get();
        prestigeMax = SUprestigeMax.get();
        prestigeTpBonusPerLevel = SUprestigeTpBonusPerLevel.get();
        prestigeCapBonusPerLevel = SUprestigeCapBonusPerLevel.get();
        enableCommandAliases = SUenableCommandAliases.get();
        overwriteConflictingCommands = SUoverwriteConflictingCommands.get();
        worldScopedData = SUworldScopedData.get();
        customMainMenu = SUcustomMainMenu.get();
        customStatHud = SUcustomStatHud.get();
        customStatHudScale = SUcustomStatHudScale.get();
        customQuestTracker = SUcustomQuestTracker.get();
        questTrackerCoordLines = SUquestTrackerCoordLines.get();
        questTrackerFallbackRightMargin = SUquestTrackerFallbackRightMargin.get();
        questTrackerFallbackTopY = SUquestTrackerFallbackTopY.get();
        questTrackerFallbackWidth = SUquestTrackerFallbackWidth.get();
        auraTrails = SUauraTrails.get();
        flightAura = SUflightAura.get();
        suiteAnnouncements = SUsuiteAnnouncements.get();
        lensingEnabled = SUlensingEnabled.get();
        planetVegetaGravity = SUplanetVegetaGravity.get();
        wishTrackingEnabled = SUwishTrackingEnabled.get();
        wishTrackingThreshold = SUwishTrackingThreshold.get();
        corruptedStormExtraTicks = SUcorruptedStormExtraTicks.get();
        corruptedPropLingerTicks = SUcorruptedPropLingerTicks.get();
        corruptedDragonLifetimeTicks = SUcorruptedDragonLifetimeTicks.get();
        ssgWishMinLevel = SUssgWishMinLevel.get();
        ssgRitualRequiredChargers = SUssgRitualRequiredChargers.get();
        runeAwakenZeniCost = SUruneAwakenZeniCost.get();
        holdOverworldFluids = SUholdOverworldFluids.get();
        holdOverworldFallingBlocks = SUholdOverworldFallingBlocks.get();
        protectedBuildDimensions = parseDimensionList(SUprotectedBuildDimensions.get());
        // The whole point of the two keys above: WorldPhysicsModule used to hold its own hardcoded true and this
        // call never existed, so nothing could turn the freeze off. Baking it here is what makes them real.
        net.shurui.shuruisutilities.world.WorldPhysicsModule.bake(holdOverworldFallingBlocks, holdOverworldFluids);
        overworldDragonBallScatterRange = SUoverworldDragonBallScatterRange.get();
        superDragonBallMinPlacementDistance = SUsuperDragonBallMinPlacementDistance.get();
        itemClearEnabled = SUitemClearEnabled.get();
        itemClearIntervalSeconds = SUitemClearIntervalSeconds.get();
        itemClearWarnSeconds = SUitemClearWarnSeconds.get();
        itemClearWarnMessage = SUitemClearWarnMessage.get();
        itemClearDoneMessage = SUitemClearDoneMessage.get();
        itemClearExcludedItems = SUitemClearExcludedItems.get();
        dragonBallAlpha = SUdragonBallAlpha.get();
        dragonBallRenderStyle = SUdragonBallRenderStyle.get();
        radarFuzzRadius = SUradarFuzzRadius.get();
        patreonBackendUrl = SUpatreonBackendUrl.get();
        patreonServerApiKey = SUpatreonServerApiKey.get();
        patreonRefreshMinutes = SUpatreonRefreshMinutes.get();
        patreonGraceHours = SUpatreonGraceHours.get();
        patreonHttpTimeoutSeconds = SUpatreonHttpTimeoutSeconds.get();
        ShuruisUtilities.bakeConfig(reload);
        ChatOutputHandler.bakeConfig(reload);
    }

    /**
     * Parse a comma-separated dimension-id list into an immutable set, dropping blanks and surrounding whitespace. An
     * empty or all-blank string yields an empty set (protect nothing), which the guard treats as inert.
     */
    private static java.util.Set<String> parseDimensionList(String raw)
    {
        if (raw == null || raw.isBlank())
            return java.util.Collections.emptySet();
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : raw.split(","))
        {
            String id = part.trim();
            if (!id.isEmpty())
                out.add(id);
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    /**
     * Sets the dragon ball render style at runtime and persists it. Updating the baked static field is what makes the
     * change apply instantly: the two ball block-entity renderers read {@link #dragonBallRenderStyle} every frame, so
     * no config reload or resource reload is needed. Used by the native switch row injected into DragonMineZ's own
     * in-game config menu.
     */
    public static void setDragonBallRenderStyle(DragonBallRenderStyle s)
    {
        dragonBallRenderStyle = s;      // live: read per-frame by the two ball renderers
        SUdragonBallRenderStyle.set(s); // persist
    }

    /**
     * Sets whether SU's custom stat HUD is active and persists it. Updating the baked static field applies instantly:
     * the overlay reads {@link #customStatHud} every frame both to decide whether to draw and whether to suppress DMZ's
     * two stat overlays, so toggling this off restores DMZ's default HUD with no reload. Used by the native switch row
     * injected into DragonMineZ's own in-game config menu. ON means SU's HUD is active (the config default is true).
     */
    public static void setCustomStatHud(boolean on)
    {
        customStatHud = on;      // live: read per-frame by ScouterStatHudOverlay (draw + DMZ suppression)
        SUcustomStatHud.set(on); // persist
    }

    /**
     * Set the aura-trail preference live and persist it. Same contract as {@link #setCustomStatHud(boolean)}:
     * {@code DashTrailRenderer} reads {@link #auraTrails} every tick and every frame, so turning it off drops the
     * streaks immediately with no reload. Used by the native switch row injected into DragonMineZ's own in-game
     * config menu. ON means trails are drawn (the config default is true).
     */
    public static void setAuraTrails(boolean on)
    {
        auraTrails = on;      // live: read per-tick and per-frame by DashTrailRenderer
        SUauraTrails.set(on); // persist
    }

    /**
     * Set the fast-flight aura preference live and persist it. Same contract as {@link #setAuraTrails(boolean)}:
     * {@link net.shurui.shuruisutilities.client.combat.FastFlightAura} reads {@link #flightAura} every client tick, so
     * turning it off drops the laid-over fast-flight aura promptly with no reload. Used by the native switch row
     * injected into DragonMineZ's own in-game config menu. ON means the aura is laid over (the config default is true).
     */
    public static void setFlightAura(boolean on)
    {
        flightAura = on;      // live: read per client tick by FastFlightAura
        SUflightAura.set(on); // persist
    }

    /**
     * Set the suite-announcement preference live and persist it. Same contract as {@link #setFlightAura(boolean)}:
     * used by the native switch row injected into DragonMineZ's own in-game config menu. ON means SU's on-screen
     * announcements are shown (the config default is true). Unlike the cosmetics above this preference also has to
     * reach the SERVER (SUConfig is COMMON, so Forge never syncs it), so the row that calls this setter also sends
     * the preference to the server; see the announce package.
     */
    public static void setSuiteAnnouncements(boolean on)
    {
        suiteAnnouncements = on;      // live: read by the client-side RegionHudOverlay entry title
        SUsuiteAnnouncements.set(on); // persist
    }

    /**
     * Set the custom stat HUD scale live and persist it. Same contract as {@link #setCustomStatHud(boolean)}:
     * {@link net.shurui.shuruisutilities.client.hud.ScouterStatHudOverlay} reads {@link #customStatHudScale} every
     * frame, so a new scale applies immediately with no reload. Used by the native FLOAT slider row injected into
     * DragonMineZ's own in-game config menu; DMZ's setter hands a float, which widens losslessly into the stored double.
     */
    public static void setCustomStatHudScale(double scale)
    {
        customStatHudScale = scale;      // live: read per-frame by ScouterStatHudOverlay
        SUcustomStatHudScale.set(scale); // persist
    }

    @Override
    public ConfigData returnData()
    {
        return data;
    }

}
