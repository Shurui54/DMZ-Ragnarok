package net.shurui.shuruisutilities.client.dragons;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Spins a player's MODEL on the spot, without touching where they are looking.
 *
 * <p>Used by Oceanus Shenron's Mighty Hurricane Fury: the dragon is the eye of their own storm, so their body turns
 * with it. The rotation is applied to the pose stack between {@code RenderPlayerEvent.Pre} and {@code .Post}, so it
 * only ever affects the drawn model - the caster's camera, their aim and everything the server knows about their yaw
 * are all untouched, which is what stops the effect being motion sickness for the person casting it.
 *
 * <p>State arrives from {@link net.shurui.shuruisutilities.dragons.PacketSpinState} and expires on its own, so a
 * player who logs out mid-storm is not left spinning to everyone who reconnects.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class SpinRenderState
{
    private SpinRenderState() {}

    /** Degrees per tick. Fast enough to read as a spin, slow enough not to strobe the skin texture. */
    private static final double DEGREES_PER_TICK = 36.0;

    /** entity id -> ticks of spin left. */
    private static final Map<Integer, Integer> spinning = new HashMap<>();

    public static void set(int entityId, int ticks)
    {
        if (ticks <= 0)
            spinning.remove(entityId);
        else
            spinning.put(entityId, ticks);
    }

    public static void clear()
    {
        spinning.clear();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        if (Minecraft.getInstance().level == null)
        {
            clear();
            return;
        }
        for (Iterator<Map.Entry<Integer, Integer>> it = spinning.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<Integer, Integer> entry = it.next();
            int left = entry.getValue() - 1;
            if (left <= 0)
                it.remove();
            else
                entry.setValue(left);
        }
    }

    @SubscribeEvent
    public static void onRenderPre(RenderPlayerEvent.Pre event)
    {
        if (!spinning.containsKey(event.getEntity().getId()))
            return;
        event.getPoseStack().pushPose();
        event.getPoseStack().mulPose(Axis.YP.rotationDegrees(angle(event.getPartialTick())));
    }

    @SubscribeEvent
    public static void onRenderPost(RenderPlayerEvent.Post event)
    {
        if (spinning.containsKey(event.getEntity().getId()))
            event.getPoseStack().popPose();
    }

    /**
     * The spin angle for this frame.
     *
     * <p>The game time is narrowed and wrapped BEFORE it is scaled, not after: a raw {@code long} game time cast to
     * float loses its low bits within a few in-game days and the rotation starts stepping instead of turning. Wrapping
     * to a whole number of revolutions first keeps every frame's value exact and keeps the motion continuous across
     * the wrap.
     */
    private static float angle(float partialTick)
    {
        long ticks = Minecraft.getInstance().level == null ? 0L : Minecraft.getInstance().level.getGameTime();
        // 10 ticks per revolution at 36 degrees each, so 10 is the period to wrap on.
        double wrapped = (double) (ticks % 10L) + partialTick;
        return (float) ((wrapped * DEGREES_PER_TICK) % 360.0);
    }
}
