package net.shurui.shuruisutilities.compat.sdu;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraftforge.fml.ModList;

/**
 * Reads the OCARINA block off an sdu racial ability.
 *
 * <p>sdu owns the racial skills and the screen that edits them, which is where every number below is set. SU owns
 * the ocarina's runtime, because that is where the rhythm chart, the guilds and the parties already live. So this is
 * the seam, and it points the way the workspace's dependencies already do: SU may know about sdu, never the reverse.
 *
 * <p>Reflection + {@code isLoaded}, matching the other sdu bridges: nothing here names an sdu type, so the class is
 * safe to load with sdu absent. One reflective hop fetches the racial's own JSON and the fields are read out of
 * that, rather than reflecting a dozen field lookups that would each have to be kept in step by hand.
 */
public final class SduOcarinaCompat
{
    private static final String CONFIG = "net.shurui.dev.sdu.race.RacialSkillConfig";

    private static Method get;
    private static Method toJson;
    private static boolean resolved;

    private SduOcarinaCompat() {}

    /** Every number an ocarina racial carries. Absent (null) when the racial has no ocarina at all. */
    public record Settings(double radius, double cooldownSeconds, double minScorePercent,
                           double healPercent, double healKiPercent, double healStaminaPercent,
                           int valourLevel, List<String> buffEffects, double buffSeconds,
                           double buffAmplifierPerLevel, double buffSecondsPerLevel,
                           int discordLevel, List<String> debuffEffects, double debuffSeconds,
                           double debuffAmplifierPerLevel, double debuffSecondsPerLevel) {}

    /**
     * The ocarina settings for a racial id, or null when sdu is absent, the racial is unknown, or its ocarina is
     * switched off.
     */
    public static Settings settings(String racialId)
    {
        if (racialId == null || racialId.isEmpty() || !ModList.get().isLoaded("dmz_ragnarok"))
            return null;
        try
        {
            resolve();
            if (get == null || toJson == null)
                return null;
            Object data = get.invoke(null, racialId);
            if (data == null)
                return null;
            Object json = toJson.invoke(data);
            if (!(json instanceof JsonObject root) || !root.has("ocarina"))
                return null;
            JsonObject o = root.getAsJsonObject("ocarina");
            if (!o.has("enabled") || !o.get("enabled").getAsBoolean())
                return null;
            return new Settings(
                    d(o, "radius", 12.0), d(o, "cooldownSeconds", 30.0), d(o, "minScorePercent", 40.0),
                    d(o, "healPercent", 25.0), d(o, "healKiPercent", 15.0), d(o, "healStaminaPercent", 15.0),
                    i(o, "valourLevel", 5), strings(o, "buffEffects"), d(o, "buffSeconds", 60.0),
                    d(o, "buffAmplifierPerLevel", 0.25), d(o, "buffSecondsPerLevel", 5.0),
                    i(o, "discordLevel", 10), strings(o, "debuffEffects"), d(o, "debuffSeconds", 20.0),
                    d(o, "debuffAmplifierPerLevel", 0.2), d(o, "debuffSecondsPerLevel", 2.0));
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private static void resolve()
    {
        if (resolved)
            return;
        resolved = true;
        try
        {
            Class<?> config = Class.forName(CONFIG);
            get = config.getMethod("get", String.class);
            Class<?> data = Class.forName("net.shurui.dev.sdu.race.RacialSkillData");
            toJson = data.getMethod("toJson");
        }
        catch (Throwable ignored)
        {
            get = null;
            toJson = null;
        }
    }

    private static double d(JsonObject o, String key, double fallback)
    {
        return o.has(key) ? o.get(key).getAsDouble() : fallback;
    }

    private static int i(JsonObject o, String key, int fallback)
    {
        return o.has(key) ? o.get(key).getAsInt() : fallback;
    }

    private static List<String> strings(JsonObject o, String key)
    {
        List<String> out = new ArrayList<>();
        if (!o.has(key) || !o.get(key).isJsonArray())
            return out;
        JsonArray a = o.getAsJsonArray(key);
        for (int i = 0; i < a.size(); i++)
            out.add(a.get(i).getAsString());
        return out;
    }
}
