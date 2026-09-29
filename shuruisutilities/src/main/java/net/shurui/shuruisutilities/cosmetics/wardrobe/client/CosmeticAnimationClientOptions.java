package net.shurui.shuruisutilities.cosmetics.wardrobe.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

import net.minecraftforge.fml.loading.FMLPaths;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * CLIENT ONLY preferences for how much of other players' triggered animations this client draws.
 *
 * <p>Not a server config and not a packet: whether YOUR machine draws a slideshow at spawn is your call, not the
 * server's, exactly as the plan proposes. Persisted to a tiny properties file in the config dir so the choice
 * survives a restart. The server still filters by the animation's own radius, so this only ever narrows what is
 * drawn, never widens it.
 */
public final class CosmeticAnimationClientOptions
{
    private CosmeticAnimationClientOptions()
    {
    }

    /** ALL: everyone's animations. SELF: only your own. OFF: none. */
    public enum Mode
    {
        ALL, SELF, OFF;

        public static Mode byName(String s)
        {
            if (s != null)
                for (Mode m : values())
                    if (m.name().equalsIgnoreCase(s.trim()))
                        return m;
            return ALL;
        }
    }

    private static final String FILE = "dmz_ragnarok-animations.properties";

    private static Mode mode = Mode.ALL;

    /** How near an animation must be to this client's camera to be drawn, in blocks. Never widens the server radius. */
    private static int radius = 48;

    /** Whether this client draws the heavy GeckoLib RIGS. Off falls every animation back to its particle style. */
    private static boolean rigs = true;

    private static boolean loaded;

    public static synchronized Mode mode()
    {
        ensureLoaded();
        return mode;
    }

    public static synchronized int radius()
    {
        ensureLoaded();
        return radius;
    }

    public static synchronized boolean rigs()
    {
        ensureLoaded();
        return rigs;
    }

    public static synchronized void setRigs(boolean on)
    {
        ensureLoaded();
        rigs = on;
        save();
    }

    public static synchronized void set(Mode newMode, int newRadius)
    {
        ensureLoaded();
        if (newMode != null)
            mode = newMode;
        radius = Math.max(8, Math.min(96, newRadius));
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
                mode = Mode.byName(props.getProperty("mode", "ALL"));
                rigs = !"false".equalsIgnoreCase(props.getProperty("rigs", "true").trim());
                try
                {
                    radius = Math.max(8, Math.min(96, Integer.parseInt(props.getProperty("radius", "48").trim())));
                }
                catch (NumberFormatException ignored)
                {
                    radius = 48;
                }
            }
        }
        catch (IOException e)
        {
            LoggingHandler.sulog.warn("[Cosmetics] Could not read animation client options: {}", e.toString());
        }
    }

    private static void save()
    {
        try
        {
            Properties props = new Properties();
            props.setProperty("mode", mode.name().toLowerCase(Locale.ROOT));
            props.setProperty("radius", Integer.toString(radius));
            props.setProperty("rigs", Boolean.toString(rigs));
            try (OutputStream out = Files.newOutputStream(path()))
            {
                props.store(out, "DMZ Ragnarok cosmetic animation client options");
            }
        }
        catch (IOException e)
        {
            LoggingHandler.sulog.warn("[Cosmetics] Could not write animation client options: {}", e.toString());
        }
    }
}
