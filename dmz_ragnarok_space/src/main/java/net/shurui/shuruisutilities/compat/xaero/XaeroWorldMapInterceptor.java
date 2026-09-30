package net.shurui.shuruisutilities.compat.xaero;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.client.space.starmap.SpaceStarMapScreen;
import net.shurui.shuruisutilities.space.SpaceDimension;

/**
 * OPTIONAL Xaero's World Map integration: while the player is in the space dimension, opening Xaero's world map shows
 * OUR {@link SpaceStarMapScreen} instead. Outside space, Xaero opens exactly as normal.
 *
 * <p>This follows the workspace's optional-dependency rules without a compile dependency on Xaero at all: it never
 * imports or classloads an Xaero type. It hooks the Forge-level {@link ScreenEvent.Opening} (which fires for every
 * screen and needs no mixin) and recognises Xaero's world map screen by its CLASS NAME string
 * ({@code xaero.map.gui.GuiMap}), the "reflection escape hatch" the compat pattern allows for an unstable third-party
 * API. A {@link ModList} guard skips the work entirely when Xaero's World Map is absent, and the string match means the
 * class is only ever recognised, never loaded, so nothing here can fail when the mod is not installed. Client dist only.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class XaeroWorldMapInterceptor
{
    // Xaero's World Map full-screen map GUI, matched by name so this class never references (or loads) an Xaero type.
    private static final String XAERO_WORLD_MAP_SCREEN = "xaero.map.gui.GuiMap";
    private static final String XAERO_WORLD_MAP_MODID = "xaeroworldmap";

    private XaeroWorldMapInterceptor()
    {
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event)
    {
        if (!ModList.get().isLoaded(XAERO_WORLD_MAP_MODID))
        {
            return;
        }
        var newScreen = event.getNewScreen();
        if (newScreen == null || !XAERO_WORLD_MAP_SCREEN.equals(newScreen.getClass().getName()))
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !SpaceDimension.isSpace(mc.level))
        {
            return; // outside space, Xaero's world map opens normally.
        }
        // in space: swap Xaero's map for the star map. Our screen is not the Xaero class, so this cannot re-trigger.
        event.setNewScreen(new SpaceStarMapScreen());
    }
}
