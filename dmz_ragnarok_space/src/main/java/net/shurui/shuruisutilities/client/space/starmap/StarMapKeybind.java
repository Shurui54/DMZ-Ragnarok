package net.shurui.shuruisutilities.client.space.starmap;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import net.shurui.shuruisutilities.space.SpaceDimension;

/**
 * The star map keybind: a fallback way to open {@link SpaceStarMapScreen} when Xaero's World Map is NOT installed (or
 * a player simply prefers a key). Bound to nothing by default so it never clashes with DMZ's or Xaero's keys; the player
 * can bind it under Controls, in the DMZ Ragnarok category. It opens the map only while the player is in the space
 * dimension, matching where the map is meaningful.
 *
 * <p>Registered on THIS module's mod bus (the mapping) and forge bus (the tick that consumes presses), each with an
 * explicit modid so it fires once in a multi-mod jar.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class StarMapKeybind
{
    public static final KeyMapping OPEN_STAR_MAP = new KeyMapping(
            "key.dmz_ragnarok.core.starmap.open",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.dmz_ragnarok");

    private StarMapKeybind()
    {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event)
    {
        event.register(OPEN_STAR_MAP);
    }

    /** Forge-bus half: consume presses and open the star map when in space and no screen is already up. */
    @Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBus
    {
        private ForgeBus()
        {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event)
        {
            if (event.phase != TickEvent.Phase.END)
            {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            boolean opened = false;
            while (OPEN_STAR_MAP.consumeClick())
            {
                if (opened || mc.player == null || mc.screen != null || mc.level == null
                        || !SpaceDimension.isSpace(mc.level))
                {
                    continue;
                }
                mc.setScreen(new SpaceStarMapScreen());
                opened = true;
            }
        }
    }
}
