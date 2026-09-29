package net.shurui.dev.shuruis_dmz_dungeons.registry;

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
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;

public final class ModCreativeTabs {

    // SU-owned tabs, referenced by ResourceLocation only so there is no classload coupling to shuruisutilities.
    // Registered under the shared modid (dmz_ragnarok), so address them under that namespace, not the pre-merge
    // "shuruisutilities" id, or the tp gems and crates silently miss the tabs.
    private static final ResourceLocation SU_GEMS_SOULS =
            new ResourceLocation(Shuruis_dmz_dungeons.MODID, "gems_souls");
    private static final ResourceLocation SU_BLOCKS_MISC =
            new ResourceLocation(Shuruis_dmz_dungeons.MODID, "blocks_misc");

    // shuruisutilities (the tab owner) is now in this same container and always present, so this mod's items are
    // always injected into the SU tabs below.

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok_dungeons", bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Injector {
        // Registered at class load: Forge initialises this subscriber class at mod construction, long before a
        // tab is built or JEI starts. Only the LISTING changes; the items register everywhere.
        static {
            // PRIVATE: TP gems, the floor ticket (instanced dungeons) and the dungeon crates (barrel form too) are
            // left out of the tabs (and JEI) unless the server holds the key. The advanced spawner is public.
            net.shurui.dev.sdu.api.PrivateItems.register(null, () -> java.util.stream.Stream.concat(
                            ModItems.TP_GEMS.stream(),
                            java.util.stream.Stream.of(
                                    ModItems.FLOOR_TICKET, ModItems.CRATE_CHEST, ModItems.CRATE_BARREL))
                    .map(net.minecraftforge.registries.RegistryObject::get)
                    .toList());
        }

        @SubscribeEvent
        public static void onBuildTabs(BuildCreativeModeTabContentsEvent event) {
            // Everything below goes through the private-item filter (see the static block above).
            net.minecraft.world.item.CreativeModeTab.Output out = net.shurui.dev.sdu.api.PrivateItems.filtered(event);
            ResourceLocation key = event.getTabKey().location();
            if (SU_GEMS_SOULS.equals(key)) {
                ModItems.TP_GEMS.forEach(gem -> gem.ifPresent(out::accept));
                ModItems.FLOOR_TICKET.ifPresent(out::accept);
            } else if (SU_BLOCKS_MISC.equals(key)) {
                ModItems.ADVANCED_SPAWNER.ifPresent(out::accept);
                ModItems.CRATE_CHEST.ifPresent(out::accept);
                // crate_barrel is NOT offered any more: the barrel form draws as nothing in world, so every one
                // placed here was an invisible block, and generation stopped producing them too (CrateConversionTask
                // promotes barrels to crate chests). Block and item stay registered so existing ones still load and
                // /give still reaches it.
                // Below: one entry per rarity x metal LOOK. The plain entry above places a crate that works its
                // rarity and metal out from where it stands (right for a dungeon, but no way to choose one); these
                // twelve place exactly the crate you picked, which makes the set inspectable and usable as decoration.
                for (net.shurui.dev.shuruis_dmz_dungeons.block.CrateTier tier
                        : net.shurui.dev.shuruis_dmz_dungeons.block.CrateTier.values()) {
                    for (net.shurui.dev.shuruis_dmz_dungeons.block.CrateMetal metal
                            : net.shurui.dev.shuruis_dmz_dungeons.block.CrateMetal.values()) {
                        ModItems.CRATE_CHEST.ifPresent(item -> {
                            net.minecraft.world.item.ItemStack stack =
                                    new net.minecraft.world.item.ItemStack(item);
                            net.shurui.dev.shuruis_dmz_dungeons.block.CrateBlock.withVariant(
                                    stack, tier.ordinal(), metal.ordinal());
                            // named, because twelve identical icons would be twelve guesses otherwise.
                            stack.setHoverName(Component.translatable("item.dmz_ragnarok.crate_variant",
                                    Component.translatable(tier.langKey()),
                                    Component.translatable(metal.langKey())));
                            out.accept(stack);
                        });
                    }
                }
                // Toffy's colour crates as hand-placeable dungeon crates. Behave like any other dungeon crate (no
                // key, instant reward), just a different model and whatever tier they were set to. Sneak-right-click
                // in CREATIVE steps a placed one through the tiers.
                for (String skin : net.shurui.dev.shuruis_dmz_dungeons.block.CrateBlock.SKINS) {
                    ModItems.CRATE_CHEST.ifPresent(item -> {
                        net.minecraft.world.item.ItemStack stack =
                                new net.minecraft.world.item.ItemStack(item);
                        net.shurui.dev.shuruis_dmz_dungeons.block.CrateBlock.withVariant(stack, 0, 0, skin);
                        stack.setHoverName(Component.translatable("item.dmz_ragnarok.crate_skin",
                                Component.translatable("crate.dmz_ragnarok.skin." + skin)));
                        out.accept(stack);
                    });
                }
            }
        }
    }

    private ModCreativeTabs() {
    }
}
