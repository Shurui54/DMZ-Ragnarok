package net.shurui.shuruisutilities.racing.physics;

import net.minecraft.network.FriendlyByteBuf;

/**
 * The pure kart-physics numbers the server hands the rider client at race start (packet 109). Every racing bike
 * drives on THESE, not on the bike's own {@code DATA_SPEED} / variant, so all bikes handle identically while
 * racing (owner decision: "very very similar to Mario Kart"). The bike variant stays purely cosmetic.
 *
 * <p>The defaults (R4) are tuned to feel like Mario Kart 150cc scaled to Minecraft: a flat-ground top around 1.0
 * block/tick (~20 blocks/s), a couple of seconds to reach it, snappy low-speed steering that tightens off at speed,
 * and mini-turbos in the same blue / orange / purple tiers. {@code RaceTuningStore} (R6/R10) lets an operator edit
 * them per server / track. All units are blocks per tick and per-tick fractions unless noted.
 */
public final class RaceDriveParams
{
    /** Flat-ground top scalar speed, blocks/tick (~1.0 b/t = ~20 b/s, MK 150cc feel; the plan's ~0.9-1.1 band). */
    public double topSpeed = 1.0;
    /** Acceleration toward the target speed, blocks/tick^2 (reaches top in ~1.8 s from a standstill). */
    public double accel = 0.028;
    /** Braking / reverse deceleration, blocks/tick^2. */
    public double brake = 0.06;
    /** Top reverse speed as a fraction of {@link #topSpeed}. */
    public double reverseFraction = 0.35;

    /** Steer yaw rate at a standstill, degrees/tick (falls toward {@link #steerRateHigh} with speed). */
    public double steerRateLow = 6.0;
    /** Steer yaw rate at top speed, degrees/tick (kept tighter than a full-lock hairpin so a straight stays stable). */
    public double steerRateHigh = 3.2;
    /** How hard velocity is lerped toward the heading each tick (grip); 1.0 = no slide, lower = looser (MK karts grip). */
    public double grip = 0.55;

    /** Minimum speed fraction of {@link #topSpeed} before a hop-and-steer becomes a drift. */
    public double driftMinFraction = 0.5;
    /** Grip multiplier while drifting (looser, so the tail slides out; grip * this = the drift grip). */
    public double driftGripFactor = 0.4;
    /** Upward hop impulse, blocks/tick (the little jump that starts a drift). */
    public double hopImpulse = 0.42;

    /** Off-road top-speed multiplier (block under the bike not in the track surface set), unless boosting. */
    public double offroadMult = 0.55;
    /** Speed multiplier applied while a boost pad, mini-turbo or rocket-start boost is active. */
    public double boostMult = 1.4;
    /** Speed retained after bumping a wall or road edge. */
    public double wallBumpFactor = 0.7;

    /** Mini-turbo boost multiplier per drift tier (index 0 unused; 1 blue, 2 orange, 3 purple). */
    public double[] miniTurboMult = { 1.0, 1.12, 1.25, 1.4 };
    /** Mini-turbo boost duration per drift tier, ticks (index 0 unused; blue 12, orange 22, purple 34). */
    public int[] miniTurboTicks = { 0, 12, 22, 34 };

    /** Ticks before GO during which holding accelerate arms a rocket start (too early stalls). */
    public int rocketStartWindow = 10;

    public RaceDriveParams() {}

    /** A deep copy, so a per-track override never aliases the global tuning's array fields. */
    public RaceDriveParams copy()
    {
        RaceDriveParams p = new RaceDriveParams();
        p.topSpeed = topSpeed;
        p.accel = accel;
        p.brake = brake;
        p.reverseFraction = reverseFraction;
        p.steerRateLow = steerRateLow;
        p.steerRateHigh = steerRateHigh;
        p.grip = grip;
        p.driftMinFraction = driftMinFraction;
        p.driftGripFactor = driftGripFactor;
        p.hopImpulse = hopImpulse;
        p.offroadMult = offroadMult;
        p.boostMult = boostMult;
        p.wallBumpFactor = wallBumpFactor;
        p.miniTurboMult = miniTurboMult == null ? null : miniTurboMult.clone();
        p.miniTurboTicks = miniTurboTicks == null ? null : miniTurboTicks.clone();
        p.rocketStartWindow = rocketStartWindow;
        return p;
    }

    /** Write the full parameter block. Fixed field order is the wire contract. */
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeDouble(topSpeed);
        buf.writeDouble(accel);
        buf.writeDouble(brake);
        buf.writeDouble(reverseFraction);
        buf.writeDouble(steerRateLow);
        buf.writeDouble(steerRateHigh);
        buf.writeDouble(grip);
        buf.writeDouble(driftMinFraction);
        buf.writeDouble(driftGripFactor);
        buf.writeDouble(hopImpulse);
        buf.writeDouble(offroadMult);
        buf.writeDouble(boostMult);
        buf.writeDouble(wallBumpFactor);
        writeDoubles(buf, miniTurboMult);
        writeInts(buf, miniTurboTicks);
        buf.writeVarInt(rocketStartWindow);
    }

    public static RaceDriveParams decode(FriendlyByteBuf buf)
    {
        RaceDriveParams p = new RaceDriveParams();
        p.topSpeed = buf.readDouble();
        p.accel = buf.readDouble();
        p.brake = buf.readDouble();
        p.reverseFraction = buf.readDouble();
        p.steerRateLow = buf.readDouble();
        p.steerRateHigh = buf.readDouble();
        p.grip = buf.readDouble();
        p.driftMinFraction = buf.readDouble();
        p.driftGripFactor = buf.readDouble();
        p.hopImpulse = buf.readDouble();
        p.offroadMult = buf.readDouble();
        p.boostMult = buf.readDouble();
        p.wallBumpFactor = buf.readDouble();
        p.miniTurboMult = readDoubles(buf);
        p.miniTurboTicks = readInts(buf);
        p.rocketStartWindow = buf.readVarInt();
        return p;
    }

    private static void writeDoubles(FriendlyByteBuf buf, double[] a)
    {
        buf.writeVarInt(a.length);
        for (double v : a)
            buf.writeDouble(v);
    }

    private static double[] readDoubles(FriendlyByteBuf buf)
    {
        int n = buf.readVarInt();
        double[] a = new double[n];
        for (int i = 0; i < n; i++)
            a[i] = buf.readDouble();
        return a;
    }

    private static void writeInts(FriendlyByteBuf buf, int[] a)
    {
        buf.writeVarInt(a.length);
        for (int v : a)
            buf.writeVarInt(v);
    }

    private static int[] readInts(FriendlyByteBuf buf)
    {
        int n = buf.readVarInt();
        int[] a = new int[n];
        for (int i = 0; i < n; i++)
            a[i] = buf.readVarInt();
        return a;
    }
}
