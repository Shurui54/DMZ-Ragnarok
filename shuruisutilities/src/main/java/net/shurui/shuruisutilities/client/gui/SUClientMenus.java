package net.shurui.shuruisutilities.client.gui;


import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

// binds SU's custom chest menu types to the DMZ-styled DmzChestScreen. client dist / mod bus only.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SUClientMenus
{
    private SUClientMenus() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event)
    {
        event.enqueueWork(() -> {
            bind(SUMenus.PERM.get());
            // dragon ball bag gets its own DMZ-panel-skinned screen (fixed 7-slot row), not the generic chest screen.
            net.minecraft.client.gui.screens.MenuScreens.register(SUMenus.DRAGONBALL_BAG.get(),
                    net.shurui.shuruisutilities.dragonballbag.client.DragonBallBagScreen::new);
            // senzu bean bag gets its own DMZ-panel-skinned screen (fixed 9-slot row).
            net.minecraft.client.gui.screens.MenuScreens.register(SUMenus.SENZU_BAG.get(),
                    net.shurui.shuruisutilities.senzu.bag.client.SenzuBagScreen::new);
            net.minecraft.client.gui.screens.MenuScreens.register(
                    net.shurui.shuruisutilities.runes.RuneBenchRegistry.MENU.get(),
                    net.shurui.shuruisutilities.runes.client.RuneBenchScreen::new);
            // when sdu is loaded, add SU's admin editors as a section in the shared /rg npc edit hub (guarded compat)
            net.shurui.shuruisutilities.compat.sdu.SduHubCompat.register();
        });
    }

    private static <M extends AbstractContainerMenu> void bind(MenuType<M> type)
    {
        MenuScreens.register(type, (M menu, net.minecraft.world.entity.player.Inventory inv,
                net.minecraft.network.chat.Component title) -> new DmzChestScreen<M>(menu, inv, title));
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(net.minecraftforge.client.event.RegisterKeyMappingsEvent event)
    {
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.OPEN_MENU);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.TOGGLE_REGION_OVERLAY);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.OPEN_CHARACTERS);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.OPEN_TASKS);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.MOVE_REGION_HUD);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.TOGGLE_HOVERBIKE);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.OPEN_PLANET_SELECT);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.CLEAR_PLANET_COURSE);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.OPEN_PLANET_INFO);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.OPEN_DRAGONBALL_BAG);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.OPEN_SENZU_BAG);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.TAKE_SENZU_BEAN);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.DASH);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.ROLE_ABILITY);
        // race-only arrow-key steering (own category); active only while riding a race bike, never removed.
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.RACE_STEER_LEFT);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.RACE_STEER_RIGHT);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.RACE_ACCELERATE);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.RACE_BRAKE);
        event.register(net.shurui.shuruisutilities.client.SUKeybinds.RACE_MINIMAP);
    }

    @SubscribeEvent
    public static void onRegisterGuiOverlays(net.minecraftforge.client.event.RegisterGuiOverlaysEvent event)
    {
        // player NPC-region HUD (title/difficulty/description panel)
        event.registerAboveAll(net.shurui.shuruisutilities.client.hud.RegionHudOverlay.OVERLAY_ID,
                net.shurui.shuruisutilities.client.hud.RegionHudOverlay::render);
        // the floating planet-info panel is registered by the Space module's own client bus (SpaceClientBusEvents) so
        // this core menu class never names the Space client classes.
        // custom scouter-style stat HUD (orb player preview + health/ki/stamina/transform bars), replaces DMZ's HUD
        event.registerAboveAll(net.shurui.shuruisutilities.client.hud.ScouterStatHudOverlay.OVERLAY_ID,
                net.shurui.shuruisutilities.client.hud.ScouterStatHudOverlay::render);
        event.registerAboveAll(net.shurui.shuruisutilities.client.combat.ClashRhythmOverlay.OVERLAY_ID,
                net.shurui.shuruisutilities.client.combat.ClashRhythmOverlay.HUD);
        // the staff task a clocked-in staff member has accepted, so the job stays readable without the menu
        event.registerAboveAll(net.shurui.shuruisutilities.client.hud.StaffTaskHudOverlay.OVERLAY_ID,
                net.shurui.shuruisutilities.client.hud.StaffTaskHudOverlay::render);
        // quest tracker banner (tracked quest name on the commissioned banner, objectives beneath), replaces DMZ's tracker
        event.registerAboveAll(net.shurui.shuruisutilities.client.gui.task.QuestTrackerOverlay.OVERLAY_ID,
                net.shurui.shuruisutilities.client.gui.task.QuestTrackerOverlay::render);
        // race HUD (position, lap, timer, countdown, wrong-way / final-lap banners, results, rescue fade), visible
        // only with an active race session AND the racing feature synced from the server
        event.registerAboveAll(net.shurui.shuruisutilities.client.hud.RaceHudOverlay.OVERLAY_ID,
                net.shurui.shuruisutilities.client.hud.RaceHudOverlay::render);
    }
}
