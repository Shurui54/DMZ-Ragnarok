package net.shurui.shuruisutilities.racing.physics;

/**
 * The cosmetic FX bit flags for a race bike, packed into the synched {@code DATA_RACE_FX} int on
 * {@code HoverbikeEntity} and carried by the effect packet (112). Purely a rendering signal: the server sets the
 * bits, the client draws the matching aura, trail or squash on the bike. Never a gameplay input (physics reads
 * {@link RaceDriveParams}, not these).
 *
 * <p>Bit VALUES are the wire contract, so they are pinned: append new flags at higher bits, never renumber. An int
 * holds thirty-two, well past the current set.
 */
public final class RaceFx
{
    private RaceFx() {}

    /** Blue drift-charge sparks (mini-turbo tier 1). */
    public static final int DRIFT_BLUE = 1 << 0;
    /** Orange drift-charge sparks (mini-turbo tier 2). */
    public static final int DRIFT_ORANGE = 1 << 1;
    /** Purple drift-charge sparks (mini-turbo tier 3). */
    public static final int DRIFT_PURPLE = 1 << 2;
    /** A boost is active (mini-turbo, pad, Senzu or Kaioken burst): the exhaust/streak layer. */
    public static final int BOOST = 1 << 3;
    /** Rocket-start glow while the countdown accelerate window is open. */
    public static final int ROCKET_READY = 1 << 4;

    /** Destroyer Aura (Star): the purple god-of-destruction aura, invincible. */
    public static final int DESTROYER_AURA = 1 << 5;
    /** Kaioken red aura (x3). */
    public static final int KAIOKEN = 1 << 6;
    /** Kaioken x20 stronger red/gold aura. */
    public static final int KAIOKEN_X20 = 1 << 7;
    /** Flying Nimbus under the bike (Bullet Bill autopilot). */
    public static final int NIMBUS = 1 << 8;
    /** Afterimage: the user sees a ghosted self, others see nothing (Boo). */
    public static final int AFTERIMAGE = 1 << 9;

    /** Spun out (hit by a shell, mine, Saibaman or Kiai): the tumble animation. */
    public static final int SPINOUT = 1 << 10;
    /** Squashed flat (Gravity Crush): render scaled down. */
    public static final int SQUASH = 1 << 11;
    /** Frozen on the grid or by a Spirit Bomb stun. */
    public static final int FROZEN = 1 << 12;
    /** Blinded by Solar Flare: the white overlay is driven by 112, this marks the source bike. */
    public static final int BLINDED = 1 << 13;

    /** True when any of the given bits are set in the packed flags. */
    public static boolean has(int flags, int bit)
    {
        return (flags & bit) != 0;
    }
}
