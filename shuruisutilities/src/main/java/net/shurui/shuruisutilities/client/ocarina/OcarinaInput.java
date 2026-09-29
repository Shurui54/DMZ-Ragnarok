package net.shurui.shuruisutilities.client.ocarina;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.ocarina.PacketOcarina;

/**
 * Raises the ocarina on DMZ's own ACTION key.
 *
 * <p>That key rather than one of ours, because it is already the key for "use the thing my character can do", and a
 * racial ability is exactly that. DMZ keeps whatever else it does with the key; this only adds a request, and the
 * server answers it with a menu ONLY for a player whose race actually carries an ocarina - so for everyone else the
 * key behaves exactly as it did.
 *
 * <p>Edge triggered on the key going down, and never while a screen is open, so the ocarina cannot be re-raised from
 * inside its own menu.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class OcarinaInput
{
    private OcarinaInput() {}

    private static boolean wasDown;

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
        // The ocarina is a Ragnarok Key feature: against a server without it the key is left entirely to DMZ.
        if (!net.shurui.dev.sdu.api.ClientGate.feature("ocarina"))
        {
            wasDown = false;
            return;
        }
        boolean down = actionKeyDown();
        if (down && !wasDown)
            NetworkUtils.sendToServer(new PacketOcarina(PacketOcarina.OPEN, 0, 0));
        wasDown = down;
    }

    /**
     * Is DMZ's action key held?
     *
     * <p>Read reflectively. DMZ is a mandatory dependency, but a keybind field is exactly the sort of thing that
     * gets renamed between versions, and an ocarina is not worth a crash on the client tick: a failed lookup simply
     * means the key does nothing here.
     */
    private static boolean actionKeyDown()
    {
        try
        {
            if (ACTION_KEY == null)
            {
                if (resolved)
                    return false;
                resolved = true;
                Class<?> binds = Class.forName("com.dragonminez.client.util.KeyBinds");
                ACTION_KEY = (net.minecraft.client.KeyMapping) binds.getField("ACTION_KEY").get(null);
            }
            return ACTION_KEY != null && ACTION_KEY.isDown();
        }
        catch (Throwable t)
        {
            resolved = true;
            ACTION_KEY = null;
            return false;
        }
    }

    private static net.minecraft.client.KeyMapping ACTION_KEY;
    private static boolean resolved;
}
