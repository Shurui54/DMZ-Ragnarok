package net.shurui.shuruisutilities.ranks.client;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * A built-in client resource pack whose files live only in memory. The server streams the rank badge PNGs to the
 * client at runtime ({@code PacketRankAssets}); {@link RankAssetCache} unzips them into {@link #FILES} keyed by
 * their {@code assets/shuruisutilities/…} path, and this pack hands them to the vanilla font pipeline so the
 * {@code shuruisutilities:ranks} font renders exactly as before. The PNGs are never shipped in the mod jar, so they
 * can't be lifted by unzipping it; a version-keyed copy is XOR-masked into a client-side cache ({@link RankAssetCache})
 * only so the font can stitch at startup without a per-join resource reload. There is a single shared instance.
 */
public final class RankPackResources implements PackResources
{
    public static final RankPackResources INSTANCE = new RankPackResources();

    /** Namespace-relative resource path (e.g. {@code textures/font/rank/admin.png}) -&gt; PNG bytes. */
    private static final Map<String, byte[]> FILES = new ConcurrentHashMap<>();

    /** Prefix (under the {@code shuruisutilities} namespace) that every served file lives under. */
    private static final String PREFIX = "textures/font/rank/";

    private RankPackResources() {}

    /** Replace the in-memory rank textures. {@code rel} keys are relative to {@link #PREFIX} (e.g. {@code anim/x/0.png}). */
    public static void replaceAll(Map<String, byte[]> relToBytes)
    {
        FILES.clear();
        for (Map.Entry<String, byte[]> e : relToBytes.entrySet())
            FILES.put(PREFIX + e.getKey().replace('\\', '/'), e.getValue());
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... paths)
    {
        return null; // metadata is served directly by getMetadataSection; no root files needed
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location)
    {
        if (type != PackType.CLIENT_RESOURCES || !ShuruisUtilities.MODID.equals(location.getNamespace()))
            return null;
        byte[] bytes = FILES.get(location.getPath());
        return bytes == null ? null : () -> new ByteArrayInputStream(bytes);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput out)
    {
        if (type != PackType.CLIENT_RESOURCES || !ShuruisUtilities.MODID.equals(namespace))
            return;
        String prefix = path.endsWith("/") ? path : path + "/";
        for (Map.Entry<String, byte[]> e : FILES.entrySet())
        {
            String key = e.getKey();
            if (key.equals(path) || key.startsWith(prefix))
            {
                byte[] bytes = e.getValue();
                out.accept(new ResourceLocation(ShuruisUtilities.MODID, key), () -> new ByteArrayInputStream(bytes));
            }
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type)
    {
        return type == PackType.CLIENT_RESOURCES ? Set.of(ShuruisUtilities.MODID) : Set.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer)
    {
        // pack_format 15 = 1.20.1 client resources. Only the "pack" section is provided.
        if ("pack".equals(serializer.getMetadataSectionName()))
            return (T) new PackMetadataSection(Component.literal("Shurui's Utilities rank badges (server-supplied)"), 15);
        return null;
    }

    @Override
    public String packId()
    {
        return "shuruisutilities_rank_badges";
    }

    @Override
    public boolean isBuiltin()
    {
        return true;
    }

    @Override
    public void close() {}
}
