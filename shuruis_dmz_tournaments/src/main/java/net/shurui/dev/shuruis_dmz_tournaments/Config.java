package net.shurui.dev.shuruis_dmz_tournaments;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.List;

// all tunables. also editable in-game via /rg tourney config ... which writes back here and persists
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_tournaments", bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ITEMS_ALLOWED = BUILDER
            .comment("Whether contestants may use items (consumables, senzu, etc.) during a match.")
            .define("rules.itemsAllowedInArena", false);
    public static final ForgeConfigSpec.BooleanValue HEAL_AFTER_FIGHT = BUILDER
            .comment("Fully heal both contestants (health, energy, stamina) after every match.")
            .define("rules.healAfterFight", true);
    public static final ForgeConfigSpec.BooleanValue FORCE_FRIENDLY_FIST = BUILDER
            .comment("Force DragonMineZ friendly-fist (non-lethal) ON for contestants inside the arena.")
            .define("rules.forceFriendlyFist", true);
    public static final ForgeConfigSpec.BooleanValue PVP_IN_WAITING = BUILDER
            .comment("Allow PvP in the fighter waiting area. Normally false.")
            .define("rules.allowPvpInWaitingArea", false);
    public static final ForgeConfigSpec.BooleanValue PVP_IN_STANDS = BUILDER
            .comment("Allow PvP in the spectator stands. Normally false.")
            .define("rules.allowPvpInStands", false);
    public static final ForgeConfigSpec.IntValue COUNTDOWN_SECONDS = BUILDER
            .comment("Countdown length before a match begins.")
            .defineInRange("rules.countdownSeconds", 5, 0, 60);
    public static final ForgeConfigSpec.IntValue MATCH_TIME_LIMIT = BUILDER
            .comment("Match time limit in seconds (0 = unlimited). On timeout the higher-health fighter wins.")
            .defineInRange("rules.matchTimeLimitSeconds", 300, 0, 3600);

    public static final ForgeConfigSpec.BooleanValue TOURNAMENT_CHARS_ENABLED = BUILDER
            .comment("Swap every contestant into a dedicated tournament-only character on entry and back to their",
                    "real character afterwards. The tournament character has a fixed training-point allowance, a",
                    "chosen ki/strike attack loadout, and cannot gain TP or dynamic growth. It is reloaded from a",
                    "frozen template on every entry so it can never carry progress out of a tournament.")
            .define("characters.enabled", true);
    /**
     * DEAD as of the stat-allocation switch, kept only so an existing config file doesn't lose the key and log a
     * warning. Nothing reads it. Do NOT resurrect it by lowering the default: a changed default does nothing for
     * a file that already exists, so the only way to retire a setting is to stop reading it, which
     * {@link #TOURNAMENT_STAT_ALLOCATIONS} does.
     */
    @Deprecated
    public static final ForgeConfigSpec.IntValue TOURNAMENT_TP_ALLOWANCE = BUILDER
            .comment("UNUSED. Superseded by characters.statAllocations. A tournament character is now given flat",
                    "stat allocations instead of a training-point pool, so this value is ignored.")
            .defineInRange("characters.trainingPointAllowance", 100000, 0, Integer.MAX_VALUE);

    public static final ForgeConfigSpec.IntValue TOURNAMENT_STAT_ALLOCATIONS = BUILDER
            .comment("Flat stat allocations granted to a tournament character, spent through DragonMineZ's own",
                    "stats screen. These are DMZ's PENDING ATTRIBUTE POINTS, not training points, and that is the",
                    "whole point: DMZ spends attribute points BEFORE training points and at one point per stat,",
                    "with no cost curve, so every contestant gets exactly this many stat increases whatever their",
                    "race or how far they have already allocated. A training-point pool did not do that, because",
                    "its recursive cost meant the same pool bought wildly different amounts of stat.",
                    "Training points are set to zero on a tournament character so this is the only currency.")
            .defineInRange("characters.statAllocations", 2500, 0, Integer.MAX_VALUE);
    public static final ForgeConfigSpec.BooleanValue FORCE_TRANSFORM_AT_HALF = BUILDER
            .comment("When a contestant drops to or below half health, force them into their first reachable form",
                    "(or Ultimate if their race has no forms) with invulnerability frames during the transformation.")
            .define("characters.forceTransformAtHalfHealth", true);
    public static final ForgeConfigSpec.DoubleValue FORCED_FORM_STAT_MULT = BUILDER
            .comment("Stock stat multiplier applied to a tournament character's base stats when it is forced to",
                    "transform. Applies only inside a tournament (the template is never written back).",
                    "No upper ceiling: the operator sets this, and a hidden bound only ever surfaces as",
                    "'the number I typed is not the number that was saved'.")
            .defineInRange("characters.forcedFormStatMultiplier", 1.5, 1.0, Double.MAX_VALUE);
    public static final ForgeConfigSpec.IntValue FORCED_TRANSFORM_IFRAME_TICKS = BUILDER
            .comment("Length in ticks of the damage-immunity window granted while the forced transformation plays.")
            .defineInRange("characters.forcedTransformIframeTicks", 40, 0, 600);

    public static final ForgeConfigSpec.IntValue MAX_PARTICIPANTS = BUILDER
            .comment("Maximum contestants per tournament (bracket is padded with byes). 0 = unlimited.")
            .defineInRange("signup.maxParticipants", 16, 0, 256);
    public static final ForgeConfigSpec.IntValue SIGNUP_MINUTES = BUILDER
            .comment("How long sign-ups stay open before an auto-scheduled tournament begins.")
            .defineInRange("signup.durationMinutes", 10, 1, 1440);
    public static final ForgeConfigSpec.IntValue MIN_PARTICIPANTS = BUILDER
            .comment("Minimum contestants required or the tournament is cancelled.")
            .defineInRange("signup.minParticipants", 2, 2, 256);
    public static final ForgeConfigSpec.BooleanValue REQUIRE_CHARACTER = BUILDER
            .comment("Require the player to have created a DragonMineZ character to sign up.")
            .define("signup.requireDmzCharacter", true);
    public static final ForgeConfigSpec.IntValue SIGNUP_REMINDER_SECONDS = BUILDER
            .comment("While sign-ups are open, repeat the on-screen 'Sign-ups Open' title + clickable join prompt",
                    "every this-many seconds (e.g. 30 = twice a minute, 60 = once a minute). 0 = announce only once.")
            .defineInRange("signup.reminderSeconds", 30, 0, 3600);

    public static final ForgeConfigSpec.ConfigValue<String> NPC_NAME = BUILDER
            .comment("Display name of the sign-up NPC.")
            .define("npc.name", "Hercule");
    public static final ForgeConfigSpec.ConfigValue<String> NPC_CHARACTER = BUILDER
            .comment("Which DragonMineZ tournament character skin the NPC uses: hercule, announcer, etc.")
            .define("npc.character", "hercule");
    public static final ForgeConfigSpec.DoubleValue NPC_LOOK_RADIUS = BUILDER
            .comment("How far (in blocks) a sign-up NPC will turn to face the nearest player. 0 disables look-at.")
            .defineInRange("npc.lookRadius", 8.0, 0.0, 64.0);

    public static final ForgeConfigSpec.ConfigValue<String> SCHEDULE_MODE = BUILDER
            .comment("Automatic scheduling: MANUAL, DAILY, WEEKLY, or MONTHLY.")
            .define("schedule.mode", "MANUAL");
    public static final ForgeConfigSpec.IntValue SCHEDULE_HOUR = BUILDER
            .comment("Hour (server local time, 0-23) sign-ups auto-open on a scheduled day.")
            .defineInRange("schedule.hour", 18, 0, 23);
    public static final ForgeConfigSpec.IntValue SCHEDULE_MINUTE = BUILDER
            .defineInRange("schedule.minute", 0, 0, 59);
    public static final ForgeConfigSpec.IntValue SCHEDULE_DAY_OF_WEEK = BUILDER
            .comment("Day of week for WEEKLY mode (1=Monday .. 7=Sunday).")
            .defineInRange("schedule.dayOfWeek", 6, 1, 7);
    public static final ForgeConfigSpec.IntValue SCHEDULE_DAY_OF_MONTH = BUILDER
            .comment("Day of month for MONTHLY mode (1-28).")
            .defineInRange("schedule.dayOfMonth", 1, 1, 28);

    public static final ForgeConfigSpec.BooleanValue USE_TITLES = BUILDER
            .comment("Show announcements as on-screen titles in addition to chat.")
            .define("announce.useOnScreenTitles", true);
    public static final ForgeConfigSpec.ConfigValue<String> ANNOUNCE_SOUND = BUILDER
            .comment("Sound played to everyone on major announcements (resource location, empty to disable).")
            .define("announce.sound", "minecraft:ui.toast.challenge_complete");
    public static final ForgeConfigSpec.ConfigValue<String> MSG_SIGNUP_OPEN = BUILDER
            .define("announce.signupOpen", "&e[Tournament] &fSign-ups are now OPEN! Talk to {npc} or use /rg tourney join.");
    public static final ForgeConfigSpec.ConfigValue<String> MSG_STARTING = BUILDER
            .define("announce.starting", "&6[Tournament] &fThe tournament is starting with {count} fighters!");
    public static final ForgeConfigSpec.ConfigValue<String> MSG_MATCH = BUILDER
            .define("announce.match", "&6[Tournament] &fNext match: &e{p1} &fvs &e{p2}&f!");
    public static final ForgeConfigSpec.ConfigValue<String> MSG_MATCH_WIN = BUILDER
            .define("announce.matchWin", "&6[Tournament] &e{winner} &fdefeats &e{loser}&f!");
    public static final ForgeConfigSpec.ConfigValue<String> MSG_CHAMPION = BUILDER
            .define("announce.champion", "&6[Tournament] &e{champion} &fis the new CHAMPION!");

    // rewards: see RewardEntry for the token format
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> WINNER_REWARDS = BUILDER
            .comment("Rewards for the champion. Tokens: 'command:<cmd with {player}>', 'item:<id>:<count>',",
                    "'tp:<x> <y> <z>', 'skill:<dmzSkillId>:<levels>', 'title:<titleId>', 'message:<text>'.")
            .defineListAllowEmpty("rewards.winner",
                    List.of("title:champion", "item:minecraft:diamond:10", "message:&6You are the champion!"),
                    Config::isString);
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> RUNNERUP_REWARDS = BUILDER
            .defineListAllowEmpty("rewards.runnerUp", List.of("item:minecraft:diamond:3"), Config::isString);
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PARTICIPATION_REWARDS = BUILDER
            .defineListAllowEmpty("rewards.participation", List.of("item:minecraft:gold_ingot:1"), Config::isString);

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> TITLE_DEFS = BUILDER
            .comment("Configurable titles, format 'id|Display Text|Tag'. Granted via 'title:<id>' rewards.",
                    "Tag (with & colour codes) is shown AFTER the holder's chat name and above their head.",
                    "Only one player holds a given title at a time; it transfers to the new winner.")
            .defineListAllowEmpty("titles.definitions",
                    List.of("champion|&6&lWorld Martial Arts Champion|&6&l [Champion]"), Config::isString);
    public static final ForgeConfigSpec.BooleanValue TITLE_CHAT_PREFIX = BUILDER
            .comment("Show a title holder's prefix before their name in chat.")
            .define("titles.chatPrefix", true);
    public static final ForgeConfigSpec.BooleanValue TITLE_NAMETAG_PREFIX = BUILDER
            .comment("Show a title holder's prefix above their head (via a scoreboard team; also decorates chat).")
            .define("titles.nametagPrefix", true);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    private static boolean isString(final Object obj) {
        return obj instanceof String;
    }

    public static volatile boolean loaded = false;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        // fires for EVERY spec this mod registers, not just ours. Guard by spec so "loaded" reflects OUR spec
        // being ready (and future .get() calls here don't run before it).
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        loaded = true;
    }

    /**
     * A reload of OUR spec (an edited config file, or /reload) can change the title definitions, so re-push the
     * recognised title list to every online client. Public because a Forge EventBusSubscriber handler that is private
     * compiles green and then fails to bind at runtime. Guarded by SPEC so it ignores every other mod's reload, and by
     * a null server so it is a no-op before a world is up (mod bus can fire client side with no integrated server).
     */
    @SubscribeEvent
    public static void onReload(final ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet.broadcastTitles(
                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer());
    }

    // keys editable at runtime via /rg tourney config <key> <value>
    public static final List<String> EDITABLE_KEYS = List.of(
            "items", "healafterfight", "friendlyfist", "pvpwaiting", "pvpstands",
            "countdown", "timelimit", "maxparticipants", "minparticipants", "signupminutes", "signupreminder",
            "requirecharacter", "tournamentchars", "npcname", "npccharacter", "npclookradius", "schedule", "schedulehour",
            "scheduleminute", "scheduledayofweek", "scheduledayofmonth", "usetitles", "sound");

    // reads are live so edits take effect right away; disk write happens on next config save.
    // false = unknown key or bad value
    public static boolean setByKey(String key, String value) {
        try {
            switch (key.toLowerCase()) {
                case "items" -> ITEMS_ALLOWED.set(parseBool(value));
                case "healafterfight" -> HEAL_AFTER_FIGHT.set(parseBool(value));
                case "friendlyfist" -> FORCE_FRIENDLY_FIST.set(parseBool(value));
                case "pvpwaiting" -> PVP_IN_WAITING.set(parseBool(value));
                case "pvpstands" -> PVP_IN_STANDS.set(parseBool(value));
                case "requirecharacter" -> REQUIRE_CHARACTER.set(parseBool(value));
                case "tournamentchars" -> TOURNAMENT_CHARS_ENABLED.set(parseBool(value));
                case "usetitles" -> USE_TITLES.set(parseBool(value));
                case "countdown" -> COUNTDOWN_SECONDS.set(Integer.parseInt(value));
                case "timelimit" -> MATCH_TIME_LIMIT.set(Integer.parseInt(value));
                case "maxparticipants" -> MAX_PARTICIPANTS.set(Integer.parseInt(value));
                case "minparticipants" -> MIN_PARTICIPANTS.set(Integer.parseInt(value));
                case "signupminutes" -> SIGNUP_MINUTES.set(Integer.parseInt(value));
                case "signupreminder" -> SIGNUP_REMINDER_SECONDS.set(Integer.parseInt(value));
                case "schedulehour" -> SCHEDULE_HOUR.set(Integer.parseInt(value));
                case "scheduleminute" -> SCHEDULE_MINUTE.set(Integer.parseInt(value));
                case "scheduledayofweek" -> SCHEDULE_DAY_OF_WEEK.set(Integer.parseInt(value));
                case "scheduledayofmonth" -> SCHEDULE_DAY_OF_MONTH.set(Integer.parseInt(value));
                case "npcname" -> NPC_NAME.set(value);
                case "npccharacter" -> NPC_CHARACTER.set(value);
                case "npclookradius" -> NPC_LOOK_RADIUS.set(Double.parseDouble(value));
                case "sound" -> ANNOUNCE_SOUND.set(value);
                case "schedule" -> {
                    String v = value.toUpperCase();
                    if (!List.of("MANUAL", "DAILY", "WEEKLY", "MONTHLY").contains(v)) return false;
                    SCHEDULE_MODE.set(v);
                }
                default -> {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean parseBool(String v) {
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on") || v.equals("1") || v.equalsIgnoreCase("yes");
    }
}
