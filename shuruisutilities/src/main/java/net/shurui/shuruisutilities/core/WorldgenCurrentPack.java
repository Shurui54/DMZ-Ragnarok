package net.shurui.shuruisutilities.core;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * A built-in data pack, pinned at the TOP, that serves this build's own {@code dimension_type} JSON so it outranks the
 * world's {@link WorldgenShim} copy.
 *
 * <p>Why: the shim lives in {@code <world>/datapacks}, which outranks the mod, and it is only rewritten at server start,
 * after the registries have already loaded from it. So on the FIRST start after an update that changes a dimension type,
 * the world still ran the old one. That mattered when space grew from -64..1984 to -2032..2032 (2026-10-22): the first
 * boot kept the old column ("That position is out of this world!" below Y -64), while the new layout put planets down to
 * Y -1776, where a player would have taken void damage. This pack is built from the same mod-file read the shim uses,
 * so it always equals what this build ships, and the shim still keeps the world loadable if the mod is ever removed.
 * Only {@code dimension_type} is served (the registry a dimension's height lives in); the other worldgen roots keep
 * their old order.
 */
@Mod.EventBusSubscriber(modid = ShuruisUtilities.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class WorldgenCurrentPack implements PackResources
{
    private static final String ID = "dmz_ragnarok_dimension_types";
    private static final String ROOT = "dimension_type";

    // data/<ns>/dimension_type/<file> -> bytes, read once from the mod file.
    private final Map<String, byte[]> files;

    private WorldgenCurrentPack(Map<String, byte[]> files)
    {
        this.files = files;
    }

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event)
    {
        if (event.getPackType() != PackType.SERVER_DATA)
        {
            return;
        }
        Map<String, byte[]> mine = new TreeMap<>();
        try
        {
            for (Map.Entry<String, byte[]> e : WorldgenShim.collect().entrySet())
            {
                String[] parts = e.getKey().split("/", 4);   // data, ns, root, file
                if (parts.length == 4 && ROOT.equals(parts[2]))
                {
                    mine.put(e.getKey(), e.getValue());
                }
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[WorldgenShim] Could not read the dimension types for the current pack: {}.",
                    t.toString());
            return;
        }
        if (mine.isEmpty())
        {
            return;
        }
        WorldgenCurrentPack resources = new WorldgenCurrentPack(mine);
        event.addRepositorySource(onLoad ->
        {
            Pack.ResourcesSupplier supplier = id -> resources;
            Pack.Info info = Pack.readPackInfo(ID, supplier);
            if (info == null)
            {
                return;
            }
            // required + fixed at the top: it can never be ordered under the world's shim, and it is always enabled.
            onLoad.accept(Pack.create(ID, Component.literal("DMZ Ragnarok dimension types"), true, supplier, info,
                    PackType.SERVER_DATA, Pack.Position.TOP, true, PackSource.BUILT_IN));
        });
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... paths)
    {
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location)
    {
        if (type != PackType.SERVER_DATA)
        {
            return null;
        }
        byte[] bytes = files.get("data/" + location.getNamespace() + "/" + location.getPath());
        return bytes == null ? null : () -> new ByteArrayInputStream(bytes);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput out)
    {
        if (type != PackType.SERVER_DATA)
        {
            return;
        }
        String prefix = "data/" + namespace + "/" + path + "/";
        for (Map.Entry<String, byte[]> e : files.entrySet())
        {
            if (e.getKey().startsWith(prefix))
            {
                byte[] bytes = e.getValue();
                String rel = e.getKey().substring(("data/" + namespace + "/").length());
                out.accept(new ResourceLocation(namespace, rel), () -> new ByteArrayInputStream(bytes));
            }
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type)
    {
        Set<String> out = new HashSet<>();
        if (type == PackType.SERVER_DATA)
        {
            for (String key : files.keySet())
            {
                out.add(key.split("/", 3)[1]);
            }
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer)
    {
        if ("pack".equals(serializer.getMetadataSectionName()))
        {
            return (T) new PackMetadataSection(Component.literal("DMZ Ragnarok dimension types"), 15);
        }
        return null;
    }

    @Override
    public String packId()
    {
        return ID;
    }

    @Override
    public boolean isBuiltin()
    {
        return true;
    }

    @Override
    public void close()
    {
    }
}
