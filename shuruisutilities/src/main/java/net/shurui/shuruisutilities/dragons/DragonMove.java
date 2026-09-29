package net.shurui.shuruisutilities.dragons;

/**
 * The shadow dragon signature moves, one per dragon, plus Omega's own.
 *
 * <p>Each is registered into DMZ's technique registry so it appears in the skill menu, equips to a slot and gets
 * DMZ's cast time, cooldown, colours and ANIMATIONS for free (see {@code DragonTechniqueDefs}). Everything the move
 * actually DOES is ours: DMZ's technique data can only express a projectile shape, damage or healing, and a
 * buff/debuff on a DMZ stat, so freezing, burning, tornadoes, gas clouds and lightning are implemented in
 * {@code DragonMoveEffects}.
 *
 * <p>The {@link #race} is the DMZ race id that may use the move. DMZ enforces this itself through
 * {@code KiAttackData.setAllowedRaces}, so the gating is native rather than something we police at cast time. The
 * base {@code shadow_dragon} race is Omega and is added to EVERY move's allowed list, which is how "transforming
 * into Omega gives you all the other dragons' abilities" is implemented.
 *
 * <p>The dragon-to-star mapping is not guesswork: it is the mapping the race lang file already ships
 * ({@code race.dragonminez.shadow_dragon_3star} = "Eis Shenron", and so on), which matches canon.
 */
public enum DragonMove
{
    /** Haze Shenron, 2 stars. A cloud of toxic gas that damages and blinds. */
    POLLUTION("shadow_pollution", "Pollution", "shadow_dragon_2star",
            0x8A2BE2, 0x4B0082, 0x2A0044, "ki.masenko"),

    /** Eis Shenron, 3 stars. Encases everyone nearby in ice and ticks freeze damage. */
    ABSOLUTE_ZERO("shadow_absolute_zero", "Absolute Zero", "shadow_dragon_3star",
            0x9FE7FF, 0x2A6FA8, 0x0A3A66, "ki.finalflash"),

    /** Nuova Shenron, 4 stars. Burns everything within a radius of the caster. */
    SOLAR_FLARE("shadow_solar_flare", "Nova Heat", "shadow_dragon_4star",
            0xFFB44D, 0xE0500A, 0x8A2A00, "ki.explosion"),

    /** Rage Shenron, 5 stars. Lightning on everyone nearby, slowing and ticking electric damage. */
    DRAGON_THUNDER("shadow_dragon_thunder", "Dragon Thunder", "shadow_dragon_5star",
            0xFFF6A8, 0xFFD21A, 0xB8860B, "ki.galick"),

    /** Oceanus Shenron, 6 stars. A tornado that swallows entities and projectiles and throws them out. */
    HURRICANE_FURY("shadow_hurricane_fury", "Mighty Hurricane Fury", "shadow_dragon_6star",
            0xEAFBFF, 0xBEE8F7, 0x7FC4DE, "ki.kienzandoble"),

    /** Naturon Shenron, 7 stars. An earthquake: the ground heaves, and everything in it is damaged and slowed. */
    DRAGON_QUAKE("shadow_dragon_quake", "Dragon Quake", "shadow_dragon_7star",
            0xC8FF9B, 0x3F8F1E, 0x1B4A0A, "ki.bigbang"),

    /**
     * Omega Shenron, the base race. A large ball with a BLACK core and a RED outline, wrapped in blood red
     * lightning, that applies every other dragon's effect at once.
     *
     * <p>The colours are the move: interior black, exterior and outline red. {@code supernova} is the animation
     * because it is DMZ's own big overhead-ball wind-up, which is the pose this move wants.
     */
    MINUS_ENERGY_POWER_BALL("shadow_minus_energy_ball", "Minus Energy Power Ball",
            DragonRaces.OMEGA_RACE, 0x000000, 0xE00018, 0x8A0010, "ki.large_ball");

    /** Stable technique id. PERSISTED: a player's unlocked/equipped technique rows key on this, so never rename. */
    public final String id;
    /** Menu name. */
    public final String displayName;
    /** The DMZ race id that owns this move. Omega ({@link DragonRaces#OMEGA_RACE}) may use them all. */
    public final String race;
    /** Interior (core) colour. */
    public final int colorInterior;
    /** Exterior colour. */
    public final int colorExterior;
    /** Outline colour, kept separate from the exterior so a move can have a distinct rim. */
    public final int colorOutline;
    /**
     * DMZ technique animation PREFIX, driving the charge-up and firing clips.
     *
     * <p>MUST be one of DMZ's clip prefixes from {@code animations/entity/races/ki.animation.json} - DMZ appends
     * {@code _cast} and {@code _fire} to it, so {@code ki.kameha} resolves to {@code ki.kameha_cast} and
     * {@code ki.kameha_fire}. A TECHNIQUE ID is not an animation prefix: setting {@code "kamehameha"} here makes
     * DMZ look for {@code kamehameha_cast}, which does not exist, and the move plays nothing.
     *
     * <p>ONE PER MOVE, deliberately. The whole set used to share {@code ki.explosion}, which made six different
     * dragons wind up identically. The full list of clips DMZ ships is barrage, bigbang, explosion, finalflash,
     * galick, kameha, kienzan, kienzandoble, large_ball, makkako and masenko; only solarflare is fire-only and so
     * cannot be used for a charge.
     */
    public final String animation;

    DragonMove(String id, String displayName, String race,
               int colorInterior, int colorExterior, int colorOutline, String animation)
    {
        this.id = id;
        this.displayName = displayName;
        this.race = race;
        this.colorInterior = colorInterior;
        this.colorExterior = colorExterior;
        this.colorOutline = colorOutline;
        this.animation = animation;
    }

    /**
     * The move belonging to a shadow dragon BOSS slot, or null for a slot outside 1..7.
     *
     * <p>Slot index follows the star count, matching the races: slot 1 is the one-star dragon, which is Omega, and
     * slots 2..7 are Haze, Eis, Nuova, Rage, Oceanus and Naturon in order. Each boss gets ONLY its own dragon's
     * move; unlike a player who transforms into Omega, a boss does not inherit the others.
     */
    public static DragonMove bySlot(int slot)
    {
        return switch (slot)
        {
            case 1 -> MINUS_ENERGY_POWER_BALL;
            case 2 -> POLLUTION;
            case 3 -> ABSOLUTE_ZERO;
            case 4 -> SOLAR_FLARE;
            case 5 -> DRAGON_THUNDER;
            case 6 -> HURRICANE_FURY;
            case 7 -> DRAGON_QUAKE;
            default -> null;
        };
    }

    /**
     * The radius, in blocks, that this move's effect actually reaches.
     *
     * <p>Read from each move's own {@code RADIUS} rather than restated here, so the charge orb can be sized to the
     * area it stands for and the two cannot drift apart. Omega's is the splash radius of its ball, which is a
     * detonation rather than a caster-centred area, so it is NOT what sizes its projectile.
     */
    public double effectRadius()
    {
        return switch (this)
        {
            case POLLUTION -> DragonMoveHaze.RADIUS;
            case ABSOLUTE_ZERO -> DragonMoveEis.RADIUS;
            case SOLAR_FLARE -> DragonMoveNuova.RADIUS;
            case DRAGON_THUNDER -> DragonMoveRage.RADIUS;
            case HURRICANE_FURY -> DragonMoveOceanus.RADIUS;
            case DRAGON_QUAKE -> DragonMoveQuake.RADIUS;
            case MINUS_ENERGY_POWER_BALL -> DragonMoveOmega.RADIUS;
        };
    }

    /** Resolve by technique id, or null when the id is not one of ours. */
    public static DragonMove byId(String techniqueId)
    {
        if (techniqueId == null)
            return null;
        for (DragonMove m : values())
            if (m.id.equals(techniqueId))
                return m;
        return null;
    }
}
