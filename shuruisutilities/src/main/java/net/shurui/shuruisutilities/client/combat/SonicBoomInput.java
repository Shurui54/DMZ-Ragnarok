package net.shurui.shuruisutilities.client.combat;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.combat.PacketSonicBoom;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Turns a double tap of the SPRINT key into a request to break the sound barrier.
 *
 * <p>The sprint key rather than a bind of its own, because it is already the "go faster" key and while flying it is
 * already what fast flight is bound to: asking for more of the same thing with the same key is the gesture that
 * needs no explaining.
 *
 * <p>Edge triggered on the raw key, and deliberately NOT on {@code player.isSprinting()} - the sprint STATE is
 * latched and stays on while a key is held, so it would report one long press as a tap and never see the second.
 *
 * <p>Nothing is checked here beyond the gesture. Whether the player is flying, has mastered flight, can pay for it
 * or is already mid-crash are all the server's to decide ({@code SonicBoomService}), because a client cannot be
 * trusted with any of them.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class SonicBoomInput
{
    private SonicBoomInput() {}

    /** The same window the dash gesture uses, for the same reasons: chatter below, two separate presses above. */
    private static final long MIN_GAP_MILLIS = 50L;
    private static final long MAX_GAP_MILLIS = 400L;

    private static boolean wasDown;
    private static long lastPressMillis;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null)
        {
            wasDown = false;
            return;
        }
        boolean down = mc.options.keySprint.isDown();
        if (down && !wasDown)
        {
            long now = System.currentTimeMillis();
            long gap = now - lastPressMillis;
            if (gap >= MIN_GAP_MILLIS && gap <= MAX_GAP_MILLIS)
            {
                NetworkUtils.sendToServer(new PacketSonicBoom());
                // Consumed, so a third tap starts a fresh gesture rather than chaining off the second.
                lastPressMillis = 0L;
            }
            else
            {
                lastPressMillis = now;
            }
        }
        wasDown = down;
    }
}
