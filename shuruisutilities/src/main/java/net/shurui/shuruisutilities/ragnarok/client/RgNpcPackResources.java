package net.shurui.shuruisutilities.ragnarok.client;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * A built-in client resource pack holding the rgnpc (ninjin) models in memory only, the same arrangement the rank
 * badges use. The server streams the pack at runtime ({@code PacketRgNpcAssets}); {@link RgNpcAssetCache} unzips
 * it into {@link #files} and this pack hands the files to GeckoLib's model loader and the texture manager exactly
 * as if they had shipped in the jar.
 *
 * <p>Unlike the rank pack this serves a whole SUBTREE rather than one flat prefix, because the pack holds both
 * {@code geo/entity/ragnarok/} and {@code textures/entity/ragnarok/}. Zip keys are therefore already the full
 * namespace-relative path and are stored verbatim, with only a guard that refuses anything outside those two
 * prefixes: a zip is an untrusted input, and an entry called {@code ../../} or {@code textures/gui/…} must not be
 * able to shadow a file the jar owns.</p>
 */
public final class RgNpcPackResources implements PackResources
{
    public static final RgNpcPackResources INSTANCE = new RgNpcPackResources();

    /**
     * Namespace-relative resource path (e.g. {@code geo/entity/ragnarok/2stars.geo.json}) -&gt; file bytes.
     *
     * <p>An IMMUTABLE snapshot behind a volatile reference, never a live mutable map. A pack swap publishes a new
     * snapshot in one assignment, so a reader can only ever see the whole old pack or the whole new one. See
     * {@link #replaceAll(Map)} for why that matters.
     */
    private static volatile Map<String, byte[]> files = Map.of();

    /** The only two subtrees this pack may serve. Anything else in the zip is dropped on load. */
    private static final String[] ALLOWED_PREFIXES = {"geo/entity/ragnarok/", "textures/entity/ragnarok/"};

    private RgNpcPackResources() {}

    /**
     * Replace the in-memory model set. Keys are namespace-relative paths, as the server zipped them.
     *
     * <p>The new set is built OFF to the side and published in a single assignment. It used to clear the live map
     * and refill it, which left a window in which the pack was empty or half filled. That window is not theoretical:
     * GeckoLib lists and reads this pack on BACKGROUND threads during a resource reload
     * ({@code GeckoLibCache.loadModels}), so a swap landing mid reload could make a model that was listed fail to
     * read a moment later, which fails the whole reload rather than one model.
     */
    public static void replaceAll(Map<String, byte[]> pathToBytes)
    {
        Map<String, byte[]> next = new HashMap<>();
        for (Map.Entry<String, byte[]> e : pathToBytes.entrySet())
        {
            String path = e.getKey().replace('\\', '/');
            if (allowed(path))
                next.put(path, e.getValue());
        }
        files = Map.copyOf(next);
        // The race-model aliases and the rig rewrites are built FROM these files, so a new pack must not keep
        // serving old conversions. Cleared AFTER the swap: a derived value rebuilt in between is rebuilt from the
        // new snapshot, where clearing first could have let a racing reader re-memoise the OLD bytes for good.
        RgNpcRaceModels.clearCache();
        RgNpcRig.clearCache();
    }

    /** How many files are currently served. Used only for logging. */
    public static int size()
    {
        return files.size();
    }

    /** Whether this exact resource is one we hold, so a caller can fall back before GeckoLib throws. */
    public static boolean has(String path)
    {
        return files.containsKey(path);
    }

    private static boolean allowed(String path)
    {
        if (path.contains(".."))
            return false;
        for (String prefix : ALLOWED_PREFIXES)
        {
            if (path.startsWith(prefix))
                return true;
        }
        return false;
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... paths)
    {
        return null; // metadata is served directly by getMetadataSection; no root files needed
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location)
    {
        if (type != PackType.CLIENT_RESOURCES)
            return null;

        // One snapshot for the whole call, so a pack swap landing halfway through cannot answer out of two packs.
        Map<String, byte[]> snapshot = files;

        // DMZ's own namespace, for a race whose customModel names an rgnpc entry. See RgNpcRaceModels: the geo is
        // converted on the way out (hand-item bones) and the texture is served as authored.
        if (RgNpcRaceModels.DMZ_NAMESPACE.equals(location.getNamespace()))
        {
            byte[] alias = RgNpcRaceModels.resolve(location.getPath(), snapshot::get);
            return alias == null ? null : () -> new ByteArrayInputStream(alias);
        }

        if (!ShuruisUtilities.MODID.equals(location.getNamespace()))
            return null;
        // Through RgNpcRig, so the ENTITIES get the corrected torso too. The race alias above reaches the same rule
        // by its own route, because it reads the raw bytes out of the snapshot rather than coming back through here.
        byte[] bytes = RgNpcRig.normalised(location.getPath(), snapshot.get(location.getPath()));
        return bytes == null ? null : () -> new ByteArrayInputStream(bytes);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput out)
    {
        if (type != PackType.CLIENT_RESOURCES)
            return;

        // One snapshot for the listing AND for every lazy supplier it hands out. A supplier that read the live
        // field instead could be told about a model by one pack and then asked to produce it out of another.
        Map<String, byte[]> snapshot = files;

        // sdu's race editor builds its model dropdown by LISTING dragonminez:geo/entity/races, so an alias that is
        // only readable would render in game and never be pickable. Enumerate them here for exactly that reason.
        if (RgNpcRaceModels.DMZ_NAMESPACE.equals(namespace))
        {
            String want = path.endsWith("/") ? path : path + "/";
            RgNpcRaceModels.forEachGeoPath(snapshot::containsKey, alias ->
            {
                if (!alias.equals(path) && !alias.startsWith(want))
                    return;
                // LAZY on purpose. A listing is what fills the race editor's model dropdown, and converting all
                // 386 models to answer "what exists" would parse and rewrite every geo to populate a list. The
                // supplier runs only if something actually reads the model.
                out.accept(new ResourceLocation(RgNpcRaceModels.DMZ_NAMESPACE, alias), () ->
                {
                    byte[] bytes = RgNpcRaceModels.resolve(alias, snapshot::get);
                    if (bytes == null)
                        throw new java.io.FileNotFoundException(alias);
                    return new ByteArrayInputStream(bytes);
                });
            });
            return;
        }

        if (!ShuruisUtilities.MODID.equals(namespace))
            return;
        String prefix = path.endsWith("/") ? path : path + "/";
        for (Map.Entry<String, byte[]> e : snapshot.entrySet())
        {
            String key = e.getKey();
            if (key.equals(path) || key.startsWith(prefix))
            {
                byte[] bytes = e.getValue();
                // LAZY, like the alias listing above: rewriting every geo to answer "what exists" would parse the
                // whole pack for a list nothing may read.
                out.accept(new ResourceLocation(ShuruisUtilities.MODID, key),
                        () -> new ByteArrayInputStream(RgNpcRig.normalised(key, bytes)));
            }
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type)
    {
        // dragonminez is listed because a race model alias lives under DMZ's namespace; the resource manager never
        // asks a pack for a namespace it does not claim.
        return type == PackType.CLIENT_RESOURCES
                ? Set.of(ShuruisUtilities.MODID, RgNpcRaceModels.DMZ_NAMESPACE)
                : Set.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer)
    {
        // pack_format 15 = 1.20.1 client resources. Only the "pack" section is provided.
        if ("pack".equals(serializer.getMetadataSectionName()))
            return (T) new PackMetadataSection(Component.literal("Ragnarok NPC models (server-supplied)"), 15);
        return null;
    }

    @Override
    public String packId()
    {
        return "shuruisutilities_rgnpc_models";
    }

    @Override
    public boolean isBuiltin()
    {
        return true;
    }

    @Override
    public void close() {}
}
