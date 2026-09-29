package net.shurui.shuruisutilities.client.cosmetics.pet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.minecraftforge.fml.loading.FMLPaths;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * CLIENT ONLY preference for whether this machine draws cosmetic pets at all.
 *
 * <p>Not a server config and not a packet: whether YOUR machine draws everyone's pets is your call, not the
 * server's, the same shape {@code CosmeticAnimationClientOptions} uses for triggered animations. Persisted to a tiny
 * properties file in the config dir so the choice survives a restart. The server still spawns the pet either way and
 * every other client still sees it; this only stops THIS client drawing them, which is the cheap escape hatch for a
 * low-end machine at a crowded spawn.
 */
public final class CosmeticPetClientOptions
{
    private CosmeticPetClientOptions()
    {
    }

    private static final String FILE = "dmz_ragnarok-pets.properties";

    private static boolean hidden;
    private static boolean loaded;

    /** Whether this client should skip drawing cosmetic pets. */
    public static synchronized boolean hidden()
    {
        ensureLoaded();
        return hidden;
    }

    public static synchronized void setHidden(boolean value)
    {
        ensureLoaded();
        hidden = value;
        save();
    }

    private static Path path()
    {
        return FMLPaths.CONFIGDIR.get().resolve(FILE);
    }

    private static void ensureLoaded()
    {
        if (loaded)
            return;
        loaded = true;
        try
        {
            Path p = path();
            if (Files.exists(p))
            {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(p))
                {
                    props.load(in);
                }
                hidden = Boolean.parseBoolean(props.getProperty("hidden", "false").trim());
            }
        }
        catch (IOException e)
        {
            LoggingHandler.sulog.warn("[Cosmetics] Could not read pet client options: {}", e.toString());
        }
    }

    private static void save()
    {
        try
        {
            Properties props = new Properties();
            props.setProperty("hidden", Boolean.toString(hidden));
            try (OutputStream out = Files.newOutputStream(path()))
            {
                props.store(out, "DMZ Ragnarok cosmetic pet client options");
            }
        }
        catch (IOException e)
        {
            LoggingHandler.sulog.warn("[Cosmetics] Could not write pet client options: {}", e.toString());
        }
    }
}
