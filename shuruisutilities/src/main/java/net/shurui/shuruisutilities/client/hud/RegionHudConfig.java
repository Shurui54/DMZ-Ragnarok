package net.shurui.shuruisutilities.client.hud;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraftforge.fml.loading.FMLPaths;

// RegionHudOverlay position, persisted to config/shuruisutilities-regionhud.json as screen fractions (0..1) so
// it stays put across resolutions / GUI scale. moved via HudMoveScreen, which drags this and the staff
// task panel together so the two can be positioned against each other.
public final class RegionHudConfig
{
    // default: upper-left, clear of the hotbar, boss bars and DMZ's own HUD
    private static final double DEFAULT_X_PCT = 0.0573;
    private static final double DEFAULT_Y_PCT = 0.1789;

    public double xPct = DEFAULT_X_PCT; // panel center, fraction of width
    public double yPct = DEFAULT_Y_PCT; // panel top, fraction of height

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static RegionHudConfig instance;

    public static RegionHudConfig get()
    {
        if (instance == null)
            instance = load();
        return instance;
    }

    private static File file()
    {
        return FMLPaths.CONFIGDIR.get().resolve("shuruisutilities-regionhud.json").toFile();
    }

    private static RegionHudConfig load()
    {
        try (FileReader in = new FileReader(file()))
        {
            RegionHudConfig c = GSON.fromJson(in, RegionHudConfig.class);
            if (c != null)
                return c.clamp();
        }
        catch (Exception ignored)
        {
        }
        return new RegionHudConfig();
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

    public RegionHudConfig clamp()
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
