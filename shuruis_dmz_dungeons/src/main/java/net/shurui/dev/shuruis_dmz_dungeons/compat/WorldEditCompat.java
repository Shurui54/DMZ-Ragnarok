package net.shurui.dev.shuruis_dmz_dungeons.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.ModList;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

// reflection bridge to WorldEdit (SOFT dep). every access is reflective + try/catch, so if WorldEdit is
// absent (or its API shifts) the paste methods just log and no-op instead of crashing.
//
// reflective calls target WorldEdit 7.2.x public API:
//   ClipboardFormat fmt   = ClipboardFormats.findByFile(file);
//   Clipboard       clip  = fmt.getReader(in).read();
//   World           world = ForgeAdapter.adapt(serverLevel);
//   EditSession     es    = WorldEdit.getInstance().newEditSession(world);
//   Operation       op    = new ClipboardHolder(clip).createPaste(es).to(BlockVector3.at(x,y,z)).ignoreAirBlocks(false).build();
//   Operations.complete(op); es.close();
// if the WorldEdit build differs, the class/method names in pasteSchematic are the only coupling point.
public final class WorldEditCompat {

    private WorldEditCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("worldedit");
    }

    // paste a .schem/.schematic at the exact origin. true on success; logs + false (no-op) if WorldEdit is
    // missing, the file can't be read, or the reflective call fails. must run on the server thread.
    public static boolean pasteSchematic(ServerLevel level, BlockPos origin, File schematic) {
        if (!isLoaded()) {
            Shuruis_dmz_dungeons.LOGGER.info("[{}] WorldEdit not installed - skipping schematic paste of {} (this is fine; the feature is optional).",
                    Shuruis_dmz_dungeons.MODID, schematic == null ? "null" : schematic.getName());
            return false;
        }
        if (schematic == null || !schematic.isFile()) {
            Shuruis_dmz_dungeons.LOGGER.warn("[{}] Schematic file not found: {}", Shuruis_dmz_dungeons.MODID, schematic);
            return false;
        }

        try {
            // 1) resolve the clipboard format by extension/magic and read it
            Class<?> formatsCls = Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats");
            Object format = formatsCls.getMethod("findByFile", File.class).invoke(null, schematic);
            if (format == null) {
                Shuruis_dmz_dungeons.LOGGER.warn("[{}] WorldEdit did not recognise the schematic format of {}.",
                        Shuruis_dmz_dungeons.MODID, schematic.getName());
                return false;
            }
            Object clipboard;
            try (InputStream in = new BufferedInputStream(new FileInputStream(schematic))) {
                Object reader = format.getClass().getMethod("getReader", InputStream.class).invoke(format, in);
                clipboard = reader.getClass().getMethod("read").invoke(reader);
            }

            // 2) adapt the Forge level to a WorldEdit World
            Class<?> weWorldCls = Class.forName("com.sk89q.worldedit.world.World");
            Object weWorld = adaptWorld(level, weWorldCls);
            if (weWorld == null) {
                Shuruis_dmz_dungeons.LOGGER.warn("[{}] Could not adapt level {} to a WorldEdit world.",
                        Shuruis_dmz_dungeons.MODID, level.dimension().location());
                return false;
            }

            // 3) open an EditSession for that world
            Object worldEdit = Class.forName("com.sk89q.worldedit.WorldEdit").getMethod("getInstance").invoke(null);
            Object editSession = worldEdit.getClass().getMethod("newEditSession", weWorldCls).invoke(worldEdit, weWorld);

            try {
                // 4) build the paste op at the exact origin (ignoreAirBlocks false so it clears/overwrites)
                Class<?> clipboardCls = Class.forName("com.sk89q.worldedit.extent.clipboard.Clipboard");
                Object holder = Class.forName("com.sk89q.worldedit.session.ClipboardHolder")
                        .getConstructor(clipboardCls).newInstance(clipboard);

                Class<?> bv3 = Class.forName("com.sk89q.worldedit.math.BlockVector3");
                Object to = bv3.getMethod("at", int.class, int.class, int.class)
                        .invoke(null, origin.getX(), origin.getY(), origin.getZ());

                Class<?> extentCls = Class.forName("com.sk89q.worldedit.extent.Extent");
                Object pasteBuilder = holder.getClass().getMethod("createPaste", extentCls).invoke(holder, editSession);
                pasteBuilder = pasteBuilder.getClass().getMethod("to", bv3).invoke(pasteBuilder, to);
                pasteBuilder = pasteBuilder.getClass().getMethod("ignoreAirBlocks", boolean.class).invoke(pasteBuilder, false);
                Object operation = pasteBuilder.getClass().getMethod("build").invoke(pasteBuilder);

                // 5) run it to completion
                Class<?> operationCls = Class.forName("com.sk89q.worldedit.function.operation.Operation");
                Class.forName("com.sk89q.worldedit.function.operation.Operations")
                        .getMethod("complete", operationCls).invoke(null, operation);
            } finally {
                // EditSession is AutoCloseable in 7.2; close flushes the changes to the world
                try {
                    editSession.getClass().getMethod("close").invoke(editSession);
                } catch (Throwable ignored) {
                }
            }

            Shuruis_dmz_dungeons.LOGGER.info("[{}] Pasted schematic {} at {} in {}.", Shuruis_dmz_dungeons.MODID,
                    schematic.getName(), origin, level.dimension().location());
            return true;
        } catch (Throwable t) {
            Shuruis_dmz_dungeons.LOGGER.warn("[{}] WorldEdit schematic paste failed ({}). WorldEdit present but API mismatch?",
                    Shuruis_dmz_dungeons.MODID, t.toString());
            return false;
        }
    }

    // ForgeAdapter.adapt(ServerLevel) -> World. it has several overloaded adapt() statics; pick the one that
    // accepts our level (or a supertype) and returns a WE World.
    private static Object adaptWorld(ServerLevel level, Class<?> weWorldCls) {
        try {
            Class<?> adapterCls = Class.forName("com.sk89q.worldedit.forge.ForgeAdapter");
            for (Method m : adapterCls.getMethods()) {
                if (!m.getName().equals("adapt") || m.getParameterCount() != 1) {
                    continue;
                }
                if (m.getParameterTypes()[0].isInstance(level) && weWorldCls.isAssignableFrom(m.getReturnType())) {
                    return m.invoke(null, level);
                }
            }
        } catch (Throwable t) {
            Shuruis_dmz_dungeons.LOGGER.debug("[{}] ForgeAdapter.adapt lookup failed: {}", Shuruis_dmz_dungeons.MODID, t.toString());
        }
        return null;
    }
}
