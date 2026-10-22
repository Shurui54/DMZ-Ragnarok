package net.shurui.dev.sdu;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue REQUIRE_OP_TO_EDIT = BUILDER
            .comment("If true, only operators (or the NPC's owner) may open the NPC editor and change stats/abilities.")
            .define("requireOpToEdit", true);

    private static final ForgeConfigSpec.IntValue MAX_NPC_HEALTH = BUILDER
            .comment("Upper bound the Options screen offers for an NPC's max health. Raised to effectively remove",
                    "the ceiling: the vanilla 1024 attribute cap is already lifted by RangedAttributeMixin, and",
                    "no editor actually clamps per-NPC health to this, so huge values take effect.")
            .defineInRange("maxNpcHealth", 1_000_000_000, 1, Integer.MAX_VALUE);

    private static final ForgeConfigSpec.BooleanValue ENABLE_DMZ_INTEGRATION = BUILDER
            .comment("If true, scale NPC combat stats/damage through DragonMine Z's ki-stat system when DMZ is present.")
            .define("enableDmzIntegration", true);

    private static final ForgeConfigSpec.BooleanValue ENABLE_AURA_STACKING = BUILDER
            .comment("If true, render a form's extra 'aura layers' (the Aura tab) on top of DMZ's aura.",
                    "Set false as a safety switch if stacked auras ever misbehave when transforming.")
            .define("enableAuraStacking", true);

    private static final ForgeConfigSpec.IntValue BARRIER_DEFAULT_LEVEL = BUILDER
            .comment("Default required DMZ level a freshly-placed Level Barrier block gates to. An op can then",
                    "right-click the block to set its gate to their own current level.")
            .defineInRange("barrierDefaultLevel", 100, 0, Integer.MAX_VALUE);

    private static final ForgeConfigSpec.IntValue CHAMBER_RADIUS = BUILDER
            .comment("Radius (in blocks, around the chamber block) of a Gravity Chamber's effect area when it has no",
                    "explicit WorldEdit region captured. Players training inside get the multiplier + shared pool.")
            .defineInRange("chamberRadius", 12, 1, 256);

    private static final ForgeConfigSpec.DoubleValue CHAMBER_MULTIPLIER = BUILDER
            .comment("Multiplier applied to a player's organic TP gain while training inside a Gravity Chamber area.")
            .defineInRange("chamberMultiplier", 1.5D, 1.0D, 1000.0D);

    private static final ForgeConfigSpec.DoubleValue CHAMBER_SHARE_FRACTION = BUILDER
            .comment("Fraction (0..1) of the post-multiplier TP gain awarded to EVERY OTHER player inside the SAME",
                    "Gravity Chamber area (shared training pool). 0 disables sharing.")
            .defineInRange("chamberShareFraction", 0.25D, 0.0D, 1.0D);

    private static final ForgeConfigSpec.DoubleValue CHAMBER_GRAVITY = BUILDER
            .comment("Extra gravity (DMZ gravity-room effect) applied to players inside a freshly-placed Gravity",
                    "Chamber's area. 1.0 = no gravity effect. Registers the chamber area as a DMZ gravity zone;",
                    "requires DMZ server config gravity.machineGravityEnabled = true for the effect to apply.")
            .defineInRange("chamberGravity", 1.0D, 1.0D, 10.0D);

    private static final ForgeConfigSpec.IntValue SHADOW_DUMMY_COOLDOWN_SECONDS = BUILDER
            .comment("Per-player cooldown (in seconds) between shadow training dummy summons. 0 disables the",
                    "cooldown rule entirely. Range 0-86400. Default 90.")
            .defineInRange("shadowDummyCooldownSeconds", 90, 0, 86_400);

    private static final ForgeConfigSpec.IntValue SHADOW_DUMMY_MAX_ALIVE_PER_PARTY = BUILDER
            .comment("Maximum number of live shadow training dummies a single party may have out at once. A",
                    "new summon is allowed while the party's live-dummy count is below this. Range 1-64. Default 1.")
            .defineInRange("shadowDummyMaxAlivePerParty", 1, 1, 64);

    private static final ForgeConfigSpec.IntValue PARTY_TP_FALLOFF_THRESHOLD = BUILDER
            .comment("Party sizes at or below this keep the full DMZ shared-TP amount. Beyond it, each extra",
                    "member reduces the shared amount by the step (down to the floor). Range 1-64. Default 3.")
            .defineInRange("partyTpFalloffThreshold", 3, 1, 64);

    private static final ForgeConfigSpec.IntValue PARTY_TP_FALLOFF_STEP_PERCENT = BUILDER
            .comment("Percent of the shared-TP removed per party member beyond the threshold (step = percent/100).",
                    "Range 0-100. Default 20 (0.20 per extra member).")
            .defineInRange("partyTpFalloffStepPercent", 20, 0, 100);

    private static final ForgeConfigSpec.IntValue PARTY_TP_FALLOFF_FLOOR_PERCENT = BUILDER
            .comment("Minimum shared-TP fraction, as a percent, no matter how large the party is (floor = percent/100).",
                    "Range 0-100. Default 20 (never below 0.20 of the share).")
            .defineInRange("partyTpFalloffFloorPercent", 20, 0, 100);

    // OUR imported worlds, not DMZ content. The travel gate used to read KeyGate.unlocked() ("not a
    // dedicated server OR the key is present"), so every singleplayer and LAN world walked straight in.
    // Now the gate wants a REAL key; a solo player who wants the worlds anyway flips this.
    // Default false. COMMON config, so on a dedicated server it is the operator's file, a client cannot
    // touch it, and the key check runs first regardless, so this true on a keyless dedicated server does nothing.
    private static final ForgeConfigSpec.BooleanValue ALLOW_PRIVATE_WORLDS_WITHOUT_KEY = BUILDER
            .comment("Allow travel to the private imported worlds (kaiow, namekow) on a world with no Shurui's Key.",
                    "Default false: without a key those two destinations are refused, in singleplayer and on LAN",
                    "as well as on a dedicated server. Set true to play them solo. Ignored on a dedicated server,",
                    "where the key decides.")
            .define("allowPrivateWorldsWithoutKey", false);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean requireOpToEdit;
    public static int maxNpcHealth;
    public static boolean enableDmzIntegration;
    public static boolean enableAuraStacking = true;
    public static int barrierDefaultLevel = 100;
    public static int chamberRadius = 12;
    public static double chamberMultiplier = 1.5D;
    public static double chamberShareFraction = 0.25D;
    public static double chamberGravity = 1.0D;
    public static int shadowDummyCooldownSeconds = 90;
    public static int shadowDummyMaxAlivePerParty = 1;
    public static int partyTpFalloffThreshold = 3;
    public static int partyTpFalloffStepPercent = 20;
    public static int partyTpFalloffFloorPercent = 20;
    public static boolean allowPrivateWorldsWithoutKey = false;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        // Fires for EVERY spec this mod registers (COMMON here plus ClientConfig's CLIENT), not just ours.
        // Reading a ConfigValue before its owning spec loads throws, so act only when OUR spec fired.
        // Comparing the spec (not the type) stays correct if another COMMON spec is added later.
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        bake();
    }

    /** Copy the spec's values into the static fields the code reads. Run on load and after an in-game edit. */
    public static void bake() {
        requireOpToEdit = REQUIRE_OP_TO_EDIT.get();
        maxNpcHealth = MAX_NPC_HEALTH.get();
        enableDmzIntegration = ENABLE_DMZ_INTEGRATION.get();
        enableAuraStacking = ENABLE_AURA_STACKING.get();
        barrierDefaultLevel = BARRIER_DEFAULT_LEVEL.get();
        chamberRadius = CHAMBER_RADIUS.get();
        chamberMultiplier = CHAMBER_MULTIPLIER.get();
        chamberShareFraction = CHAMBER_SHARE_FRACTION.get();
        chamberGravity = CHAMBER_GRAVITY.get();
        shadowDummyCooldownSeconds = SHADOW_DUMMY_COOLDOWN_SECONDS.get();
        shadowDummyMaxAlivePerParty = SHADOW_DUMMY_MAX_ALIVE_PER_PARTY.get();
        partyTpFalloffThreshold = PARTY_TP_FALLOFF_THRESHOLD.get();
        partyTpFalloffStepPercent = PARTY_TP_FALLOFF_STEP_PERCENT.get();
        partyTpFalloffFloorPercent = PARTY_TP_FALLOFF_FLOOR_PERCENT.get();
        allowPrivateWorldsWithoutKey = ALLOW_PRIVATE_WORLDS_WITHOUT_KEY.get();
    }

    /** The spec, for the in-game options editor (which saves through it, so the file keeps its comments). */
    public static ForgeConfigSpec spec() {
        return SPEC;
    }
}
