package net.shurui.shuruisutilities.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.fml.ModList;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Writes a copy of our WORLDGEN JSON into the world's own {@code datapacks} folder, so the world still loads after
 * this mod is uninstalled.
 *
 * <h2>The problem this exists for</h2>
 * Minecraft bakes the whole dimension registry into {@code level.dat}. Once a world has run with this mod, its
 * {@code WorldGenSettings} permanently lists our dimensions, and each entry names a {@code dimension_type}, a
 * {@code noise_settings} and one or more {@code biome} in our namespaces. Remove the mod and none of those resolve,
 * so the load fails outright:
 *
 * <pre>
 *   Failed to get element ResourceKey[minecraft:dimension_type / dmz_ragnarok:planet_surface]; ...
 *   Failed to load level data or datapacks, can't proceed with server load
 * </pre>
 *
 * That is not a warning a player can dismiss. Their world simply will not open, and the only fix without this file
 * is to put the mod back or hand-edit level.dat. A player who tried our mod and moved on is entitled to their world
 * back, so the definitions those entries point at are written INTO the world, where they stay when the jar goes.
 *
 * <h2>What it writes, and what it deliberately does not</h2>
 * Only the four registries a stored dimension entry can reference: {@code dimension}, {@code dimension_type},
 * {@code worldgen/noise_settings} and {@code worldgen/biome}, and only in namespaces this suite owns. That is enough
 * for the world to LOAD; it is not an attempt to keep the mod's content working. Without the jar our dimensions
 * become empty generated worlds and our blocks stay missing (Forge already handles those as the ordinary
 * missing-registry case). Copying anything else would be pretending the mod is still installed.
 *
 * <h2>Idempotent, and quiet</h2>
 * The pack carries a manifest of what was written and the hash of its content. A boot whose content hash matches
 * does nothing at all, so this costs one hash per start and only touches the disk when our worldgen actually
 * changed.
 *
 * <h2>It reads the MOD FILE, not the resource manager, and that is not a detail</h2>
 * The first version collected through the server's {@code ResourceManager}. That looks equivalent and is not: the
 * pack this writes lands in the world's own {@code datapacks} folder, which is itself part of that resource manager
 * and OUTRANKS the mod. So from the second boot onward it read back its own previous output, hashed it, found it
 * unchanged and wrote nothing, while the world went on generating from the copy instead of from the jar. A stale
 * shim does not merely fail to update: a datapack dimension beats the one stored in {@code level.dat}, so every
 * later change to our worldgen was silently dead in any world that had ever written the shim.
 *
 * <p>Reading the mod file directly cannot do that. It is the jar (or, in a dev run, its build output) and nothing
 * else, so what is written is always what this build ships.
 */
public final class WorldgenShim
{

    /** Folder name inside {@code <world>/datapacks}. Stable: renaming it would strand the copy already written. */
    private static final String PACK_DIR = "dmz_ragnarok_worldgen";

    /** Written beside the pack so a boot can tell "already current" from "needs rewriting" without a diff. */
    private static final String MANIFEST = ".dmz_ragnarok_shim_hash";

    /** 1.20.1 data pack format. */
    private static final int PACK_FORMAT = 15;

    /** The registries a level.dat dimension entry can point at. Nothing else is copied. */
    private static final List<String> COPIED_ROOTS =
            List.of("dimension", "dimension_type", "worldgen/noise_settings", "worldgen/biome");

    /** Namespaces this suite owns, including the two it used before the rename stage. */
    private static final Set<String> OUR_NAMESPACES =
            Set.of("dmz_ragnarok", "shuruisutilities", "shuruis_dmz_dungeons", "sdu");

    private WorldgenShim()
    {
    }

    /**
     * Write (or refresh) the shim for this world. Safe to call on every server start; never throws.
     */
    public static void write(MinecraftServer server)
    {
        if (server == null)
        {
            return;
        }
        try
        {
            Map<String, byte[]> files = collect();
            if (files.isEmpty())
            {
                // Nothing of ours is loaded, so there is nothing in level.dat to keep alive either.
                return;
            }
            Path packRoot = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_DIR);
            String hash = hash(files);
            Path manifest = packRoot.resolve(MANIFEST);
            if (Files.isRegularFile(manifest)
                    && hash.equals(Files.readString(manifest, StandardCharsets.UTF_8).trim()))
            {
                return; // already current
            }

            for (Map.Entry<String, byte[]> entry : files.entrySet())
            {
                Path out = packRoot.resolve(entry.getKey());
                Files.createDirectories(out.getParent());
                Files.write(out, entry.getValue());
            }
            Files.write(packRoot.resolve("pack.mcmeta"), packMeta().getBytes(StandardCharsets.UTF_8));
            Files.write(manifest, hash.getBytes(StandardCharsets.UTF_8));
            LoggingHandler.sulog.info("[WorldgenShim] Wrote {} worldgen file(s) into the world's datapacks folder"
                    + " ({}). This is what lets this world still load if the mod is ever removed.",
                    files.size(), packRoot);
        }
        catch (Throwable t)
        {
            // A world that cannot be written to (read-only mount, a permissions problem) must not stop the server
            // starting. The consequence is only that this world would need the mod to keep loading.
            LoggingHandler.sulog.warn("[WorldgenShim] Could not write the worldgen shim into the world folder: {}."
                    + " The world will need this mod installed to load.", t.toString());
        }
    }

    /**
     * Every worldgen file we own, keyed by its path inside the pack ({@code data/<ns>/<root>/<file>}).
     *
     * <p>Taken from this mod's own file, walked directly. NOT from the resource manager: the pack this writes is
     * loaded by that same manager at a higher priority than the mod, so collecting there would read back the last
     * copy of itself instead of the jar. See the class note.
     */
    private static Map<String, byte[]> collect()
    {
        Map<String, byte[]> out = new java.util.TreeMap<>();
        IModFileInfo info = ModList.get().getModFileById(ShuruisUtilities.MODID);
        if (info == null || info.getFile() == null)
        {
            return out;
        }
        for (String namespace : OUR_NAMESPACES)
        {
            for (String root : COPIED_ROOTS)
            {
                Path dir;
                try
                {
                    dir = info.getFile().findResource("data", namespace, root);
                }
                catch (Throwable t)
                {
                    continue; // a root this namespace does not use
                }
                if (dir == null || !Files.isDirectory(dir))
                {
                    continue;
                }
                try (java.util.stream.Stream<Path> walk = Files.walk(dir))
                {
                    for (Path file : walk.filter(Files::isRegularFile).toList())
                    {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(".json"))
                        {
                            continue;
                        }
                        // The path inside the pack mirrors the path inside the jar, including any sub-folders a
                        // registry uses (worldgen/biome has none today; nothing here assumes that).
                        String relative = dir.relativize(file).toString().replace('\\', '/');
                        out.put("data/" + namespace + "/" + root + "/" + relative, Files.readAllBytes(file));
                    }
                }
                catch (IOException e)
                {
                    // One unreadable folder is not worth abandoning the rest: the shim is still better with the
                    // others in it than not written at all.
                    LoggingHandler.sulog.warn("[WorldgenShim] Skipped {}/{} ({}).", namespace, root, e.toString());
                }
            }
        }
        return out;
    }

    /** A stable hash over every path and its bytes, so a content change rewrites and nothing else does. */
    private static String hash(Map<String, byte[]> files) throws Exception
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<String> paths = new ArrayList<>(files.keySet());
        paths.sort(Comparator.naturalOrder());
        for (String path : paths)
        {
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update(files.get(path));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String packMeta()
    {
        return "{\n"
                + "  \"pack\": {\n"
                + "    \"pack_format\": " + PACK_FORMAT + ",\n"
                + "    \"description\": \"DMZ Ragnarok worldgen definitions, copied into this world so it still"
                + " loads if the mod is removed. Deleting this pack will stop this world opening without the mod.\"\n"
                + "  }\n"
                + "}\n";
    }
}
