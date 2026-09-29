package net.shurui.shuruisutilities.cosmetics.wardrobe.client;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers {@link CosmeticPackResources} as an always-on, top-priority built-in client resource pack, mirroring
 * {@code RankPackFinder} and {@code RgNpcPackFinder}. The art is streamed by the Ragnarok Key at runtime
 * ({@code PacketCosmeticAssets} into {@link CosmeticAssetCache}); the disk cache is loaded here, BEFORE the initial
 * resource load, so a returning player's cosmetics bake at startup and no reload is needed on join.
 *
 * <p>Also re-arms the triggered-animation player tracks after every resource reload, because that file is part of
 * the stream and may land after the first read. Mod bus, client only.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class CosmeticPackFinder
{
    private CosmeticPackFinder() {}

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event)
    {
        if (event.getPackType() != PackType.CLIENT_RESOURCES)
            return;
        CosmeticAssetCache.loadFromDisk();
        event.addRepositorySource(onLoad ->
        {
            Pack pack = Pack.readMetaAndCreate(CosmeticPackResources.INSTANCE.packId(),
                    Component.literal("DMZ Ragnarok Cosmetic Art"), true, id -> CosmeticPackResources.INSTANCE,
                    PackType.CLIENT_RESOURCES, Pack.Position.TOP, PackSource.BUILT_IN);
            if (pack != null)
                onLoad.accept(pack);
        });
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event)
    {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> CosmeticAnimPlayerPoser.invalidate());
    }
}
