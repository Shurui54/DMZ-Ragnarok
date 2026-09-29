package net.shurui.dev.sdu.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.shenron.ShrineColor;

/**
 * sdu's creative tab handling. The five shared content tabs (gems_souls, dragon_balls, equipment, consumables,
 * blocks_misc) and their 233 items moved to shuruisutilities. sdu now only inserts its own legacy items into
 * those SU-owned tabs via {@link Injector}.
 *
 * <p>sdu loads BEFORE shuruisutilities and has no dependency on it. Safe here: tab population runs on
 * BuildCreativeModeTabContentsEvent, after all registration completes, so load order does not matter. SU tabs
 * are addressed by ResourceLocation string only, never by classloading any SU class.
 */
public final class ModCreativeTabs {

    // SU-owned tabs, referenced by ResourceLocation only so there is no classload coupling to shuruisutilities.
    private static final ResourceLocation SU_GEMS_SOULS = new ResourceLocation("dmz_ragnarok", "gems_souls");
    private static final ResourceLocation SU_EQUIPMENT = new ResourceLocation("dmz_ragnarok", "equipment");
    private static final ResourceLocation SU_BLOCKS_MISC = new ResourceLocation("dmz_ragnarok", "blocks_misc");

    // The old sdu:main fallback tab is gone: shuruisutilities (the tab owner) is now in this same container and
    // always present, so sdu's legacy items always inject into the SU tabs below.

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Injector {
        // Registered at class load: Forge initialises this subscriber class at mod construction, long before a
        // tab is built or JEI starts. Only the LISTING changes; the items register everywhere.
        static {
            // PRIVATE (the token buffs live in the Ragnarok Key): the TP and stat buff tokens are left out of the
            // gems tab (and JEI) unless the server reported the tokenbuffs feature installed.
            net.shurui.dev.sdu.api.PrivateItems.register(net.shurui.dev.sdu.api.key.TokenBuffHooks.FEATURE_ID,
                    () -> java.util.stream.Stream.concat(ModItems.TP_BUFF_TOKENS.stream(),
                                    ModItems.STAT_BUFF_TOKENS.stream())
                            .map(net.minecraftforge.registries.RegistryObject::get)
                            .toList());
        }

        @SubscribeEvent
        public static void onBuildTabs(BuildCreativeModeTabContentsEvent event) {
            // Everything below goes through the private-item filter (see the static block above).
            net.minecraft.world.item.CreativeModeTab.Output out = net.shurui.dev.sdu.api.PrivateItems.filtered(event);
            ResourceLocation key = event.getTabKey().location();
            if (SU_BLOCKS_MISC.equals(key)) {
                for (ShrineColor color : ShrineColor.values()) {
                    var block = ModBlocks.SHRINES.get(color);
                    if (block != null) {
                        block.ifPresent(b -> out.accept(b));
                    }
                }
                ModBlocks.GRAVITY_CHAMBER.ifPresent(b -> out.accept(b));
                ModBlocks.LEVEL_BARRIER.ifPresent(b -> out.accept(b));
                ModItems.MAX_PROGRESSION.ifPresent(out::accept);
            } else if (SU_EQUIPMENT.equals(key)) {
                ModItems.SHURUIS_ARMOR_CHESTPLATE.ifPresent(out::accept);
                ModItems.SHURUIS_ARMOR_LEGGINGS.ifPresent(out::accept);
                ModItems.SHURUIS_ARMOR_BOOTS.ifPresent(out::accept);
                ModItems.DUKES_BLADE.ifPresent(out::accept);
            } else if (SU_GEMS_SOULS.equals(key)) {
                for (var token : ModItems.TP_BUFF_TOKENS) {
                    token.ifPresent(out::accept);
                }
                for (var token : ModItems.STAT_BUFF_TOKENS) {
                    token.ifPresent(out::accept);
                }
            }
        }
    }

    private ModCreativeTabs() {
    }
}
