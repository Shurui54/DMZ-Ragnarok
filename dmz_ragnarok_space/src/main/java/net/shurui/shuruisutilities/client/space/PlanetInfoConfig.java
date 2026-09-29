package net.shurui.shuruisutilities.client.space;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Persistent client-side preference for the floating planet-info overlay: whether it is switched ON. Stored as
 * config/shuruisutilities-planetinfo.json exactly like {@code RegionHudConfig}, so the player's choice survives a
 * restart. This is a pure client PREFERENCE (nothing gameplay-authoritative lives here), which is why it is a small local
 * json and not a synced server config: the server never needs to know whether a given client is currently drawing the
 * panel.
 */
public final class PlanetInfoConfig
{
    // DEFAULT ON. The user asked for the NoeaMod feel, where the panel simply appears when you look at a planet with
    // nothing to open first; shipping it enabled makes the feature discoverable the same way, and the P key (and this
    // file) let anyone who finds it intrusive switch it off for good.
    private static final boolean DEFAULT_ENABLED = true;

    public boolean overlayEnabled = DEFAULT_ENABLED;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static PlanetInfoConfig instance;

    public static PlanetInfoConfig get()
    {
        if (instance == null)
        {
            instance = load();
        }
        return instance;
    }

    private static File file()
    {
        return FMLPaths.CONFIGDIR.get().resolve("shuruisutilities-planetinfo.json").toFile();
    }

    private static PlanetInfoConfig load()
    {
        try (FileReader in = new FileReader(file()))
        {
            PlanetInfoConfig c = GSON.fromJson(in, PlanetInfoConfig.class);
            if (c != null)
            {
                return c;
            }
        }
        catch (Exception ignored)
        {
            // no file yet, or an unreadable one: fall back to the default (enabled). Never fatal.
        }
        return new PlanetInfoConfig();
    }

    public void save()
    {
        try (FileWriter out = new FileWriter(file()))
        {
            GSON.toJson(this, out);
        }
        catch (Exception ignored)
        {
            // a failed write only loses the preference for next launch; it must never break the toggle this session.
        }
    }
}
