package net.shurui.shuruisutilities.god;

import net.shurui.shuruisutilities.energy.EnergyKind;

/**
 * The role abilities that are cast as DMZ techniques rather than from the ability key.
 *
 * <p>Split from the key because these two are AIMED: the sphere has to hit something, and hakai has to be pointed at
 * a target. The ability key is for the two self-buffs (the destruction aura, the angel state), which have nothing to
 * aim at. Registering these as techniques also gets them a slot, a cast time, a cooldown and an ANIMATION from DMZ.
 *
 * <p>UNLIKE THE DRAGON MOVES, THESE ARE NOT RACE-GATED. A shadow dragon's move is restricted by DMZ's own
 * {@code allowedRaces}, but a G.O.D. is a TITLE holder of any race, so DMZ has nothing to filter on. The gate is
 * therefore applied at cast time, in the dispatcher seam, by checking the title. That means the technique may be
 * visible to a player who cannot use it; refusing at cast is the only place the check can live.
 */
public enum RoleMove
{
    /**
     * Traps whoever it hits in a sphere of ki, draining them while it holds.
     *
     * <p>Uses DMZ's {@code final_flash} animation: a braced two-handed build-up, which is the pose this move wants.
     */
    KI_PRISON("god_ki_prison", "Sphere of Destruction", EnergyKind.DESTRUCTION,
            0xB44BFF, 0x5A0A8A, "ki.finalflash"),

    /**
     * Erases what it is used on. Full bar, and an eight hour real-world cooldown.
     *
     * <p>Animation is {@code big_bang}, matching Shurui's Hakai, which is cloned from that technique and holds its
     * charge pose. The two share their PRESENTATION deliberately so both read as the same act of erasure; they share
     * no code and no consequence, and this one never bans.
     */
    HAKAI("god_hakai", "Hakai", EnergyKind.DESTRUCTION,
            0x9B30FF, 0x3A0A5A, "ki.bigbang");

    /** Stable technique id. PERSISTED on player technique rows, so never rename. */
    public final String id;
    public final String displayName;
    /** The bar this move spends, and therefore the role that may cast it. */
    public final EnergyKind kind;
    public final int colorInterior;
    public final int colorExterior;
    /** DMZ technique animation PREFIX (DMZ appends _cast/_fire). See {@code DragonMove#animation}. */
    public final String animation;

    RoleMove(String id, String displayName, EnergyKind kind, int colorInterior, int colorExterior, String animation)
    {
        this.id = id;
        this.displayName = displayName;
        this.kind = kind;
        this.colorInterior = colorInterior;
        this.colorExterior = colorExterior;
        this.animation = animation;
    }

    public static RoleMove byId(String techniqueId)
    {
        if (techniqueId == null)
            return null;
        for (RoleMove m : values())
            if (m.id.equals(techniqueId))
                return m;
        return null;
    }
}
