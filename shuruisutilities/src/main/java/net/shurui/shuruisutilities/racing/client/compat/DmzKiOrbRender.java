package net.shurui.shuruisutilities.racing.client.compat;

import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;

import com.dragonminez.common.init.MainEntities;
import com.dragonminez.common.init.entities.ki.KiBlastEntity;

import net.shurui.shuruisutilities.racing.entity.RaceKiOrbEntity;

/**
 * Renders a race ki orb (Ki Blast, Hellzone, Spirit Bomb) with DragonMineZ's real ki VISUAL, by delegating to DMZ's
 * {@code KiProjectileRenderer} through a client-only dummy {@link KiBlastEntity} that is NEVER added to the world: it
 * carries no DMZ technique / damage / homing logic, only the colours and size we set. DragonMineZ is a mandatory
 * dependency, so the classes always resolve; even so the whole call is guarded, and on any failure the delegate is
 * marked dead for the session so the caller draws its own translucent-sphere fallback.
 *
 * <p>DMZ's renderer does NOT draw immediately: it enqueues a task into its own effect queue, flushed later in the
 * same frame. So each orb needs its OWN dummy (state read at flush time), keyed here weakly by the orb entity, and
 * re-posed per frame.
 */
public final class DmzKiOrbRender
{
    private DmzKiOrbRender() {}

    /** Force the fallback sphere (skip the DMZ delegate) without throwing: a visual / test switch (also settable). */
    private static boolean forceFallback = Boolean.getBoolean("dmzr.raceKiFallback");
    /** Force the delegate to THROW, to exercise the catch -> fallback path in a test (property or setter). */
    private static boolean forceThrow = Boolean.getBoolean("dmzr.raceKiThrow");

    /** Test hook: force the drawn-sphere fallback instead of the DMZ delegate. */
    public static void setForceFallback(boolean v) { forceFallback = v; }
    /** Test hook: force the delegate to throw so the catch -> fallback path is exercised (resets {@code dead}). */
    public static void setForceThrow(boolean v) { forceThrow = v; if (v) dead = false; }

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("dmz_ragnarok");

    private static final WeakHashMap<RaceKiOrbEntity, KiBlastEntity> DUMMIES = new WeakHashMap<>();
    private static volatile boolean dead;

    /**
     * Draw the orb with the DMZ ki visual at the current pose origin. Returns false (drawing nothing) if the delegate
     * is unavailable / forced off, so the caller draws its fallback.
     */
    public static boolean render(RaceKiOrbEntity orb, int main, int border, int outline, float size, float yaw,
                                 float xrot, float partialTick, PoseStack pose, MultiBufferSource buffers, int light)
    {
        if (dead || forceFallback)
            return false;
        try
        {
            if (forceThrow)
                throw new RuntimeException("forced ki delegate failure (dmzr.raceKiThrow test)");
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null)
                return false;
            KiBlastEntity dummy = DUMMIES.get(orb);
            if (dummy == null || dummy.level() != mc.level)
            {
                dummy = new KiBlastEntity(MainEntities.KI_BLAST.get(), mc.level);
                DUMMIES.put(orb, dummy);
            }
            dummy.setColors(main, border, outline);
            dummy.setSize(size);
            dummy.setPos(orb.getX(), orb.getY(), orb.getZ());
            dummy.setYRot(yaw);
            dummy.yRotO = yaw;
            dummy.setXRot(xrot);
            dummy.xRotO = xrot;
            dummy.tickCount = orb.tickCount; // animate the ki shimmer in step with the orb

            @SuppressWarnings("unchecked")
            EntityRenderer<KiBlastEntity> renderer =
                    (EntityRenderer<KiBlastEntity>) mc.getEntityRenderDispatcher().getRenderer(dummy);
            if (renderer == null)
                return false;
            renderer.render(dummy, yaw, partialTick, pose, buffers, light);
            return true;
        }
        catch (Throwable t)
        {
            dead = true;
            LOG.warn("[race] DMZ ki-orb renderer delegate failed; using the fallback sphere for the rest of the session",
                    t);
            return false;
        }
    }
}
