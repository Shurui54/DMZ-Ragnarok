package net.shurui.dev.sdu.client.hud.ragnarok;

/**
 * A bar fill with DragonMineZ 2.2's HUD bar feel, for suite bars that are not drawn with DMZ's own HudBar (it needs
 * DMZ's sprite sheets): the fill eases toward its value, a loss leaves a trailing chip that holds and then drains, and
 * a burst gain leaves the fill behind for a beat and catches up. Same constants as DMZ's {@code HudBarAnimator}:
 * fill tau 0.55 s, chip hold 1.5 s then tau 0.32 s, a gain of 12% or more counts as a burst heal held 0.25 s.
 */
public final class RagnarokBarAnim
{
    private static final float FRONT_TAU = 0.55f;
    private static final float GHOST_TAU = 0.32f;
    private static final float DAMAGE_HOLD_SECONDS = 1.5f;
    private static final float HEAL_HOLD_SECONDS = 0.25f;
    private static final float BURST_HEAL_THRESHOLD = 0.12f;
    private static final float DAMAGE_THRESHOLD = 0.01f;
    private static final float MAX_FRAME_SECONDS = 0.25f;

    private boolean initialized;
    private float target;
    private float front;
    private float ghost;
    private float hold;
    private boolean healing;
    // a hit's flash strength, 1 on the frame of the hit and decaying with DMZ's impact tau (0.14 s)
    private float impact;
    private long lastNanos;

    /** Jump straight to a value with no animation (a new bar, or a different maximum). */
    public void reset(float fraction)
    {
        fraction = clamp(fraction);
        initialized = true;
        target = front = ghost = fraction;
        hold = 0.0f;
        healing = false;
        impact = 0.0f;
        lastNanos = System.nanoTime();
    }

    public void update(float fraction)
    {
        fraction = clamp(fraction);
        if (!initialized)
        {
            reset(fraction);
            return;
        }
        long now = System.nanoTime();
        float dt = Math.min(MAX_FRAME_SECONDS, Math.max(0.0f, (now - lastNanos) / 1_000_000_000.0f));
        lastNanos = now;

        if (target - fraction >= DAMAGE_THRESHOLD)
        {
            // a loss: the fill drops at once and the chip keeps the old value, held before it drains
            ghost = Math.max(ghost, front);
            front = fraction;
            hold = DAMAGE_HOLD_SECONDS;
            healing = false;
            impact = Math.min(1.0f, impact + 0.55f + (target - fraction) * 3.0f);
        }
        else if (fraction - target >= BURST_HEAL_THRESHOLD)
        {
            // a burst gain: the chip jumps ahead and the fill catches up after a beat
            healing = true;
            ghost = fraction;
            hold = HEAL_HOLD_SECONDS;
        }
        target = fraction;
        if (dt <= 0.0f)
            return;

        float frontBlend = 1.0f - (float) Math.exp(-dt / FRONT_TAU);
        float ghostBlend = 1.0f - (float) Math.exp(-dt / GHOST_TAU);
        impact *= (float) Math.exp(-dt / 0.14f);
        if (hold > 0.0f)
            hold -= dt;
        if (healing)
        {
            ghost = target;
            if (hold <= 0.0f)
                front += (target - front) * frontBlend;
            if (front >= ghost - 0.0015f)
            {
                front = ghost;
                healing = false;
            }
        }
        else
        {
            front += (target - front) * frontBlend;
            if (hold <= 0.0f)
                ghost += (front - ghost) * ghostBlend;
            if (ghost < front || ghost - front <= 0.0015f)
                ghost = front;
        }
    }

    public float front()
    {
        return front;
    }

    public float ghost()
    {
        return ghost;
    }

    /** True while the chip shows a gain (drawn green) rather than a loss. */
    public boolean healing()
    {
        return healing;
    }

    public float impact()
    {
        return impact;
    }

    private static float clamp(float v)
    {
        return v < 0.0f ? 0.0f : v > 1.0f ? 1.0f : v;
    }
}
