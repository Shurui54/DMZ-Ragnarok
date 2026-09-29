package net.shurui.dev.sdu.client.container;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.fml.ModList;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.client.ClientConfig;

/**
 * A built-in client resource pack that overrides Minecraft's container GUI textures (and, when Curios is
 * present, the Curios sidebar textures) with sdu's slot art. The restyled PNGs ship inside the sdu jar under
 * {@code /sdu_container_reskin/<namespace>/...}; this pack maps a request for {@code minecraft:textures/gui/...}
 * (or {@code curios:textures/gui/...}) onto that private path and streams the bytes back, so the vanilla GUI
 * pipeline picks up the reskinned art without a static override in {@code assets/minecraft}.
 *
 * <p>Client-only: only ever loaded from the client resource-pack path (see {@link ContainerReskinPackFinder}).
 * The pack is registered when EITHER {@code ClientConfig#reskinContainers} or {@code ClientConfig#reskinCurios}
 * is true, and each namespace is then served only if its own switch is on. Two switches, not one, because the
 * Curios art is finished while the vanilla container art is still placeholder.
 *
 * <p>Curios handling is guarded twice over: the {@code curios} namespace is only advertised, and its files are
 * only served, when {@code reskinCurios} is on AND the {@code curios} mod is actually loaded. With Curios absent
 * nothing ever requests a {@code curios:} resource and this pack never touches a Curios class, so a missing
 * Curios cannot break it.
 */
public final class ContainerReskinPack implements PackResources {

    public static final ContainerReskinPack INSTANCE = new ContainerReskinPack();

    /** Root inside the sdu jar holding the restyled textures, one subfolder per served namespace. */
    private static final String ROOT = "/sdu_container_reskin/";

    private static final String MINECRAFT = "minecraft";
    private static final String CURIOS = "curios";

    private ContainerReskinPack() {
    }

    private static boolean curiosLoaded() {
        return ModList.get().isLoaded(CURIOS);
    }

    /**
     * True for the namespaces this pack is willing to override, honouring the Curios guard AND the two
     * separate config toggles. The vanilla container art and the Curios art are switched independently
     * because the Curios art is finished while the vanilla art is still placeholder: a single switch would
     * have forced anyone who wanted the Curios sidebar to take the placeholders with it.
     */
    private static boolean served(String namespace) {
        if (MINECRAFT.equals(namespace)) {
            return ClientConfig.reskinContainers;
        }
        return CURIOS.equals(namespace) && ClientConfig.reskinCurios && curiosLoaded();
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... paths) {
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type != PackType.CLIENT_RESOURCES || !served(location.getNamespace())) {
            return null;
        }
        String jarPath = ROOT + location.getNamespace() + "/" + location.getPath();
        // Only claim the request if we actually ship this file; otherwise return null so the vanilla
        // (lower-priority) resource is used instead.
        if (ContainerReskinPack.class.getResource(jarPath) == null) {
            return null;
        }
        return () -> {
            InputStream in = ContainerReskinPack.class.getResourceAsStream(jarPath);
            if (in == null) {
                throw new java.io.FileNotFoundException(jarPath);
            }
            return in;
        };
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput out) {
        // The vanilla texture pipeline fetches container/widget textures by exact ResourceLocation, so it
        // never needs this pack to enumerate. Leaving it empty keeps the pack purely an override.
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        if (type != PackType.CLIENT_RESOURCES) {
            return Set.of();
        }
        Set<String> namespaces = new HashSet<>();
        if (served(MINECRAFT)) {
            namespaces.add(MINECRAFT);
        }
        if (served(CURIOS)) {
            namespaces.add(CURIOS);
        }
        return namespaces;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
        // pack_format 15 = 1.20.1 client resources. Only the "pack" section is provided.
        if ("pack".equals(serializer.getMetadataSectionName())) {
            return (T) new PackMetadataSection(
                    Component.literal("Shurui's DMZ Essentials container reskin"), 15);
        }
        return null;
    }

    @Override
    public String packId() {
        return DmzNpc.MODID + "_container_reskin";
    }

    @Override
    public boolean isBuiltin() {
        return true;
    }

    @Override
    public void close() {
    }
}
