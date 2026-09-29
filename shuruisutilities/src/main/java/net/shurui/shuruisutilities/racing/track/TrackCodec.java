package net.shurui.shuruisutilities.racing.track;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads and writes a {@link TrackDef} as JSON ({@code formatVersion} 1). The output is DETERMINISTIC: fields are
 * emitted in a fixed order with a fixed pretty printer, so writing, re-reading and writing again yields the same
 * bytes. That byte-identical round trip is what {@code /race debug selftest} asserts and what lets the on-disk track
 * files be diffed and version-controlled by hand.
 *
 * <p>Reading is tolerant: a missing field takes the {@link TrackDef} default (so an older or hand-edited file still
 * loads), and an unknown field is ignored. Pure common code; the key's {@code TrackStore} is the only writer.
 */
public final class TrackCodec
{
    private TrackCodec() {}

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** Serialise a track to its canonical JSON string (trailing newline included, so files end cleanly). */
    public static String toJson(TrackDef def)
    {
        return GSON.toJson(toTree(def)) + "\n";
    }

    /** Parse a track from JSON. Never returns null for well-formed JSON; throws only on malformed JSON. */
    public static TrackDef fromJson(String json)
    {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        TrackDef def = new TrackDef();

        def.formatVersion = getInt(o, "formatVersion", TrackDef.FORMAT_VERSION);
        def.id = getString(o, "id", "");
        def.name = getString(o, "name", "");
        def.serverId = getString(o, "serverId", "");
        def.dimension = getString(o, "dimension", "minecraft:overworld");
        def.laps = getInt(o, "laps", 3);
        def.defaultWidth = getDouble(o, "defaultWidth", 8.0);

        if (o.has("surfaceBlocks"))
            for (JsonElement e : o.getAsJsonArray("surfaceBlocks"))
                def.surfaceBlocks.add(e.getAsString());

        def.offroadMult = getDouble(o, "offroadMult", 0.55);
        def.rescueDistance = getDouble(o, "rescueDistance", 6.0);
        def.fallDepth = getInt(o, "fallDepth", 8);
        def.buildSurfaceBlock = getString(o, "buildSurfaceBlock", "minecraft:smooth_stone");
        def.edgeBlock = getString(o, "edgeBlock", "minecraft:polished_andesite");
        def.edgeBlockAlt = getString(o, "edgeBlockAlt", "");
        def.wallBlock = getString(o, "wallBlock", "minecraft:smooth_stone");
        def.wallHeight = getInt(o, "wallHeight", 2);
        def.headroom = getInt(o, "headroom", 3);
        def.bounceMode = getString(o, "bounceMode", "BOTH");
        def.autoGateSpacing = getInt(o, "autoGateSpacing", 24);
        def.startNode = getInt(o, "startNode", -1);
        def.gridMode = getString(o, "gridMode", "AUTO");
        def.gridSpacing = getDouble(o, "gridSpacing", 3.0);

        if (o.has("gridSlots"))
            for (JsonElement e : o.getAsJsonArray("gridSlots"))
            {
                JsonArray a = e.getAsJsonArray();
                def.gridSlots.add(new double[] { a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble() });
            }

        if (o.has("boostPads"))
            for (JsonElement e : o.getAsJsonArray("boostPads"))
            {
                JsonObject b = e.getAsJsonObject();
                def.boostPads.add(new TrackDef.BoostPad(
                        getDouble(b, "x", 0), getDouble(b, "y", 0), getDouble(b, "z", 0),
                        (float) getDouble(b, "facing", 0)));
            }

        if (o.has("itemPoints"))
            for (JsonElement e : o.getAsJsonArray("itemPoints"))
            {
                JsonObject b = e.getAsJsonObject();
                def.itemPoints.add(new TrackDef.ItemPoint(
                        getDouble(b, "x", 0), getDouble(b, "y", 0), getDouble(b, "z", 0)));
            }

        if (o.has("nodes"))
            for (JsonElement e : o.getAsJsonArray("nodes"))
            {
                JsonObject b = e.getAsJsonObject();
                TrackNode n = new TrackNode();
                n.id = getInt(b, "id", 0);
                n.x = getDouble(b, "x", 0);
                n.y = getDouble(b, "y", 0);
                n.z = getDouble(b, "z", 0);
                n.width = getDouble(b, "width", 0);
                n.checkpoint = getBool(b, "checkpoint", false);
                n.wallL = getBool(b, "wallL", false);
                n.wallR = getBool(b, "wallR", false);
                n.main = getBool(b, "main", true);
                if (b.has("next"))
                    for (JsonElement t : b.getAsJsonArray("next"))
                        n.next.add(t.getAsInt());
                def.nodes.add(n);
            }

        // Optional per-track overrides (absent on an ordinary track, so the file stays byte-identical without them).
        if (o.has("driveOverride") && o.get("driveOverride").isJsonObject())
            def.driveOverride = readDrive(o.getAsJsonObject("driveOverride"));
        if (o.has("oddsOverride") && o.get("oddsOverride").isJsonArray())
            def.oddsOverride = readOdds(o.getAsJsonArray("oddsOverride"));

        return def;
    }

    // Build the canonical JSON tree in a FIXED field order (the order below is the wire contract for the round trip).
    private static JsonObject toTree(TrackDef def)
    {
        JsonObject o = new JsonObject();
        o.addProperty("formatVersion", def.formatVersion);
        o.addProperty("id", def.id);
        o.addProperty("name", def.name);
        o.addProperty("serverId", def.serverId);
        o.addProperty("dimension", def.dimension);
        o.addProperty("laps", def.laps);
        o.addProperty("defaultWidth", def.defaultWidth);

        JsonArray surface = new JsonArray();
        for (String s : def.surfaceBlocks)
            surface.add(s);
        o.add("surfaceBlocks", surface);

        o.addProperty("offroadMult", def.offroadMult);
        o.addProperty("rescueDistance", def.rescueDistance);
        o.addProperty("fallDepth", def.fallDepth);
        o.addProperty("buildSurfaceBlock", def.buildSurfaceBlock);
        o.addProperty("edgeBlock", def.edgeBlock);
        o.addProperty("edgeBlockAlt", def.edgeBlockAlt);
        o.addProperty("wallBlock", def.wallBlock);
        o.addProperty("wallHeight", def.wallHeight);
        o.addProperty("headroom", def.headroom);
        o.addProperty("bounceMode", def.bounceMode);
        o.addProperty("autoGateSpacing", def.autoGateSpacing);
        o.addProperty("startNode", def.startNode);
        o.addProperty("gridMode", def.gridMode);
        o.addProperty("gridSpacing", def.gridSpacing);

        JsonArray grid = new JsonArray();
        for (double[] slot : def.gridSlots)
        {
            JsonArray a = new JsonArray();
            a.add(slot[0]);
            a.add(slot[1]);
            a.add(slot[2]);
            grid.add(a);
        }
        o.add("gridSlots", grid);

        JsonArray pads = new JsonArray();
        for (TrackDef.BoostPad b : def.boostPads)
        {
            JsonObject p = new JsonObject();
            p.addProperty("x", b.x);
            p.addProperty("y", b.y);
            p.addProperty("z", b.z);
            p.addProperty("facing", b.facing);
            pads.add(p);
        }
        o.add("boostPads", pads);

        JsonArray items = new JsonArray();
        for (TrackDef.ItemPoint b : def.itemPoints)
        {
            JsonObject p = new JsonObject();
            p.addProperty("x", b.x);
            p.addProperty("y", b.y);
            p.addProperty("z", b.z);
            items.add(p);
        }
        o.add("itemPoints", items);

        JsonArray nodes = new JsonArray();
        for (TrackNode n : def.nodes)
        {
            JsonObject b = new JsonObject();
            b.addProperty("id", n.id);
            b.addProperty("x", n.x);
            b.addProperty("y", n.y);
            b.addProperty("z", n.z);
            b.addProperty("width", n.width);
            b.addProperty("checkpoint", n.checkpoint);
            b.addProperty("wallL", n.wallL);
            b.addProperty("wallR", n.wallR);
            b.addProperty("main", n.main);
            JsonArray next = new JsonArray();
            for (int t : n.next)
                next.add(t);
            b.add("next", next);
            nodes.add(b);
        }
        o.add("nodes", nodes);

        // Emit the optional overrides ONLY when present, so a track without them round-trips to the same bytes.
        if (def.driveOverride != null)
            o.add("driveOverride", writeDrive(def.driveOverride));
        if (def.oddsOverride != null)
            o.add("oddsOverride", writeOdds(def.oddsOverride));

        return o;
    }

    // --- per-track override (physics + odds) JSON, fixed field order for the byte-identical round trip ---

    private static JsonObject writeDrive(net.shurui.shuruisutilities.racing.physics.RaceDriveParams p)
    {
        JsonObject d = new JsonObject();
        d.addProperty("topSpeed", p.topSpeed);
        d.addProperty("accel", p.accel);
        d.addProperty("brake", p.brake);
        d.addProperty("reverseFraction", p.reverseFraction);
        d.addProperty("steerRateLow", p.steerRateLow);
        d.addProperty("steerRateHigh", p.steerRateHigh);
        d.addProperty("grip", p.grip);
        d.addProperty("driftMinFraction", p.driftMinFraction);
        d.addProperty("driftGripFactor", p.driftGripFactor);
        d.addProperty("hopImpulse", p.hopImpulse);
        d.addProperty("offroadMult", p.offroadMult);
        d.addProperty("boostMult", p.boostMult);
        d.addProperty("wallBumpFactor", p.wallBumpFactor);
        JsonArray mult = new JsonArray();
        for (double v : p.miniTurboMult)
            mult.add(v);
        d.add("miniTurboMult", mult);
        JsonArray ticks = new JsonArray();
        for (int v : p.miniTurboTicks)
            ticks.add(v);
        d.add("miniTurboTicks", ticks);
        d.addProperty("rocketStartWindow", p.rocketStartWindow);
        return d;
    }

    private static net.shurui.shuruisutilities.racing.physics.RaceDriveParams readDrive(JsonObject d)
    {
        net.shurui.shuruisutilities.racing.physics.RaceDriveParams p =
                new net.shurui.shuruisutilities.racing.physics.RaceDriveParams();
        p.topSpeed = getDouble(d, "topSpeed", p.topSpeed);
        p.accel = getDouble(d, "accel", p.accel);
        p.brake = getDouble(d, "brake", p.brake);
        p.reverseFraction = getDouble(d, "reverseFraction", p.reverseFraction);
        p.steerRateLow = getDouble(d, "steerRateLow", p.steerRateLow);
        p.steerRateHigh = getDouble(d, "steerRateHigh", p.steerRateHigh);
        p.grip = getDouble(d, "grip", p.grip);
        p.driftMinFraction = getDouble(d, "driftMinFraction", p.driftMinFraction);
        p.driftGripFactor = getDouble(d, "driftGripFactor", p.driftGripFactor);
        p.hopImpulse = getDouble(d, "hopImpulse", p.hopImpulse);
        p.offroadMult = getDouble(d, "offroadMult", p.offroadMult);
        p.boostMult = getDouble(d, "boostMult", p.boostMult);
        p.wallBumpFactor = getDouble(d, "wallBumpFactor", p.wallBumpFactor);
        if (d.has("miniTurboMult") && d.get("miniTurboMult").isJsonArray())
        {
            JsonArray a = d.getAsJsonArray("miniTurboMult");
            double[] m = new double[a.size()];
            for (int i = 0; i < a.size(); i++)
                m[i] = a.get(i).getAsDouble();
            p.miniTurboMult = m;
        }
        if (d.has("miniTurboTicks") && d.get("miniTurboTicks").isJsonArray())
        {
            JsonArray a = d.getAsJsonArray("miniTurboTicks");
            int[] m = new int[a.size()];
            for (int i = 0; i < a.size(); i++)
                m[i] = a.get(i).getAsInt();
            p.miniTurboTicks = m;
        }
        p.rocketStartWindow = getInt(d, "rocketStartWindow", p.rocketStartWindow);
        return p;
    }

    private static JsonArray writeOdds(int[][] odds)
    {
        int[][] o = net.shurui.shuruisutilities.racing.tuning.RaceTuningDto.copyOdds(odds);
        JsonArray rows = new JsonArray();
        for (int[] row : o)
        {
            JsonArray r = new JsonArray();
            for (int v : row)
                r.add(v);
            rows.add(r);
        }
        return rows;
    }

    private static int[][] readOdds(JsonArray rows)
    {
        int kinds = net.shurui.shuruisutilities.racing.tuning.RaceTuningDto.KINDS;
        int buckets = net.shurui.shuruisutilities.racing.tuning.RaceTuningDto.BUCKETS;
        int[][] o = new int[kinds][buckets];
        for (int k = 0; k < kinds && k < rows.size(); k++)
        {
            if (!rows.get(k).isJsonArray())
                continue;
            JsonArray r = rows.get(k).getAsJsonArray();
            for (int b = 0; b < buckets && b < r.size(); b++)
                o[k][b] = Math.max(0, r.get(b).getAsInt());
        }
        return o;
    }

    private static String getString(JsonObject o, String key, String def)
    {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static int getInt(JsonObject o, String key, int def)
    {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def;
    }

    private static double getDouble(JsonObject o, String key, double def)
    {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : def;
    }

    private static boolean getBool(JsonObject o, String key, boolean def)
    {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : def;
    }
}
