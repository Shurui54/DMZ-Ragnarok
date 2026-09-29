package net.shurui.dev.sdu.race;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * The ocarina half of a racial ability: an instrument the racial key produces, and the songs played on it.
 *
 * <p>Stored inside {@link RacialSkillData} and edited from the racial ability screen, so a server can retune every
 * number here without a build - which is the whole point of it being data rather than code.
 *
 * <h2>The songs</h2>
 * Three to begin with, and the racial's LEVEL is what hands over the later ones, so an ocarina is worth continuing
 * to train rather than being finished the moment it is unlocked:
 * <ul>
 *   <li>MENDING, a heal over the area for the player's own party and guild;</li>
 *   <li>VALOUR, a buff for the same friendly set, growing with the racial's level;</li>
 *   <li>DISCORD, a debuff for everyone in the area who is NOT one of them.</li>
 * </ul>
 *
 * <p>Every effect list is {@code namespace:path:amplifier} strings, the same format the racial's own potion list
 * uses, so an admin already knows how to write them.
 */
public class OcarinaData {

    /** Off by default: a race only carries an ocarina if someone says so. */
    public boolean enabled = false;

    /** How far a song reaches, in blocks. */
    public double radius = 12.0;

    /** Seconds before another song may be played. The songs share it: one instrument, one breath. */
    public double cooldownSeconds = 30.0;

    /** How well the song has to be played for it to take effect at all, as a percentage of a perfect run. */
    public double minScorePercent = 40.0;

    /** Healed per friendly target, as a percentage of their maximum health. */
    public double healPercent = 25.0;

    /** Ki and stamina restored, each as a percentage of their maximum. */
    public double healKiPercent = 15.0;
    public double healStaminaPercent = 15.0;

    /** Racial level at which this song is learned. */
    public int valourLevel = 5;

    /** Effects granted to friendly targets, each {@code namespace:path:amplifier}. */
    public final List<String> buffEffects = new ArrayList<>();

    /** How long the buff lasts, in seconds, before any level scaling. */
    public double buffSeconds = 60.0;

    /**
     * Extra amplifier per racial level above the one that unlocked the song.
     *
     * <p>A fraction rather than a whole step, so a song can be made to grow slowly: at 0.25 every four levels adds
     * one amplifier. Zero means the song never gets stronger, only more reliable.
     */
    public double buffAmplifierPerLevel = 0.25;

    /** Extra seconds of duration per racial level above the unlock. */
    public double buffSecondsPerLevel = 5.0;

    public int discordLevel = 10;

    /** Effects laid on everyone in range who is NOT in the player's party or guild. */
    public final List<String> debuffEffects = new ArrayList<>();

    public double debuffSeconds = 20.0;
    public double debuffAmplifierPerLevel = 0.2;
    public double debuffSecondsPerLevel = 2.0;

    public OcarinaData() {
        buffEffects.add("minecraft:strength:0");
        buffEffects.add("minecraft:speed:0");
        debuffEffects.add("minecraft:weakness:0");
        debuffEffects.add("minecraft:slowness:0");
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("radius", radius);
        o.addProperty("cooldownSeconds", cooldownSeconds);
        o.addProperty("minScorePercent", minScorePercent);
        o.addProperty("healPercent", healPercent);
        o.addProperty("healKiPercent", healKiPercent);
        o.addProperty("healStaminaPercent", healStaminaPercent);
        o.addProperty("valourLevel", valourLevel);
        o.addProperty("buffSeconds", buffSeconds);
        o.addProperty("buffAmplifierPerLevel", buffAmplifierPerLevel);
        o.addProperty("buffSecondsPerLevel", buffSecondsPerLevel);
        o.add("buffEffects", toArray(buffEffects));
        o.addProperty("discordLevel", discordLevel);
        o.addProperty("debuffSeconds", debuffSeconds);
        o.addProperty("debuffAmplifierPerLevel", debuffAmplifierPerLevel);
        o.addProperty("debuffSecondsPerLevel", debuffSecondsPerLevel);
        o.add("debuffEffects", toArray(debuffEffects));
        return o;
    }

    public static OcarinaData fromJson(JsonObject o) {
        OcarinaData d = new OcarinaData();
        if (o == null) {
            return d;
        }
        d.enabled = GsonHelper.getAsBoolean(o, "enabled", false);
        d.radius = GsonHelper.getAsDouble(o, "radius", 12.0);
        d.cooldownSeconds = GsonHelper.getAsDouble(o, "cooldownSeconds", 30.0);
        d.minScorePercent = GsonHelper.getAsDouble(o, "minScorePercent", 40.0);
        d.healPercent = GsonHelper.getAsDouble(o, "healPercent", 25.0);
        d.healKiPercent = GsonHelper.getAsDouble(o, "healKiPercent", 15.0);
        d.healStaminaPercent = GsonHelper.getAsDouble(o, "healStaminaPercent", 15.0);
        d.valourLevel = GsonHelper.getAsInt(o, "valourLevel", 5);
        d.buffSeconds = GsonHelper.getAsDouble(o, "buffSeconds", 60.0);
        d.buffAmplifierPerLevel = GsonHelper.getAsDouble(o, "buffAmplifierPerLevel", 0.25);
        d.buffSecondsPerLevel = GsonHelper.getAsDouble(o, "buffSecondsPerLevel", 5.0);
        d.discordLevel = GsonHelper.getAsInt(o, "discordLevel", 10);
        d.debuffSeconds = GsonHelper.getAsDouble(o, "debuffSeconds", 20.0);
        d.debuffAmplifierPerLevel = GsonHelper.getAsDouble(o, "debuffAmplifierPerLevel", 0.2);
        d.debuffSecondsPerLevel = GsonHelper.getAsDouble(o, "debuffSecondsPerLevel", 2.0);
        // Only replace the defaults when the file actually carries a list, so an older racial keeps working.
        if (o.has("buffEffects")) {
            fill(d.buffEffects, o.getAsJsonArray("buffEffects"));
        }
        if (o.has("debuffEffects")) {
            fill(d.debuffEffects, o.getAsJsonArray("debuffEffects"));
        }
        return d;
    }

    private static JsonArray toArray(List<String> from) {
        JsonArray a = new JsonArray();
        for (String s : from) {
            a.add(s);
        }
        return a;
    }

    private static void fill(List<String> into, JsonArray from) {
        into.clear();
        if (from == null) {
            return;
        }
        for (int i = 0; i < from.size(); i++) {
            into.add(from.get(i).getAsString());
        }
    }
}
