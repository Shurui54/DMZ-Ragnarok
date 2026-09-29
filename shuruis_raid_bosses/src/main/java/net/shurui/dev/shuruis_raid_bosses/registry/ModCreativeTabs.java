package net.shurui.dev.shuruis_raid_bosses.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.dev.shuruis_raid_bosses.Shuruis_raid_bosses;
import net.shurui.dev.shuruis_raid_bosses.item.ZSoulTier;

/**
 * Injects the raid soul + Z-Souls into SU's gems_souls tab. SU is now in this same container and always
 * present, so there is no fallback tab. They never appear in a vanilla tab (once polluted TOOLS_AND_UTILITIES).
 */
public final class ModCreativeTabs {
    private ModCreativeTabs() {}

    // SU-owned tab, referenced by ResourceLocation only, no classload coupling to shuruisutilities. It is
    // registered under this container's shared modid (dmz_ragnarok), so it must be addressed under THAT
    // namespace, not the pre-merge "shuruisutilities" id, or the souls silently miss the tab.
    private static final ResourceLocation SU_GEMS_SOULS =
            new ResourceLocation(Shuruis_raid_bosses.MODID, "gems_souls");

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok_raids", bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Injector {
        // Registered at class load: Forge initialises this subscriber class at mod construction, long before a
        // tab is built or JEI starts. Only the LISTING changes; the items register everywhere.
        static {
            // PRIVATE: the raid soul and every Z soul are left out of the tab (and JEI) unless the server holds the
            // key.
            net.shurui.dev.sdu.api.PrivateItems.register(null, () -> java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(ModItems.RAID_SOUL), ModItems.ALL_ZSOULS.stream())
                    .map(RegistryObject::get)
                    .toList());
        }

        @SubscribeEvent
        public static void onBuildTabs(BuildCreativeModeTabContentsEvent event) {
            // Everything below goes through the private-item filter (see the static block above).
            net.minecraft.world.item.CreativeModeTab.Output out = net.shurui.dev.sdu.api.PrivateItems.filtered(event);
            if (SU_GEMS_SOULS.equals(event.getTabKey().location())) {
                // RegistryObjects can be left unbound after a server registry sync (Mohist), so ifPresent
                // guards against crashing the client while the tab is built.
                ModItems.RAID_SOUL.ifPresent(out::accept);
                for (RegistryObject<Item> ro : ModItems.ALL_ZSOULS) ro.ifPresent(out::accept);
            }
        }
    }
}
