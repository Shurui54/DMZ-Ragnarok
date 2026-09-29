package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.block.AdvancedSpawnerBlockEntity;
import net.shurui.dev.shuruis_dmz_dungeons.compat.WorldEditCompat;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// server-side dungeon ops: paste schematics at exact coords (via WorldEditCompat) and find disguised
// spawners for the highlight command.
public final class DungeonManager {

    private DungeonManager() {
    }

    public static boolean pasteSchematic(ServerLevel level, BlockPos origin, String schematicName) {
        if (!WorldEditCompat.isLoaded()) {
            Shuruis_dmz_dungeons.LOGGER.info("[{}] /rg dungeon paste requested but WorldEdit is not installed - ignoring '{}'.",
                    Shuruis_dmz_dungeons.MODID, schematicName);
            return false;
        }
        File file = resolveSchematic(level, schematicName);
        if (file == null) {
            Shuruis_dmz_dungeons.LOGGER.warn("[{}] Schematic '{}' not found under the world or config/worldedit/schematics.",
                    Shuruis_dmz_dungeons.MODID, schematicName);
            return false;
        }
        return WorldEditCompat.pasteSchematic(level, origin, file);
    }

    // look under <world>/schematics then config/worldedit/schematics; try .schem, .schematic, bare name
    private static File resolveSchematic(ServerLevel level, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        List<Path> dirs = new ArrayList<>();
        dirs.add(level.getServer().getWorldPath(LevelResource.ROOT).resolve("schematics"));
        dirs.add(Path.of("config", "worldedit", "schematics"));
        String[] candidates = name.contains(".")
                ? new String[]{name}
                : new String[]{name + ".schem", name + ".schematic", name};
        for (Path dir : dirs) {
            for (String c : candidates) {
                File f = dir.resolve(c).toFile();
                if (f.isFile()) {
                    return f;
                }
            }
        }
        return null;
    }

    // disguised spawners within radius of center. loaded chunks only (getChunkNow can be null).
    public static List<BlockPos> findDisguisedSpawners(ServerLevel level, BlockPos center, int radius) {
        List<BlockPos> out = new ArrayList<>();
        int r2 = radius * radius;
        int minCx = (center.getX() - radius) >> 4, maxCx = (center.getX() + radius) >> 4;
        int minCz = (center.getZ() - radius) >> 4, maxCz = (center.getZ() + radius) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    if (entry.getValue() instanceof AdvancedSpawnerBlockEntity be) {
                        BlockPos p = entry.getKey();
                        boolean disguised = be.getConfig().disguise != null && !be.getConfig().disguise.isBlank();
                        if (disguised && center.distSqr(p) <= r2) {
                            out.add(p.immutable());
                        }
                    }
                }
            }
        }
        return out;
    }
}
