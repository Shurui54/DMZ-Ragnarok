package net.shurui.shuruisutilities.racing.physics;

/**
 * The mutable per-bike kart-physics state that {@link KartPhysics} carries between ticks. One instance lives on each
 * racing {@code HoverbikeEntity} (transient, never saved): on the rider's client for a human racer, and on the
 * server for a riderless bot bike. It holds the scalar speed and the direction of travel (which lags the heading so
 * a drift slides), the drift and boost bookkeeping, the pre-start countdown for the rocket start, and the timers for
 * the physics effects a hit applies (packet 112). Purely physics; the cosmetic {@link RaceFx} bits are computed
 * fresh each step into {@link #fxFlags} for the renderer.
 */
public final class KartState
{
    /** Signed scalar speed, blocks/tick (negative is reverse). */
    public double speed;
    /** Direction of travel in Minecraft yaw degrees; grip lerps it toward the heading each tick. */
    public double velYaw;
    /** Set true on the first step so {@link #velYaw} seeds from the current heading rather than 0. */
    public boolean initialized;

    // --- drift ---
    public boolean drifting;
    /** The steer sign the drift locked in at start (-1 or +1); the drift keeps turning that way. */
    public int driftDir;
    /** Ticks the current drift has charged (maps to the mini-turbo tier on release). */
    public int driftCharge;

    // --- boost (mini-turbo, pad, or effect) ---
    public int boostTicks;
    public double boostMult = 1.0;
    /**
     * A sustained per-bike top-speed multiplier the powerups fold in outside the transient boost (R7): the Zeni
     * bonus (+1% top speed per zeni, capped +10%). 1.0 = no bonus. Set from the racer's zeni on the server (a bot
     * bike) or from the synced count on the rider client; the anti-cheat allows for it separately.
     */
    public double topSpeedMult = 1.0;

    // --- rocket start ---
    /** Frozen pre-GO countdown, ticks; 0 = racing. */
    public int preStart;
    /** Accelerate held inside the rocket window before GO. */
    public boolean rocketPrimed;
    /** Accelerate held too early (before the window): a short stall at GO. */
    public boolean rocketFailed;
    /** Post-start stall countdown from a failed rocket start. */
    public int stallTicks;

    // --- effects (packet 112) ---
    public int spinOutTicks;
    public int frozenTicks;
    public int squashTicks;
    public int launchTicks;
    public int autopilotTicks;

    // --- self powerup effect timers (R7): purely visual + Destroyer's speed / off-road override ---
    /** Destroyer Aura window (Star): while > 0 the bike is faster (x1.25) and ignores off-road; purple aura. */
    public int destroyerTicks;
    /** Kaioken red aura flash (x3): a short flash on each use. */
    public int kaiokenFlashTicks;
    /** Kaioken x20 window: the stronger red/gold aura for as long as the x20 window runs. */
    public int kaiokenX20Ticks;
    /** Afterimage window (Boo): the user's own client draws a ghosted self plus fading trailing copies. */
    public int afterimageTicks;

    /** The cosmetic FX bits for this tick (recomputed by {@link KartPhysics#step}). */
    public int fxFlags;

    /** Clear every transient effect (packet 112 CLEAR, or race teardown). */
    public void clearEffects()
    {
        spinOutTicks = 0;
        frozenTicks = 0;
        squashTicks = 0;
        launchTicks = 0;
        autopilotTicks = 0;
        destroyerTicks = 0;
        kaiokenFlashTicks = 0;
        kaiokenX20Ticks = 0;
        afterimageTicks = 0;
    }
}
