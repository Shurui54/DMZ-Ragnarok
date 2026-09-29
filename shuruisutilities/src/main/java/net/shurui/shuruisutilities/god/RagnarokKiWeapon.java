package net.shurui.shuruisutilities.god;

/**
 * The ki weapons this mod adds to DMZ's own set.
 *
 * <p>THESE ARE NOT ITEMS. A DMZ ki weapon is a TYPE, held as player state and drawn by DMZ's own renderer from a geo
 * model; {@code PlayerAttackHelper.isKiWeaponActive} requires the main hand to be EMPTY and the {@code kimanipulation}
 * skill to be active. An earlier version of these shipped as real ItemStacks, which put something in the hand and so
 * switched DMZ's ki weapon system off entirely - that is why the models broke when swung, had no weapon sounds, took
 * item display transforms and rotated through the arm.
 *
 * <p>The {@link #type} is the whole identity: DMZ resolves the model from
 * {@code assets/dragonminez/geo/weapons/kiweapon_<type>.geo.json} and the texture alongside it, so shipping those
 * two files under DMZ's namespace is what makes a new weapon exist.
 *
 * <p>Six keyblades used to sit beside the staff. They were pulled while their rendering was still wrong; every
 * mixin around this enum iterates {@link #values()}, so putting them back is adding the constants and their two
 * asset files, plus the wheel category that grouped them.
 */
public enum RagnarokKiWeapon
{
    /** The Angel's staff. Only the current Angel may select it. */
    ANGEL_STAFF("angelstaff", "Angel's Staff");

    /** DMZ ki weapon type id. PERSISTED as the player's selected weapon, so never rename. */
    public final String type;
    public final String displayName;

    RagnarokKiWeapon(String type, String displayName)
    {
        this.type = type;
        this.displayName = displayName;
    }
}
