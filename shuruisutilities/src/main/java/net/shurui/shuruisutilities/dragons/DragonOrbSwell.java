package net.shurui.shuruisutilities.dragons;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.compat.dmz.DmzVisuals;

/**
 * The ball an area move detonates as: it starts on the caster and swells out to the edge of what the move actually
 * reaches, then holds there for as long as the move lasts.
 *
 * <h2>Why it is drawn in stages rather than as one entity</h2>
 * DMZ's explosion visual has a fixed 25-tick life and grows from half to full size over it, so a single one can only
 * ever pop into existence at half the radius. Spawning a short series of them, each a little wider than the last,
 * makes the ball read as expanding OUT OF the caster; the earlier, smaller ones are still alive inside the newer
 * ones, which is also what gives it a bright core rather than a hollow shell.
 *
 * <p>It is deliberately NOT a ki projectile. Anything extending {@code AbstractKiProjectile} that is left near a
 * player while not firing is destroyed by DMZ the moment it tidies up that player's charge state
 * ({@code TickHandler.findChargingEntity}), which is exactly the tick a move's detonation lands on.
 */
final class DragonOrbSwell implements DragonEffectTicker.ActiveEffect
{
    /** Ticks between stages. Short enough that the growth is continuous, long enough not to flood the client. */
    private static final int STAGE_INTERVAL = 6;

    /** Where the swell starts, as a fraction of the full radius. */
    private static final double START_FRACTION = 0.2;

    /** How long the swell takes to reach full width, in ticks. */
    private static final int SWELL_TICKS = 24;

    private final LivingEntity caster;
    private final ServerLevel level;
    private final double radius;
    private final int colorMain;
    private final int colorBorder;
    private final int colorOutline;

    private int age;
    private final int lifeTicks;

    private DragonOrbSwell(LivingEntity caster, ServerLevel level, double radius,
                           int colorMain, int colorBorder, int colorOutline, int lifeTicks)
    {
        this.caster = caster;
        this.level = level;
        this.radius = radius;
        this.colorMain = colorMain;
        this.colorBorder = colorBorder;
        this.colorOutline = colorOutline;
        this.lifeTicks = lifeTicks;
    }

    /** Start a swell on a caster that holds for {@code lifeTicks}. */
    static void start(LivingEntity caster, ServerLevel level, double radius,
                      int colorMain, int colorBorder, int colorOutline, int lifeTicks)
    {
        DragonOrbSwell swell = new DragonOrbSwell(caster, level, radius, colorMain, colorBorder, colorOutline,
                lifeTicks);
        swell.draw();
        DragonEffectTicker.add(swell);
    }

    @Override
    public boolean tick()
    {
        age++;
        if (age >= lifeTicks || !caster.isAlive())
            return false;
        if (age % STAGE_INTERVAL == 0)
            draw();
        return true;
    }

    private void draw()
    {
        // Centred on the chest rather than the feet, or half the ball is underground.
        Vec3 at = caster.position().add(0.0, caster.getBbHeight() * 0.5, 0.0);
        double grown = Math.min(1.0, START_FRACTION + (1.0 - START_FRACTION) * age / (double) SWELL_TICKS);
        DmzVisuals.aoeBall(level, at, radius * grown, colorMain, colorBorder, colorOutline);
    }
}
