package net.shurui.shuruisutilities.client.hud;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraftforge.fml.loading.FMLPaths;

// StaffTaskHudOverlay position, persisted to config/shuruisutilities-staffhud.json as screen fractions (0..1) so
// it stays put across resolutions / GUI scale. Its own file rather than sharing the region HUD's, because both can
// be up at once and stacking them on the same spot is what a shared position would guarantee.
public final class StaffTaskHudConfig
{
    // default: LEFT column, below where the region HUD sits. The obvious spot, upper right, is exactly where
    // Xaero's minimap lives, and the panel landed on top of it.
    private static final double DEFAULT_X_PCT = 0.0573;
    private static final double DEFAULT_Y_PCT = 0.4200;

    public double xPct = DEFAULT_X_PCT; // panel center, fraction of width
    public double yPct = DEFAULT_Y_PCT; // panel top, fraction of height

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static StaffTaskHudConfig instance;

    public static StaffTaskHudConfig get()
    {
        if (instance == null)
            instance = load();
        return instance;
    }

    private static File file()
    {
        return FMLPaths.CONFIGDIR.get().resolve("shuruisutilities-staffhud.json").toFile();
    }

    private static StaffTaskHudConfig load()
    {
        try (FileReader in = new FileReader(file()))
        {
            StaffTaskHudConfig c = GSON.fromJson(in, StaffTaskHudConfig.class);
            if (c != null)
                return c.clamp();
        }
        catch (Exception ignored)
        {
        }
        return new StaffTaskHudConfig();
    }

    public void save()
    {
        clamp();
        try (FileWriter out = new FileWriter(file()))
        {
            GSON.toJson(this, out);
        }
        catch (Exception ignored)
        {
        }
    }

    public StaffTaskHudConfig clamp()
    {
        xPct = Math.max(0.0, Math.min(1.0, xPct));
        yPct = Math.max(0.0, Math.min(0.95, yPct));
        return this;
    }

    public void reset()
    {
        xPct = DEFAULT_X_PCT;
        yPct = DEFAULT_Y_PCT;
    }
}
