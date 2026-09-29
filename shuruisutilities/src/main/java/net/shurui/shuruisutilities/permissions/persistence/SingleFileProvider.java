package net.shurui.shuruisutilities.permissions.persistence;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import com.google.gson.JsonParseException;

import net.shurui.shuruisutilities.api.permissions.ServerZone;
import net.shurui.shuruisutilities.data.v2.DataManager;
import net.shurui.shuruisutilities.permissions.core.ZonePersistenceProvider;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

public class SingleFileProvider extends ZonePersistenceProvider
{

    private File file;

    public SingleFileProvider()
    {
        file = new File(net.shurui.shuruisutilities.core.ShuruisUtilities.getSUDataRoot(), "permissions.json");
    }

    public SingleFileProvider(File file)
    {
        this.file = file;
    }

    @Override
    public ServerZone load()
    {
        // No file yet => legitimate first run. Report ABSENT so the caller may seed defaults / save normally.
        if (!file.exists())
        {
            lastLoadOutcome = LoadOutcome.ABSENT;
            return null;
        }
        try (BufferedReader in = Files.newBufferedReader(file.toPath(), Charset.forName("UTF-8")))
        {
            ServerZone serverZone = DataManager.getGson().fromJson(in, ServerZone.class);
            if (serverZone == null)
            {
                // File exists but deserialized to null (e.g. empty/blank file). Treat as unreadable so saving is
                // blocked rather than silently overwriting whatever is on disk with an empty zone.
                lastLoadOutcome = LoadOutcome.FAILED;
                LoggingHandler.sulog.error(
                        "[Permissions] permissions file \"" + file.getAbsolutePath()
                                + "\" parsed to null (empty or blank). Treating as unreadable; saving will be disabled to protect the on-disk data.");
                return null;
            }
            serverZone.afterLoad();
            readUserGroupPermissions(serverZone);
            lastLoadOutcome = LoadOutcome.LOADED;
            return serverZone;
        }
        catch (JsonParseException e)
        {
            // malformed JSON: DataManager's Gson throws JsonSyntaxException/JsonParseException (unchecked),
            // which used to escape this method entirely. catch it, mark FAILED, log loudly.
            lastLoadOutcome = LoadOutcome.FAILED;
            LoggingHandler.sulog.error(
                    "[Permissions] Failed to parse permissions file \"" + file.getAbsolutePath()
                            + "\" (malformed JSON). Saving will be disabled to protect the on-disk data.",
                    e);
        }
        catch (IOException e)
        {
            // present but unreadable (IO error), also unsafe to overwrite, mark FAILED
            lastLoadOutcome = LoadOutcome.FAILED;
            LoggingHandler.sulog.error(
                    "[Permissions] Failed to read permissions file \"" + file.getAbsolutePath()
                            + "\". Saving will be disabled to protect the on-disk data.",
                    e);
        }
        return null;
    }

    public File getFile()
    {
        return file;
    }

    @Override
    public void save(ServerZone serverZone)
    {
        writeUserGroupPermissions(serverZone);
        // Atomic write: serialize to a sibling temp file, then move it into place so a crash or serialization
        // exception mid-write can only corrupt the throwaway temp file, never the existing permissions.json.
        File parent = file.getParentFile();
        if (parent != null)
            parent.mkdirs();
        File temp = new File(parent, file.getName() + ".tmp");
        boolean moved = false;
        try
        {
            try (BufferedWriter out = Files.newBufferedWriter(temp.toPath(), Charset.forName("UTF-8")))
            {
                out.write(DataManager.getGson().toJson(serverZone));
            }
            try
            {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException atomicUnsupported)
            {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        }
        catch (IOException e)
        {
            // Failure before the move: permissions.json is left intact. Preserve the previous swallow-and-log
            // behaviour for IOException.
            e.printStackTrace();
        }
        catch (RuntimeException serializationFailure)
        {
            // The comment above always CLAIMED a serialization exception could only hurt the temp file, but only
            // IOException was caught, so an unchecked one escaped the whole save. Gson throws unchecked
            // (JsonIOException / JsonSyntaxException / StackOverflowError's cousins), and because this save runs
            // from the ServerStarted handler on the server thread, one killed the tick loop outright on a 1.1.72
            // server on 2026-08-27 (see Point.blockPos). A permissions file that fails to write is bad; a server that
            // will not boot because of it is far worse, so it is logged loudly and the boot continues with
            // permissions.json exactly as it was.
            LoggingHandler.sulog.error(
                    "[Permissions] FAILED to serialize the permission zone to \"" + file.getAbsolutePath()
                            + "\". The existing file was NOT modified and the server is continuing. This is a bug:"
                            + " something in the zone graph is not serializable. Report it with this stack trace.",
                    serializationFailure);
        }
        finally
        {
            // Clean up the leftover temp file on any failure so we never leave a stale ".tmp" behind.
            if (!moved)
            {
                try
                {
                    Files.deleteIfExists(temp.toPath());
                }
                catch (IOException cleanupFailure)
                {
                    cleanupFailure.printStackTrace();
                }
            }
        }
    }

}
