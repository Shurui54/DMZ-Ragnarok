package net.shurui.dev.shuruis_dmz_tournaments.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.dev.shuruis_dmz_tournaments.Shuruis_dmz_tournaments;

public final class ModCreativeTabs {
    private ModCreativeTabs() {}

    // SU-owned tab, referenced by ResourceLocation only so there's no classload coupling to shuruisutilities.
    // Registered under this container's shared modid (dmz_ragnarok), so address it under that namespace, not the
    // pre-merge "shuruisutilities" id, or the stat gems silently miss the tab.
    private static final ResourceLocation SU_GEMS_SOULS =
            new ResourceLocation(Shuruis_dmz_tournaments.MODID, "gems_souls");

    // No fallback tab: shuruisutilities (the tab owner) is now in this same container and always present.

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok_tournaments", bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Injector {
        // Registered at class load: Forge initialises this subscriber class at mod construction, long before a
        // tab is built or JEI starts. Only the LISTING changes; the items register everywhere.
        static {
            // PRIVATE: the stat gems are left out of the tab (and JEI) unless the server holds the key.
            net.shurui.dev.sdu.api.PrivateItems.register(null, () -> ModItems.STAT_GEMS.stream()
                    .map(net.minecraftforge.registries.RegistryObject::get)
                    .toList());
        }

        @SubscribeEvent
        public static void onBuildTabs(BuildCreativeModeTabContentsEvent event) {
            // Everything below goes through the private-item filter (see the static block above).
            net.minecraft.world.item.CreativeModeTab.Output out = net.shurui.dev.sdu.api.PrivateItems.filtered(event);
            if (SU_GEMS_SOULS.equals(event.getTabKey().location())) {
                ModItems.STAT_GEMS.forEach(gem -> gem.ifPresent(out::accept));
            }
        }
    }
}
