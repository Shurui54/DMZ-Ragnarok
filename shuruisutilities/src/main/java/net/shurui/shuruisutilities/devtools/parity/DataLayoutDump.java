package net.shurui.shuruisutilities.devtools.parity;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The persisted-data layout: the directory tree under the SU data root and the SavedData names in play.
 *
 * <p>SU's {@code DataManager.getTypePath} names a folder after a Java simple class name, so renaming a persisted
 * class silently orphans its live data. Recording the folder tree here turns that silent rename into a visible diff.
 * Only directories are listed (not individual player files, which vary per world), so the output is deterministic for
 * a given world+build. SavedData names are captured from the {@code .dat} files on disk under the world's data
 * folders.
 *
 * <p>The overworld {@code DimensionDataStorage} in-memory cache is deliberately NOT dumped: which SavedData happens
 * to be loaded at {@code ServerStartedEvent} depends on lazy-load timing and so is not deterministic between two runs
 * of the same build. On-disk {@code .dat} names are the stable "SavedData names present" signal; run the determinism
 * check against a world that has already been booted and saved once, so the on-disk set is settled.
 */
final class DataLayoutDump {

    private DataLayoutDump() {}

    static String dump(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        suDataTree(lines);
        savedData(server, lines);
        return ParityDump.sortedBlock("persisted data layout", lines);
    }

    private static void suDataTree(List<String> out) {
        try {
            Class<?> su = Class.forName("net.shurui.shuruisutilities.core.ShuruisUtilities");
            Object rootObj = su.getMethod("getSUDataRoot").invoke(null);
            if (!(rootObj instanceof File root) || !root.isDirectory()) {
                out.add("SUDATA-ROOT <absent>");
                return;
            }
            Path base = root.toPath();
            out.add("SUDATA-ROOT " + root.getName());
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isDirectory)
                        .filter(p -> !p.equals(base))
                        .map(p -> base.relativize(p).toString().replace(File.separatorChar, '/'))
                        .forEach(rel -> out.add("SUDATA-DIR " + rel));
            }
        } catch (Throwable t) {
            out.add("SUDATA-ROOT <error: " + t + ">");
        }
    }

    private static void savedData(MinecraftServer server, List<String> out) {
        // .dat files on disk under any "data" folder in the world save.
        try {
            Path world = server.getWorldPath(LevelResource.ROOT);
            TreeSet<String> dats = new TreeSet<>();
            try (Stream<Path> walk = Files.walk(world, 4)) {
                walk.filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".dat"))
                        .filter(p -> p.getParent() != null && p.getParent().getFileName() != null
                                && p.getParent().getFileName().toString().equals("data"))
                        .forEach(p -> dats.add(p.getFileName().toString()));
            }
            for (String d : dats) {
                out.add("SAVEDDATA-FILE " + d);
            }
        } catch (Throwable t) {
            out.add("SAVEDDATA-FILE <error: " + t + ">");
        }
    }
}
