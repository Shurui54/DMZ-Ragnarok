package net.shurui.shuruisutilities.ranks.client;


import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers {@link RankPackResources} as an always-on, top-priority built-in client resource pack. The rank textures
 * are streamed from the server at runtime ({@code PacketRankAssets} -&gt; {@link RankAssetCache}); a copy is cached on
 * disk so {@link RankAssetCache#loadFromDisk()}: invoked here, before the initial resource load, can populate the
 * pack up front and let the ranks font stitch at startup with no reload. A reload only happens the rare time the
 * server ships a changed rank set. Mod-bus, client-only.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RankPackFinder
{
    private RankPackFinder() {}

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event)
    {
        if (event.getPackType() != PackType.CLIENT_RESOURCES)
            return;
        // Populate the pack from the disk cache BEFORE the initial resource load reads it, so the ranks font stitches
        // with the cached glyphs at startup and the server's on-join resend of the same version needs no reload.
        RankAssetCache.loadFromDisk();
        event.addRepositorySource(onLoad ->
        {
            Pack pack = Pack.readMetaAndCreate(RankPackResources.INSTANCE.packId(),
                    Component.literal("SU Rank Badges"), true, id -> RankPackResources.INSTANCE,
                    PackType.CLIENT_RESOURCES, Pack.Position.TOP, PackSource.BUILT_IN);
            if (pack != null)
                onLoad.accept(pack);
        });
    }
}
