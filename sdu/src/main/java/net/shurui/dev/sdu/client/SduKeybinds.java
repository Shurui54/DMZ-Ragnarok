package net.shurui.dev.sdu.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.network.ActivateRacialPacket;
import net.shurui.dev.sdu.network.DmzNet;
import org.lwjgl.glfw.GLFW;

/**
 * Client keybind for activating an ACTIVE-trigger racial skill. Registered on the mod bus; presses
 * are consumed on the client tick (forge bus) and sent to the server, which validates the player's
 * racial and cooldown. Bound to an unused key by default so it never clashes with DMZ's keys; the
 * player can rebind it under Controls → Shurui's DMZ Utilities.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class SduKeybinds {

    public static final KeyMapping ACTIVATE_RACIAL = new KeyMapping(
            "key.dmz_ragnarok.npc.activate_racial",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "key.categories.dmz_ragnarok");

    private SduKeybinds() {
    }

    /** Called from the mod-bus {@link RegisterKeyMappingsEvent}. */
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(ACTIVATE_RACIAL);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        boolean sent = false;
        while (ACTIVATE_RACIAL.consumeClick()) {
            if (!sent) {
                DmzNet.sendToServer(new ActivateRacialPacket());
                sent = true;
            }
        }
    }
}
