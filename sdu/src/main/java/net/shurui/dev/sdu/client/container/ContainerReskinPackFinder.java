package net.shurui.dev.sdu.client.container;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.client.ClientConfig;

/**
 * Registers {@link ContainerReskinPack} as a top-priority built-in client resource pack, but only when the
 * {@link ClientConfig#reskinContainers} toggle is on. Gating the pack registration on the config value is how
 * the toggle takes effect: with the toggle off the pack is never added, so the vanilla container textures load
 * unchanged.
 *
 * <p>Because Minecraft builds its resource-pack set once and cannot cleanly hot-swap a built-in pack, flipping
 * the config at runtime only changes what happens on the NEXT resource (re)load. The user must reload resources
 * (F3+T) or restart for a change to this toggle to show. Mod-bus, client-only.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ContainerReskinPackFinder {

    private ContainerReskinPackFinder() {
    }

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        // Either switch is enough to justify the pack: it serves the two namespaces independently, so a
        // client that wants only the Curios sidebar still needs the pack registered.
        if (!ClientConfig.reskinContainers && !ClientConfig.reskinCurios) {
            return;
        }
        event.addRepositorySource(onLoad -> {
            Pack pack = Pack.readMetaAndCreate(ContainerReskinPack.INSTANCE.packId(),
                    Component.literal("Shurui's DMZ Essentials container reskin"), true,
                    id -> ContainerReskinPack.INSTANCE, PackType.CLIENT_RESOURCES,
                    Pack.Position.TOP, PackSource.BUILT_IN);
            if (pack != null) {
                onLoad.accept(pack);
            }
        });
    }
}
