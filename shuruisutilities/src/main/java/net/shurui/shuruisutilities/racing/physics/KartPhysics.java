package net.shurui.shuruisutilities.racing.physics;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The pure kart-physics step. One call advances a {@link KartState} by one tick from the rider's (or bot's) input and
 * the local environment, and returns what the entity should apply: the horizontal velocity, a heading yaw delta, a
 * vertical hop impulse, the cosmetic {@link RaceFx} bits, and the drift start / release signals the client turns into
 * packet 115. It reads ONLY the shared {@link RaceDriveParams} (identical for every racing bike, so a race ignores
 * the bike's own {@code DATA_SPEED} and variant), never the world; the caller resolves the environment flags and
 * applies the movement (so the same function runs on the rider's client and on the server for a bot).
 *
 * <p>Model: a scalar speed plus a heading (the bike yaw) and a direction of travel ({@link KartState#velYaw}) that
 * grip lerps toward the heading each tick; while drifting the grip is looser so the kart slides, and the built-up
 * {@link KartState#driftCharge} grants a mini-turbo on release. A/D steer (yaw rate falls with speed), W accelerates,
 * S brakes then reverses, jump hops (and starts a drift if steering fast enough), boost pads and mini-turbos raise
 * the speed cap and suppress the off-road penalty, and a wall bump keeps {@link RaceDriveParams#wallBumpFactor} of
 * the speed. Effects (spin-out, squash, launch, frozen, autopilot) override control while their timers run.
 */
public final class KartPhysics
{
    private KartPhysics() {}

    /** One tick of rider (or bot) input. {@code jumpPressed} is the rising edge; {@code jumpHeld} the held state. */
    public record Input(boolean up, boolean down, boolean left, boolean right, boolean jumpHeld, boolean jumpPressed,
                        boolean grounded) {}

    /** The local environment the caller resolved for this tick. */
    public record Env(boolean onSurface, boolean onBoostPad, boolean collided) {}

    /** What the entity applies after a step. */
    public record Output(Vec3 horizontal, double yawDelta, double hopImpulse, int fxFlags, boolean startDrift,
                         boolean releaseDrift, int releaseTier) {}

    /** The mini-turbo tier the accumulated drift charge has reached (0 none, 1 blue, 2 orange, 3 purple). */
    public static int driftTier(int charge, RaceDriveParams p)
    {
        int[] t = p.miniTurboTicks;
        if (t.length > 3 && charge >= t[3])
            return 3;
        if (t.length > 2 && charge >= t[2])
            return 2;
        if (t.length > 1 && charge >= t[1])
            return 1;
        return 0;
    }

    public static Output step(KartState st, Input in, Env env, RaceDriveParams p, double currentYaw)
    {
        if (!st.initialized)
        {
            st.velYaw = currentYaw;
            st.initialized = true;
        }

        // --- pre-start countdown (rocket start): frozen on the grid, accelerate timing decides the launch ---
        if (st.preStart > 0)
        {
            st.preStart--;
            if (in.up())
            {
                if (st.preStart < p.rocketStartWindow)
                    st.rocketPrimed = true;
                else
                    st.rocketFailed = true;
            }
            int fxPre = RaceFx.FROZEN;
            if (st.rocketPrimed && !st.rocketFailed)
                fxPre |= RaceFx.ROCKET_READY;
            if (st.preStart == 0)
            {
                if (st.rocketPrimed && !st.rocketFailed)
                {
                    st.boostTicks = Math.max(st.boostTicks, 20);
                    st.boostMult = Math.max(st.boostMult, p.boostMult);
                }
                else if (st.rocketFailed)
                {
                    st.stallTicks = 20;
                }
            }
            st.speed = 0;
            return new Output(Vec3.ZERO, 0, 0, fxPre, false, false, 0);
        }

        int fx = 0;

        // --- frozen (Spirit Bomb stun): no control ---
        if (st.frozenTicks > 0)
        {
            st.frozenTicks--;
            st.speed *= 0.5;
            return new Output(headingVel(st.velYaw, st.speed), 0, 0, RaceFx.FROZEN, false, false, 0);
        }

        boolean controllable = st.spinOutTicks <= 0;

        // wall bump: the caller passes last tick's collision; keep only wallBumpFactor of the speed.
        if (env.collided())
            st.speed *= p.wallBumpFactor;

        // --- speed cap with boost / squash / off-road modifiers ---
        // Destroyer Aura counts as "boosting" for the off-road suppression (Star ignores off-road).
        boolean boosting = st.boostTicks > 0 || st.autopilotTicks > 0 || st.destroyerTicks > 0;
        double topMult = 1.0;
        if (st.boostTicks > 0)
        {
            topMult *= Math.max(st.boostMult, 1.0);
            fx |= RaceFx.BOOST;
        }
        if (st.autopilotTicks > 0)
        {
            topMult *= 1.6;
            fx |= RaceFx.NIMBUS | RaceFx.BOOST;
            st.autopilotTicks--;
        }
        // Destroyer Aura (Star): a sustained x1.25 top and the purple god aura for the whole window.
        if (st.destroyerTicks > 0)
        {
            topMult *= 1.25;
            fx |= RaceFx.DESTROYER_AURA;
            st.destroyerTicks--;
        }
        // Kaioken flashes / x20 window / afterimage are purely cosmetic here (the boost is a separate BOOST effect).
        if (st.kaiokenFlashTicks > 0)
        {
            fx |= RaceFx.KAIOKEN;
            st.kaiokenFlashTicks--;
        }
        if (st.kaiokenX20Ticks > 0)
        {
            fx |= RaceFx.KAIOKEN_X20;
            st.kaiokenX20Ticks--;
        }
        if (st.afterimageTicks > 0)
        {
            fx |= RaceFx.AFTERIMAGE;
            st.afterimageTicks--;
        }
        if (st.squashTicks > 0)
        {
            topMult *= 0.6;
            fx |= RaceFx.SQUASH;
            st.squashTicks--;
        }
        if (!env.onSurface() && !boosting)
            topMult *= p.offroadMult;
        // The Zeni top-speed bonus is a sustained per-bike multiplier, not a boost (no off-road suppression).
        double topNow = p.topSpeed * topMult * Math.max(0.1, st.topSpeedMult);

        // Minecraft yaw increases clockwise (turning right), so left (A) must DECREASE yaw: steer < 0 = left.
        double steer = (in.right() ? 1.0 : 0.0) - (in.left() ? 1.0 : 0.0); // -1 left, +1 right
        double speedFrac = Math.min(1.0, Math.abs(st.speed) / Math.max(1e-3, p.topSpeed));

        // --- hop + drift start ---
        double hop = 0;
        boolean startDrift = false;
        if (controllable && in.jumpPressed() && in.grounded())
        {
            hop = p.hopImpulse;
            if (steer != 0 && speedFrac >= p.driftMinFraction && !st.drifting)
            {
                st.drifting = true;
                st.driftDir = (int) Math.signum(steer);
                st.driftCharge = 0;
                startDrift = true;
            }
        }

        // --- steering ---
        double yawRate = p.steerRateLow + (p.steerRateHigh - p.steerRateLow) * speedFrac;
        double yawDelta;
        if (!controllable)
        {
            // spin-out: forced spin, no drive control
            st.spinOutTicks--;
            st.speed *= 0.85;
            yawDelta = 36.0;
            fx |= RaceFx.SPINOUT;
        }
        else if (st.drifting)
        {
            yawDelta = st.driftDir * yawRate * 0.7 + steer * yawRate * 0.5;
            st.driftCharge++;
            int tier = driftTier(st.driftCharge, p);
            if (tier >= 3)
                fx |= RaceFx.DRIFT_PURPLE;
            else if (tier == 2)
                fx |= RaceFx.DRIFT_ORANGE;
            else if (tier >= 1)
                fx |= RaceFx.DRIFT_BLUE;
        }
        else
        {
            yawDelta = steer * yawRate;
        }
        if (Math.abs(st.speed) < 0.02)
            yawDelta *= 0.3; // little pivot in place

        // --- accelerate / brake / reverse ---
        if (controllable)
        {
            if (st.stallTicks > 0)
            {
                st.stallTicks--;
                st.speed *= 0.9;
            }
            else if (in.up())
            {
                st.speed += p.accel;
            }
            else if (in.down())
            {
                st.speed -= p.brake;
            }
            else
            {
                st.speed *= 0.96; // coast
            }
        }

        double maxReverse = -p.topSpeed * p.reverseFraction;
        if (st.speed > topNow)
            st.speed = Math.max(topNow, st.speed - p.brake); // ease down when a boost ends or going off-road
        if (st.speed < maxReverse)
            st.speed = maxReverse;

        // --- drift release -> mini-turbo ---
        boolean releaseDrift = false;
        int releaseTier = 0;
        if (st.drifting && !in.jumpHeld())
        {
            st.drifting = false;
            releaseTier = driftTier(st.driftCharge, p);
            if (releaseTier > 0)
            {
                st.boostTicks = Math.max(st.boostTicks, p.miniTurboTicks[releaseTier]);
                st.boostMult = Math.max(st.boostMult, p.miniTurboMult[releaseTier]);
            }
            st.driftCharge = 0;
            releaseDrift = true;
        }

        // --- boost pad + boost timer ---
        if (env.onBoostPad())
        {
            st.boostTicks = Math.max(st.boostTicks, 20);
            st.boostMult = Math.max(st.boostMult, p.boostMult);
        }
        if (st.boostTicks > 0)
        {
            st.boostTicks--;
            if (st.boostTicks == 0)
                st.boostMult = 1.0;
        }

        // --- launch (Spirit Bomb blast) one-shot upward ---
        if (st.launchTicks > 0)
        {
            if (hop < p.hopImpulse * 3.0)
                hop = p.hopImpulse * 3.0;
            fx |= RaceFx.SPINOUT;
            st.launchTicks--;
        }

        // --- grip: lerp the direction of travel toward the heading ---
        double newYaw = wrap(currentYaw + yawDelta);
        double grip = st.drifting ? p.grip * p.driftGripFactor : p.grip;
        st.velYaw = wrap(st.velYaw + Mth.wrapDegrees(newYaw - st.velYaw) * grip);

        return new Output(headingVel(st.velYaw, st.speed), yawDelta, hop, fx, startDrift, releaseDrift, releaseTier);
    }

    /** Horizontal velocity of magnitude |speed| in the {@code yaw} direction (Minecraft yaw convention). */
    private static Vec3 headingVel(double yaw, double speed)
    {
        return new Vec3(0, 0, speed).yRot((float) (-yaw * Math.PI / 180.0));
    }

    private static double wrap(double deg)
    {
        return Mth.wrapDegrees(deg);
    }
}
