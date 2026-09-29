package net.shurui.dev.shuruis_raid_bosses;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.List;

/**
 * Global defaults for the raid boss system. Each {@link net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef}
 * is seeded from these on creation, then edited independently in-game ({@code /rg raid edit}).
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_raids", bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.ConfigValue<String> BOSS_ENTITY = BUILDER
            .comment("Default entity used as the raid boss (any registered entity id).")
            .define("boss.entityType", "minecraft:warden");
    public static final ForgeConfigSpec.ConfigValue<String> BOSS_NAME = BUILDER
            .comment("Default display name shown above the raid boss and on its boss bar.")
            .define("boss.name", "Raid Boss");
    public static final ForgeConfigSpec.DoubleValue BASE_HEALTH = BUILDER
            .comment("Base max health of the boss for a single participant.",
                    "No upper ceiling: the operator picks this, an arena boss is meant to be absurd, and a",
                    "hidden bound only ever surfaces as 'the number I typed is not the number that was saved'.")
            .defineInRange("scaling.baseHealth", 500.0, 1.0, Double.MAX_VALUE);
    public static final ForgeConfigSpec.DoubleValue HEALTH_PER_PLAYER = BUILDER
            .comment("Extra max health added per additional participant (linear health scaling).")
            .defineInRange("scaling.healthPerPlayer", 250.0, 0.0, Double.MAX_VALUE);
    public static final ForgeConfigSpec.DoubleValue MAX_HEALTH_CAP = BUILDER
            .comment("Hard cap on the scaled max health (0 = no cap). The cap value itself is unbounded so the",
                    "operator can set it as high as they like without a saved value quietly disagreeing.")
            .defineInRange("scaling.maxHealthCap", 0.0, 0.0, Double.MAX_VALUE);
    public static final ForgeConfigSpec.BooleanValue SCALE_DAMAGE = BUILDER
            .comment("Also scale the boss's attack damage with the number of participants.")
            .define("scaling.scaleAttackDamage", false);
    public static final ForgeConfigSpec.DoubleValue DAMAGE_PER_PLAYER = BUILDER
            .comment("Extra attack damage added per additional participant (only if scaleAttackDamage is on).")
            .defineInRange("scaling.attackDamagePerPlayer", 2.0, 0.0, Double.MAX_VALUE);
    public static final ForgeConfigSpec.DoubleValue MAX_DAMAGE_PERCENT_PER_HIT = BUILDER
            .comment("Most damage a SINGLE hit may do to a raid boss, as a percent of the boss's max health.",
                    "0 = no ceiling. 2.5 means the boss always takes at least 40 landed hits, however hard the",
                    "hitter is. This caps the HIT, not the player: nobody's stats are touched, they simply cannot",
                    "end the encounter in one punch and leave the rest of the arena with nothing to fight.",
                    "This is only the default for newly created raids; it is set per raid in the raid editor.")
            .defineInRange("scaling.maxDamagePercentPerHit", 2.5, 0.0, 100.0);

    public static final ForgeConfigSpec.BooleanValue PVP_IN_ARENA = BUILDER
            .comment("Allow players to damage each other inside the raid arena. Normally false so nobody hits allies.")
            .define("rules.allowPvpInArena", false);
    public static final ForgeConfigSpec.BooleanValue PREVENT_REENTRY = BUILDER
            .comment("If enabled by default, players who are not active participants of a running raid are bounced out of that raid's arena, preventing re-entry. Toggleable per-arena in the raid editor.")
            .define("rules.preventReentry", false);
    public static final ForgeConfigSpec.BooleanValue HEAL_ON_START = BUILDER
            .comment("Fully heal all participants (health, energy, stamina) when the fight begins.")
            .define("rules.healOnStart", true);
    public static final ForgeConfigSpec.IntValue COUNTDOWN_SECONDS = BUILDER
            .comment("Countdown after participants are teleported in, before the boss spawns.")
            .defineInRange("rules.countdownSeconds", 5, 0, 60);
    public static final ForgeConfigSpec.IntValue FIGHT_TIME_LIMIT = BUILDER
            .comment("Fight time limit in seconds (0 = unlimited). On timeout the boss despawns and nobody is rewarded.")
            .defineInRange("rules.fightTimeLimitSeconds", 600, 0, 7200);

    public static final ForgeConfigSpec.IntValue MAX_PARTICIPANTS = BUILDER
            .comment("Maximum participants per raid. 0 = unlimited.")
            .defineInRange("signup.maxParticipants", 10, 0, 256);
    public static final ForgeConfigSpec.IntValue MIN_PARTICIPANTS = BUILDER
            .comment("Minimum participants required or the raid is cancelled.")
            .defineInRange("signup.minParticipants", 1, 1, 256);
    public static final ForgeConfigSpec.IntValue SIGNUP_MINUTES = BUILDER
            .comment("How long sign-ups stay open before an auto-scheduled raid begins.")
            .defineInRange("signup.durationMinutes", 5, 1, 1440);
    public static final ForgeConfigSpec.IntValue SIGNUP_REMINDER_SECONDS = BUILDER
            .comment("Repeat the sign-up announcement (chat + clickable Join button + on-screen title + sound)",
                    "every N seconds while sign-ups are open. 0 = announce only once when sign-ups open.")
            .defineInRange("signup.reminderSeconds", 30, 0, 3600);
    public static final ForgeConfigSpec.BooleanValue REQUIRE_CHARACTER = BUILDER
            .comment("Require the player to have created a DragonMineZ character to sign up.")
            .define("signup.requireDmzCharacter", true);

    public static final ForgeConfigSpec.ConfigValue<String> NPC_NAME = BUILDER
            .comment("Display name of the sign-up NPC.")
            .define("npc.name", "Raid Master");
    public static final ForgeConfigSpec.DoubleValue NPC_LOOK_RADIUS = BUILDER
            .comment("How far (in blocks) a sign-up NPC will turn to face the nearest player. 0 disables look-at.")
            .defineInRange("npc.lookRadius", 8.0, 0.0, 64.0);

    public static final ForgeConfigSpec.IntValue SCHEDULE_HOUR = BUILDER
            .comment("Hour (server local time, 0-23) sign-ups auto-open on a scheduled day.")
            .defineInRange("schedule.hour", 20, 0, 23);
    public static final ForgeConfigSpec.IntValue SCHEDULE_MINUTE = BUILDER
            .defineInRange("schedule.minute", 0, 0, 59);
    public static final ForgeConfigSpec.IntValue SCHEDULE_DAY_OF_WEEK = BUILDER
            .comment("Day of week for weekly scheduling (0=ignore/daily, 1=Monday .. 7=Sunday).")
            .defineInRange("schedule.dayOfWeek", 0, 0, 7);
    public static final ForgeConfigSpec.IntValue SCHEDULE_DAY_OF_MONTH = BUILDER
            .comment("Day of month for monthly scheduling (0=ignore, 1-28).")
            .defineInRange("schedule.dayOfMonth", 0, 0, 28);

    public static final ForgeConfigSpec.BooleanValue USE_TITLES = BUILDER
            .comment("Show announcements as on-screen titles in addition to chat.")
            .define("announce.useOnScreenTitles", true);
    public static final ForgeConfigSpec.ConfigValue<String> ANNOUNCE_SOUND = BUILDER
            .comment("Sound played to everyone on major announcements (resource location, empty to disable).")
            .define("announce.sound", "minecraft:ui.toast.challenge_complete");
    public static final ForgeConfigSpec.ConfigValue<String> MSG_SIGNUP_OPEN = BUILDER
            .define("announce.signupOpen", "&c[Raid] &fSign-ups are OPEN for {name}! Talk to {npc} or use /rg raid join {name}.");
    public static final ForgeConfigSpec.ConfigValue<String> MSG_STARTING = BUILDER
            .define("announce.starting", "&6[Raid] &f{name} begins with {count} fighters!");
    public static final ForgeConfigSpec.ConfigValue<String> MSG_BOSS_SPAWN = BUILDER
            .define("announce.bossSpawn", "&4[Raid] &f{boss} has appeared with &c{health} &fHP!");
    public static final ForgeConfigSpec.ConfigValue<String> MSG_VICTORY = BUILDER
            .define("announce.victory", "&6[Raid] &f{boss} has been defeated! Top damage: &e{top} &f({top_percent}%)");
    public static final ForgeConfigSpec.ConfigValue<String> MSG_TIMEOUT = BUILDER
            .define("announce.timeout", "&c[Raid] &fTime is up. {boss} escaped. Better luck next time!");

    // --- Rewards (token format documented in RewardManager / Reward) ---
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PARTICIPANT_REWARDS = BUILDER
            .comment("Rewards for every player who dealt damage. Tokens: 'command:<cmd>', 'item:<id>:<count>',",
                    "'skill:<dmzSkillId>:<levels>', 'tp:<x> <y> <z>', 'message:<text>'.",
                    "Placeholders in command/message: %player% %damage% %damage_percent% %rank% %boss% %total_damage%.")
            .defineListAllowEmpty("rewards.participant",
                    List.of("command:give %player% minecraft:diamond 2",
                            "message:&aYou dealt %damage_percent%% of the damage (rank #%rank%)!"),
                    Config::isString);
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> TOP_REWARDS = BUILDER
            .comment("Extra rewards for the #1 damage dealer (MVP). Same token/placeholder format as participant rewards.")
            .defineListAllowEmpty("rewards.topDamage",
                    List.of("command:give %player% minecraft:netherite_ingot 1",
                            "message:&6You dealt the most damage, %damage_percent%%!"),
                    Config::isString);
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> RUNNERUP_REWARDS = BUILDER
            .comment("Extra rewards for the runner-up damage dealers (ranks 2-4). Same token/placeholder format as participant rewards.")
            .defineListAllowEmpty("rewards.runnerUp",
                    List.of("command:give %player% minecraft:gold_ingot 3",
                            "message:&eRunner-up! You placed #%rank% with %damage_percent%%."),
                    Config::isString);

    public static final ForgeConfigSpec.BooleanValue ZSOUL_ENABLE = BUILDER
            .comment("Master switch for the Z-Soul stat-cap system (worn in the z_souls Curios slot).")
            .define("zsoul.enable", true);
    public static final ForgeConfigSpec.IntValue ZSOUL_SLOT_COUNT = BUILDER
            .comment("How many Z-Souls a player can wear at once (size of the z_souls Curios slot).")
            .defineInRange("zsoul.slotCount", 6, 1, 6);
    public static final ForgeConfigSpec.DoubleValue ZSOUL_BRONZE_PERCENT = BUILDER
            .comment("Bronze Z-Soul: how far a stat may be pushed past the global cap, as a % of the cap.",
                    "No upper ceiling on these percentages: the operator sets them, and a hidden bound only",
                    "ever surfaces as 'the number I typed is not the number that was saved'.")
            .defineInRange("zsoul.bronzePercent", 25.0, 0.0, Double.MAX_VALUE);
    public static final ForgeConfigSpec.DoubleValue ZSOUL_SILVER_PERCENT = BUILDER
            .comment("Silver Z-Soul: extra cap headroom as a % of the global cap.")
            .defineInRange("zsoul.silverPercent", 50.0, 0.0, Double.MAX_VALUE);
    public static final ForgeConfigSpec.DoubleValue ZSOUL_GOLD_PERCENT = BUILDER
            .comment("Gold Z-Soul: extra cap headroom as a % of the global cap.")
            .defineInRange("zsoul.goldPercent", 76.0, 0.0, Double.MAX_VALUE);
    public static final ForgeConfigSpec.DoubleValue ZSOUL_PRISMATIC_PERCENT = BUILDER
            .comment("Prismatic Z-Soul (top tier): extra cap headroom as a % of the global cap (100% = double the cap).")
            .defineInRange("zsoul.prismaticPercent", 100.0, 0.0, Double.MAX_VALUE);
    public static final ForgeConfigSpec.BooleanValue ZSOUL_AUTO_CONVERT = BUILDER
            .comment("If true, training points are automatically converted into beyond-cap growth while a",
                    "soul is worn and its base stat is already at the global cap. If false, players must use",
                    "'/rg raid zsoul invest <stat> <points>' to spend training points on beyond-cap growth.")
            .define("zsoul.autoConvertTrainingPoints", true);
    public static final ForgeConfigSpec.DoubleValue ZSOUL_TP_PER_POINT = BUILDER
            .comment("Training points spent per 1 point of beyond-cap stat growth.")
            .defineInRange("zsoul.trainingPointsPerPoint", 1.0, 0.01, 100000.0);
    public static final ForgeConfigSpec.IntValue ZSOUL_GROWTH_PER_SECOND = BUILDER
            .comment("Maximum beyond-cap points auto-converted per second, per stat (when autoConvert is on).")
            .defineInRange("zsoul.autoGrowthPerSecond", 25, 1, 1_000_000);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    private static boolean isString(final Object obj) {
        return obj instanceof String;
    }

    public static volatile boolean loaded = false;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        // Fires for EVERY spec this mod registers, so guard by spec: "loaded" must track OUR spec.
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        loaded = true;
    }
}
