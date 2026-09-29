package net.shurui.shuruisutilities.compat.distanthorizons;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * The deferred half of the Distant Horizons purge. The in-game command ({@link DhPurgeCommand}) cannot delete the
 * current server's DH data itself: while the player is connected, DH holds that server's per-level SQLite files OPEN,
 * so deleting them mid-session either fails (Windows file locks) or leaves DH writing back into a half-deleted tree.
 * The only window where DH is guaranteed not to hold those files is BEFORE it opens them, which is before the player
 * joins a server, which at the earliest is client startup.
 *
 * <p>So the command writes a small marker recording the absolute folder to remove, and this class, at
 * {@link FMLClientSetupEvent} (client startup, no server connected, DH has opened nothing), reads the marker, re-runs
 * the same {@link DhClientData#isSafeTarget safety gate}, deletes, logs, and clears the marker. The delete happens once
 * per marker and then the marker is gone, so a single restart completes it.</p>
 *
 * <p>This does not classload Distant Horizons and does not need DH present to run: deleting a stale data folder is
 * valid whether or not DH is still installed.</p>
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DhPurgePending
{
    private DhPurgePending() {}

    private static final String KEY_PATH = "path";
    private static final String KEY_LABEL = "label";
    private static final String KEY_FILES = "files";
    private static final String KEY_BYTES = "bytes";

    /** {@code <gamedir>/ShuruisUtilities/dhpurge/pending.properties}. One pending purge at a time is all we need. */
    private static Path markerFile()
    {
        return FMLPaths.GAMEDIR.get().resolve(ShuruisUtilities.SU_DIRECTORY).resolve("dhpurge").resolve("pending.properties");
    }

    /**
     * Record a pending purge for the given DH server folder. Overwrites any previous marker (the newest request wins).
     * The path is stored absolute and normalised so the startup pass does not depend on the working directory.
     */
    public static void markPending(Path serverDir, String label, long files, long bytes) throws IOException
    {
        Path marker = markerFile();
        Files.createDirectories(marker.getParent());
        Properties props = new Properties();
        props.setProperty(KEY_PATH, serverDir.toAbsolutePath().normalize().toString());
        props.setProperty(KEY_LABEL, label == null ? "" : label);
        props.setProperty(KEY_FILES, Long.toString(files));
        props.setProperty(KEY_BYTES, Long.toString(bytes));
        try (OutputStream out = Files.newOutputStream(marker))
        {
            props.store(out, "Distant Horizons data marked for removal at next startup. Delete this file to cancel.");
        }
    }

    /** True when a purge is queued, so the command can tell the player one is already pending. */
    public static boolean hasPending()
    {
        return Files.isRegularFile(markerFile());
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event)
    {
        // Filesystem work only, no render or GL state, so it runs directly on the mod-loading thread rather than
        // through enqueueWork. This is intentionally BEFORE any world load, which is the only safe time to delete.
        Path marker = markerFile();
        if (!Files.isRegularFile(marker))
            return;

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(marker))
        {
            props.load(in);
        }
        catch (IOException e)
        {
            LoggingHandler.sulog.warn("Could not read Distant Horizons purge marker, leaving it in place: " + e.getMessage());
            return;
        }

        String pathStr = props.getProperty(KEY_PATH);
        String label = props.getProperty(KEY_LABEL, "");
        if (pathStr == null || pathStr.isEmpty())
        {
            clearMarker(marker);
            return;
        }

        Path target = Path.of(pathStr);
        if (!DhClientData.isSafeTarget(target))
        {
            // The stored path no longer looks like a DH server folder under the game directory (folder gone, moved,
            // or tampered with). Refuse and drop the marker rather than risk deleting anything unexpected.
            LoggingHandler.sulog.warn("Distant Horizons purge marker pointed at an unsafe or missing path, skipping: " + pathStr);
            clearMarker(marker);
            return;
        }

        try
        {
            long removed = DhClientData.deleteGuarded(target);
            if (removed < 0L)
                LoggingHandler.sulog.warn("Distant Horizons purge refused by the safety gate at delete time: " + pathStr);
            else
                LoggingHandler.sulog.info("Purged Distant Horizons data for " + (label.isEmpty() ? pathStr : label)
                        + ": removed " + removed + " files.");
        }
        catch (IOException e)
        {
            LoggingHandler.sulog.warn("Distant Horizons purge failed partway, leaving what remains: " + e.getMessage());
        }
        finally
        {
            clearMarker(marker);
        }
    }

    private static void clearMarker(Path marker)
    {
        try
        {
            Files.deleteIfExists(marker);
        }
        catch (IOException ignored)
        {
            // if we cannot delete the marker, the worst case is a redundant no-op purge next start
        }
    }
}
