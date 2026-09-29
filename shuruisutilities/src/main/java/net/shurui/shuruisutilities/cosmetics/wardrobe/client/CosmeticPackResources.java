package net.shurui.shuruisutilities.cosmetics.wardrobe.client;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.IoSupplier;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticAssetPaths;

/**
 * A built-in client resource pack holding the streamed cosmetic art in memory, at the same {@code dmz_ragnarok:}
 * locations the jar used to ship it at, so item models, the worn variants, the GeckoLib rigs, the texture atlas and
 * the sound engine find everything exactly as before. Same arrangement as {@code RgNpcPackResources}: an immutable
 * snapshot behind a volatile reference (a swap mid reload can never answer out of two packs), and a prefix
 * allow-list ({@link CosmeticAssetPaths}) so a zip entry can never shadow a file a jar owns.
 */
public final class CosmeticPackResources implements PackResources
{
    public static final CosmeticPackResources INSTANCE = new CosmeticPackResources();

    /** Namespace-relative path (e.g. {@code geo/entity/cosmetic_pet/hw_pet_devil.geo.json}) to bytes. */
    private static volatile Map<String, byte[]> files = Map.of();

    private CosmeticPackResources() {}

    /** Replace the served set in one assignment. Keys are namespace-relative paths, as the key zipped them. */
    public static void replaceAll(Map<String, byte[]> pathToBytes)
    {
        Map<String, byte[]> next = new HashMap<>();
        for (Map.Entry<String, byte[]> e : pathToBytes.entrySet())
        {
            String path = e.getKey().replace('\\', '/');
            if (CosmeticAssetPaths.allowed(path))
                next.put(path, e.getValue());
        }
        files = Map.copyOf(next);
    }

    /** How many files are served. Logging and the round-trip probe only. */
    public static int size()
    {
        return files.size();
    }

    /** The served set as an immutable snapshot (the round-trip probe compares it byte for byte). */
    public static Map<String, byte[]> snapshot()
    {
        return files;
    }

    /** Whether this exact namespace-relative path is currently served. */
    public static boolean has(String path)
    {
        return files.containsKey(path);
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... paths)
    {
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location)
    {
        if (type != PackType.CLIENT_RESOURCES || !ShuruisUtilities.MODID.equals(location.getNamespace()))
            return null;
        byte[] bytes = files.get(location.getPath());
        return bytes == null ? null : () -> new ByteArrayInputStream(bytes);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput out)
    {
        if (type != PackType.CLIENT_RESOURCES || !ShuruisUtilities.MODID.equals(namespace))
            return;
        Map<String, byte[]> snapshot = files;
        String prefix = path.endsWith("/") ? path : path + "/";
        for (Map.Entry<String, byte[]> e : snapshot.entrySet())
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
            return (T) new PackMetadataSection(Component.literal("DMZ Ragnarok cosmetic art (server-supplied)"), 15);
        return null;
    }

    @Override
    public String packId()
    {
        return "dmz_ragnarok_cosmetic_art";
    }

    @Override
    public boolean isBuiltin()
    {
        return true;
    }

    @Override
    public void close() {}
}
