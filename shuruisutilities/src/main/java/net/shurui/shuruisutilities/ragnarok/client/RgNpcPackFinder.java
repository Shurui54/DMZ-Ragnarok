package net.shurui.shuruisutilities.ragnarok.client;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.ragnarok.RgNpcModels;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Registers {@link RgNpcPackResources} as an always-on, top-priority built-in client resource pack.
 *
 * <p><b>Bundled, not streamed (September 2026).</b> The rgnpc set left the server folder and returned to the jar
 * (the NinjinEntities saga models, see {@link RgNpcModels}). The models therefore ship as ordinary assets under
 * {@code assets/dmz_ragnarok/geo/entity/ragnarok/} and {@code .../textures/entity/ragnarok/}, and this finder
 * loads them off the classpath into the in-memory pack at {@link AddPackFindersEvent}, before the first resource
 * load. Two reasons the in-memory pack survives the move rather than the models being served straight from the jar
 * pack: it sits at {@code Pack.Position.TOP} so {@link RgNpcRig} can hand GeckoLib the torso-corrected bytes, and it
 * is the same pack that answers the {@code dragonminez:} race-model aliases ({@link RgNpcRaceModels}). Mod-bus,
 * client-only. The old server stream ({@code PacketRgNpcAssets} 93 / {@code PacketRgNpcAssetsHave} 97) is gone; those
 * packet ids are left as inert holes.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RgNpcPackFinder
{
    private RgNpcPackFinder() {}

    private static final String GEO_PREFIX = "geo/entity/ragnarok/";
    private static final String TEX_PREFIX = "textures/entity/ragnarok/";
    private static final String ASSET_ROOT = "/assets/dmz_ragnarok/";

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event)
    {
        if (event.getPackType() != PackType.CLIENT_RESOURCES)
            return;
        loadBundled();
        event.addRepositorySource(onLoad ->
        {
            Pack pack = Pack.readMetaAndCreate(RgNpcPackResources.INSTANCE.packId(),
                    Component.literal("Ragnarok NPC Models"), true, id -> RgNpcPackResources.INSTANCE,
                    PackType.CLIENT_RESOURCES, Pack.Position.TOP, PackSource.BUILT_IN);
            if (pack != null)
                onLoad.accept(pack);
        });
    }

    /**
     * Read every bundled rgnpc geo and its default texture off the classpath into {@link RgNpcPackResources}. The
     * file list is derived from {@link RgNpcModels} (distinct geo ids and default texture names) rather than by
     * scanning the jar, so it never lists a variant name GeckoLib was not told to bake. Best effort per file: a
     * missing one simply is not served, and the draw path falls back for it.
     */
    private static void loadBundled()
    {
        Map<String, byte[]> files = new HashMap<>();
        Set<String> geos = new HashSet<>();
        Set<String> texs = new HashSet<>();
        for (String id : RgNpcModels.ids())
        {
            geos.add(RgNpcModels.geoId(id));
            texs.add(RgNpcModels.defaultTexture(id));
        }
        for (String geo : geos)
        {
            String rel = GEO_PREFIX + geo + ".geo.json";
            byte[] bytes = read(ASSET_ROOT + rel);
            if (bytes != null)
                files.put(rel, bytes);
        }
        for (String tex : texs)
        {
            String rel = TEX_PREFIX + tex + ".png";
            byte[] bytes = read(ASSET_ROOT + rel);
            if (bytes != null)
                files.put(rel, bytes);
        }
        RgNpcPackResources.replaceAll(files);
        LoggingHandler.sulog.info("[rgnpc] Loaded {} bundled model file(s) into the in-memory pack.", files.size());
    }

    private static byte[] read(String classpath)
    {
        try (InputStream in = RgNpcPackFinder.class.getResourceAsStream(classpath))
        {
            if (in == null)
                return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return out.toByteArray();
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
