package net.shurui.shuruisutilities.racing.physics;

/**
 * The sixteen race powerups, each a Dragon Ball recreation of a Mario Kart item (mapping approved by the owner,
 * see the design notes section 3). The ORDINALS are the wire and NBT contract: a synched byte on the roulette
 * packet (111), the item slot, and any stored odds table key, so they are PINNED. Never reorder and never insert
 * in the middle, only append (127 is the ceiling the packet block leaves for later kinds).
 *
 * <p>The order below is the plan's table order (Star first, Coins last). {@link #NONE} is the empty slot and is
 * pinned LAST so the sixteen real kinds keep ordinals 0..15; a client with no held item reads {@link #NONE}.
 */
public enum PowerupKind
{
    /** Star: the god of destruction aura. Invincible, faster, knocks racers aside. */
    DESTROYER_AURA,
    /** Mushroom: a Senzu bean burst of speed. */
    SENZU,
    /** Triple mushroom: three Senzu-strength charges (Kaioken x3). */
    KAIOKEN_X3,
    /** Golden mushroom: a window of unlimited boosts (Kaioken x20). */
    KAIOKEN_X20,
    /** Green shell: a straight, edge-bouncing Ki Blast. */
    KI_BLAST,
    /** Red shell: a homing Hellzone Grenade (triple = orbit shields). */
    HELLZONE,
    /** Blue shell: a Spirit Bomb that drops on the leader. */
    SPIRIT_BOMB,
    /** Banana: a dropped Ki Mine. */
    KI_MINE,
    /** Bob-omb: a Saibaman that runs the centreline and self-destructs. */
    SAIBAMAN,
    /** Fake item box: a Fake Dragon Ball mine. */
    FAKE_BALL,
    /** Lightning: Gravity Crush 100x, squashing every other racer. */
    GRAVITY_CRUSH,
    /** Blooper: Solar Flare, blinding racers ahead. */
    SOLAR_FLARE,
    /** Bullet Bill: the Flying Nimbus autopilot dash. */
    NIMBUS,
    /** Boo: Afterimage, invisibility plus an item steal. */
    AFTERIMAGE,
    /** Super Horn: a Kiai shockwave that clears nearby racers and projectiles. */
    KIAI,
    /** Coins: Zeni, a small stackable top-speed bonus. */
    ZENI,

    /** The empty slot: no held item. Pinned LAST so the sixteen real kinds keep ordinals 0..15. */
    NONE;

    /** The pinned ordinal as a byte, for the synched slot and any packet. */
    public byte id()
    {
        return (byte) ordinal();
    }

    /** Resolve a pinned ordinal back to a kind, clamped to {@link #NONE} on any out-of-range value (never throws). */
    public static PowerupKind byId(int id)
    {
        PowerupKind[] all = values();
        return id >= 0 && id < all.length ? all[id] : NONE;
    }

    /** Whether this is a real, usable powerup (everything but {@link #NONE}). */
    public boolean isItem()
    {
        return this != NONE;
    }
}
