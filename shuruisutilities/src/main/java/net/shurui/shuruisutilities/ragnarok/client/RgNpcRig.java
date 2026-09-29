package net.shurui.shuruisutilities.ragnarok.client;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Puts an rgnpc (ninjin) skeleton onto the torso naming every DMZ animation expects, on the way out of the streamed
 * model pack, so that EVERY consumer of a model gets the same rig: the rgnpc entities and fighters
 * ({@link RgNpcModel}, {@link RgNpcFighterModel}, which play DMZ's saga library), and the race-model alias
 * ({@link RgNpcRaceModels}, which plays DMZ's race library).
 *
 * <h2>What is wrong with the models as authored</h2>
 * DMZ's own rig is {@code root -> waist -> {head, body, right_arm, left_arm}} with the legs on {@code root}
 * ({@code dragonminez:geo/entity/races/human.geo.json}). 86 of the 376 shipped rgnpc geos call the torso
 * {@code body1} instead, and 83 of those also hang it straight off {@code root}. Both halves cost something:
 *
 * <ul>
 *   <li>The clips keyframe a bone literally called {@code body}: {@code movement}, {@code ki}, {@code skp} and
 *       {@code transf} on the race side, {@code saga_base} on the entity side. GeckoLib silently does nothing for
 *       a name it cannot find, so a torso spelled otherwise never animates at all.</li>
 *   <li>DMZ drives {@code waist} and parents the head and both arms to it, and {@code base.idle} turns the waist by
 *       the FULL head yaw. A torso parented to {@code root} therefore sits out every one of those turns while the
 *       head and the arms take them in full, so the chest and shoulders stay put and the arms swing around them.
 *       That is what gets reported as the torso facing the wrong way.</li>
 * </ul>
 *
 * <h2>Why here and not in the art</h2>
 * The models are streamed from the server's own folder, so correcting the files would mean a new pack version and a
 * full redownload for every player, for a change that is pure bone bookkeeping. Doing it on the way out costs one
 * parse per file per resource reload, cached, and a corrected file on disk simply makes this a no-op.
 */
public final class RgNpcRig
{
    private RgNpcRig() {}

    private static final Gson GSON = new Gson();

    /** Rewritten geos, keyed by namespace-relative path. Dropped whenever the pack is replaced. */
    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();

    private static final String GEO_SUFFIX = ".geo.json";

    /** Drop the rewrites. Called when a new pack arrives, so a re-sent model cannot serve stale bytes. */
    public static void clearCache()
    {
        CACHE.clear();
    }

    /**
     * The geo at this path with its torso on DMZ's rig, or the source bytes unchanged for anything that is not a geo,
     * does not parse, or is already spelled DMZ's way.
     */
    public static byte[] normalised(String path, byte[] source)
    {
        if (source == null || path == null || !path.endsWith(GEO_SUFFIX))
            return source;
        byte[] cached = CACHE.get(path);
        if (cached != null)
            return cached;
        byte[] out = rewrite(source);
        CACHE.put(path, out);
        return out;
    }

    private static byte[] rewrite(byte[] source)
    {
        try
        {
            JsonElement root = JsonParser.parseString(new String(source, StandardCharsets.UTF_8));
            if (!root.isJsonObject())
                return source;
            JsonArray geometry = root.getAsJsonObject().getAsJsonArray("minecraft:geometry");
            if (geometry == null || geometry.isEmpty())
                return source;

            boolean changed = false;
            for (JsonElement entry : geometry)
            {
                if (!entry.isJsonObject())
                    continue;
                JsonArray bones = entry.getAsJsonObject().getAsJsonArray("bones");
                if (bones != null)
                    changed |= torso(bones);
            }
            if (!changed)
                return source;
            return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        }
        catch (Throwable t)
        {
            // A model that does not parse is the loader's problem to report, not ours to mangle.
            return source;
        }
    }

    /**
     * Give the torso the name and the parent DMZ expects, when it carries the one alternative spelling these packs
     * use. See the class note for what each half is worth.
     *
     * <p>Deliberately narrow, and IDEMPOTENT. It fires only when the model has no {@code body} already, so a rig that
     * is already spelled DMZ's way is never touched and running it twice changes nothing. It reparents only a torso
     * currently on {@code root}, in a model that actually has a {@code waist} to move it to. In the rest pose the
     * reparent changes nothing on screen: the waist carries no baked rotation in any of these models, and pivots are
     * absolute in a 1.12.0 geometry, so the geometry lands exactly where it did.
     *
     * @return true when the torso was renamed.
     */
    static boolean torso(JsonArray bones)
    {
        if (findBone(bones, "body") != null)
            return false; // already spelled DMZ's way: never rename somebody's torso out from under them
        JsonObject torso = findBone(bones, "body1");
        if (torso == null)
            return false;

        torso.addProperty("name", "body");
        JsonElement parent = torso.get("parent");
        boolean onRoot = parent != null && !parent.isJsonNull() && "root".equals(parent.getAsString());
        if (onRoot && findBone(bones, "waist") != null)
            torso.addProperty("parent", "waist");

        // Anything that hung off the old name has to follow it, or the hierarchy detaches and the geometry vanishes.
        for (JsonElement e : bones)
        {
            if (!e.isJsonObject())
                continue;
            JsonObject bone = e.getAsJsonObject();
            JsonElement boneParent = bone.get("parent");
            if (boneParent != null && !boneParent.isJsonNull() && "body1".equals(boneParent.getAsString()))
                bone.addProperty("parent", "body");
        }
        return true;
    }

    /** The bone with this name, or null. Shared with {@link RgNpcRaceModels}, which grafts onto the same skeleton. */
    static JsonObject findBone(JsonArray bones, String name)
    {
        for (JsonElement e : bones)
        {
            if (!e.isJsonObject())
                continue;
            JsonObject bone = e.getAsJsonObject();
            JsonElement boneName = bone.get("name");
            if (boneName != null && !boneName.isJsonNull() && name.equals(boneName.getAsString()))
                return bone;
        }
        return null;
    }
}
