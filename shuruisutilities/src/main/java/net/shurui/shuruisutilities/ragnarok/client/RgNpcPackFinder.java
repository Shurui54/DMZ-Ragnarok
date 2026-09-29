package net.shurui.shuruisutilities.ragnarok.client;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers {@link RgNpcPackResources} as an always-on, top-priority built-in client resource pack, mirroring
 * {@code RankPackFinder}. The models are streamed from the server at runtime ({@code PacketRgNpcAssets} -&gt;
 * {@link RgNpcAssetCache}); a copy is cached on disk so {@link RgNpcAssetCache#loadFromDisk()}, invoked here
 * before the initial resource load, can populate the pack up front and let GeckoLib bake the models at startup
 * with no reload. Mod-bus, client-only.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RgNpcPackFinder
{
    private RgNpcPackFinder() {}

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event)
    {
        if (event.getPackType() != PackType.CLIENT_RESOURCES)
            return;
        // Populate the pack from the disk cache BEFORE the initial resource load reads it, so GeckoLib bakes the
        // cached models at startup and the server's on-join resend of the same version needs no reload.
        RgNpcAssetCache.loadFromDisk();
        event.addRepositorySource(onLoad ->
        {
            Pack pack = Pack.readMetaAndCreate(RgNpcPackResources.INSTANCE.packId(),
                    Component.literal("Ragnarok NPC Models"), true, id -> RgNpcPackResources.INSTANCE,
                    PackType.CLIENT_RESOURCES, Pack.Position.TOP, PackSource.BUILT_IN);
            if (pack != null)
                onLoad.accept(pack);
        });
    }
}
