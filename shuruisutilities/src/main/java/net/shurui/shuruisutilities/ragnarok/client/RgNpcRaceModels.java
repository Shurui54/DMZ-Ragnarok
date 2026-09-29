package net.shurui.shuruisutilities.ragnarok.client;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.shurui.shuruisutilities.ragnarok.RgNpcModels;

/**
 * Lets any rgnpc (ninjin) model be used as a DMZ playable race model.
 *
 * <h2>Why this is an alias and not a shipped file</h2>
 * DMZ resolves a race's {@code customModel} to {@code dragonminez:geo/entity/races/&lt;model&gt;.geo.json}, and sdu's
 * race editor builds its model dropdown by scanning the resource stack for exactly that path. Our models live at
 * {@code dmz_ragnarok:geo/entity/ragnarok/&lt;geo&gt;.geo.json} and are STREAMED to each client rather than shipped in
 * the jar, so neither path existed as far as DMZ was concerned. Rather than bundling art (which is what the streamed
 * pack exists to avoid) this answers the DMZ path out of the same in-memory pack: ask for a race geo named after an
 * installed rgnpc entry and you get that entry's model, converted on the way out.
 *
 * <h2>What the conversion does, and what it deliberately does not</h2>
 * Two changes, both of them bone bookkeeping rather than geometry.
 *
 * <p>First, {@code right_hand_item} and {@code left_hand_item} bones are grafted onto the arms when the model has
 * none. DMZ's {@code DMZPlayerItemInHandLayer} finds a held item purely by those two bone names (confirmed against
 * the 2.1.3 class), so without them a player holding a sword shows nothing in hand.
 *
 * <p>Second, {@link RgNpcRig#torso(JsonArray)} puts the torso on the name and the parent DMZ keyframes. That one is
 * applied on the pack's own rgnpc path too, so the entities get it as well; it is repeated here because this class
 * reads the RAW pack bytes rather than going back through the pack, and it is idempotent.
 *
 * <p>Armour anchors ({@code armorHead}, {@code armorBody}, the limb and boot bones) are NOT added, by choice.
 * {@code DMZPlayerArmorLayer} also finds armour purely by bone name, so their absence is exactly what keeps armour
 * off these models, the same way it is kept off the shadow dragon ones. Body and hat layers are not added either:
 * they sample a 64x64 player skin an rgnpc texture is not.
 *
 * <h2>Textures</h2>
 * A race with {@code isLayered: false} paints one texture from
 * {@code textures/entity/races/&lt;race&gt;/&lt;customModel&gt;.png} (optionally gendered), so the same trick answers
 * that path with the entry's own artwork. The race should set its three body colours to white, or the tint multiply
 * will recolour art that is already finished.
 *
 * <h2>Three things that bite</h2>
 * <ul>
 *   <li>DMZ memoises model resolution and texture validation in STATIC caches, so a client that already looked a
 *       race up needs a full restart, not a resource reload. {@code RgNpcAssetCache} clears them on a pack swap for
 *       exactly that reason.</li>
 *   <li>The pack only holds models the server has streamed, which happens on join. Nothing here resolves in a
 *       single player world with no rgnpc folder installed. Returning null is the RIGHT answer then, not a crash:
 *       DMZ's own {@code resolveCustomModel} asks whether the file reads and falls back to its base race geo when
 *       it does not.</li>
 *   <li>READING and LISTING deliberately do not answer the same set, and that asymmetry is load bearing. DMZ
 *       appends the gender to a gendered race's model name, so it asks for {@code <entry>_male.geo.json}, which
 *       {@link #entryId(String)} strips and answers out of the bare entry. {@link #forEachGeoPath} enumerates only
 *       the bare name, because listing every entry times every variant would have GeckoLib parse and bake the whole
 *       streamed set four times over on every resource reload. GeckoLib only bakes what was LISTED and throws out
 *       of {@code getBakedModel} for anything else, so a variant name is a file that reads and was never baked:
 *       {@code DmzRaceGeoGuard} (sdu) is what bridges the gap, mapping an unbaked variant back onto the bare entry
 *       at the point DMZ hands the location over. Do not "fix" the asymmetry by silently widening either side.</li>
 * </ul>
 */
public final class RgNpcRaceModels
{
    private RgNpcRaceModels() {}

    /** DMZ's namespace: the one this class answers for, alongside the pack's own. */
    public static final String DMZ_NAMESPACE = "dragonminez";

    private static final String RACE_GEO_PREFIX = "geo/entity/races/";
    private static final String RACE_TEXTURE_PREFIX = "textures/entity/races/";
    private static final String GEO_SUFFIX = ".geo.json";

    private static final String RGNPC_GEO_PREFIX = "geo/entity/ragnarok/";
    private static final String RGNPC_TEXTURE_PREFIX = "textures/entity/ragnarok/";

    /**
     * Race geos DMZ ships itself. An rgnpc entry that happens to share one of these names must never be served
     * here: this pack is loaded above the mod jar, so answering would replace a stock race model with ours.
     */
    private static final Set<String> DMZ_OWNED = Set.of(
            "accesories", "bioandroid", "bioandroid_perfect", "bioandroid_semi", "bioandroid_ultra",
            "bioandroid_xeno", "candy", "frostdemon", "frostdemon_fifth", "frostdemon_fp", "frostdemon_metalcore",
            "frostdemon_second", "frostdemon_third", "h4arms", "h4armsfem", "h4armsslim", "hbuffed", "hbuffed_fem",
            "hbuffed_slim", "human", "human_slim", "janemba_fat", "janemba_super", "kiaura", "kiaura2", "kirayos",
            "kiweapons", "majin", "majin_slim", "oozaru", "saibaman", "weighted_items",
            // Names DMZ's own resolveCustomModel switch understands even where the geo is shared.
            "saiyan", "ssj4gt", "ssj4d", "buffed", "4arms", "namekian", "namekian_buffed",
            "majin_super", "majin_ultra", "majin_evil", "majin_kid", "null");

    /** Suffixes DMZ appends to a model name. Stripped before an entry id is looked up. */
    private static final String[] VARIANT_SUFFIXES = {"_male", "_female", "_slim"};

    /** Converted geos, keyed by entry id. Built once per id and dropped whenever the pack is replaced. */
    private static final Map<String, byte[]> GEO_CACHE = new ConcurrentHashMap<>();

    private static final Gson GSON = new Gson();

    /** Drop the converted geos. Called when a new pack arrives, so a re-sent model cannot serve stale bytes. */
    public static void clearCache()
    {
        GEO_CACHE.clear();
    }

    /**
     * The bytes for a {@code dragonminez} resource this class can answer, or null for anything else.
     *
     * @param path namespace-relative path, as the resource manager asks for it
     * @param file reads one file out of the streamed pack by its namespace-relative path
     */
    public static byte[] resolve(String path, java.util.function.Function<String, byte[]> file)
    {
        if (path == null || file == null)
            return null;
        if (path.startsWith(RACE_GEO_PREFIX) && path.endsWith(GEO_SUFFIX))
            return geo(path.substring(RACE_GEO_PREFIX.length(), path.length() - GEO_SUFFIX.length()), file);
        if (path.startsWith(RACE_TEXTURE_PREFIX) && path.endsWith(".png"))
            return texture(path, file);
        return null;
    }

    /** Every race-geo path this class can answer right now, for the pack's resource listing. */
    public static void forEachGeoPath(java.util.function.Predicate<String> installed, Consumer<String> out)
    {
        for (String id : RgNpcModels.ids())
        {
            if (DMZ_OWNED.contains(id))
                continue;
            if (!installed.test(RGNPC_GEO_PREFIX + RgNpcModels.geoId(id) + GEO_SUFFIX))
                continue;
            out.accept(RACE_GEO_PREFIX + id + GEO_SUFFIX);
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Geometry.
    // -------------------------------------------------------------------------------------------------------------

    private static byte[] geo(String model, java.util.function.Function<String, byte[]> file)
    {
        String id = entryId(model);
        if (id == null)
            return null;

        byte[] cached = GEO_CACHE.get(id);
        if (cached != null)
            return cached;

        byte[] source = file.apply(RGNPC_GEO_PREFIX + RgNpcModels.geoId(id) + GEO_SUFFIX);
        if (source == null)
            return null;

        byte[] converted = toRaceRig(source);
        if (converted == null)
            return null;
        GEO_CACHE.put(id, converted);
        return converted;
    }

    /**
     * Put an rgnpc skeleton on DMZ's race rig: name the torso the way DMZ keyframes it, and graft the two
     * hand-item bones on when the model has none.
     *
     * <p>The hand pivot is taken from the arm's own geometry rather than a constant: rgnpc models are not one
     * scale, and a fixed offset that sits in the palm of a human-sized model hangs in mid air on a tall one. The
     * lowest cube on the arm is the hand end, so the bone is pivoted there, on the arm's own x/z.
     *
     * <p>Any malformed geo returns the source untouched. A model that does not parse is DMZ's problem to report,
     * not ours to mangle; and a geo with no recognisable arms or torso simply keeps behaving as it does today.
     */
    private static byte[] toRaceRig(byte[] source)
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
                if (bones == null)
                    continue;
                changed |= RgNpcRig.torso(bones);
                changed |= addHandBone(bones, "right_hand_item", "right_arm", "rightArm", "right_arm_layer");
                changed |= addHandBone(bones, "left_hand_item", "left_arm", "leftArm", "left_arm_layer");
            }
            if (!changed)
                return source;
            return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        }
        catch (Throwable t)
        {
            return source;
        }
    }

    /** @return true when a bone was added. */
    private static boolean addHandBone(JsonArray bones, String name, String... armNames)
    {
        if (RgNpcRig.findBone(bones, name) != null)
            return false; // the model already carries one: never move somebody's hand
        JsonObject arm = null;
        for (String armName : armNames)
        {
            arm = RgNpcRig.findBone(bones, armName);
            if (arm != null)
                break;
        }
        if (arm == null)
            return false;

        JsonArray pivot = arm.getAsJsonArray("pivot");
        if (pivot == null || pivot.size() < 3)
            return false;

        JsonObject bone = new JsonObject();
        bone.addProperty("name", name);
        bone.addProperty("parent", arm.get("name").getAsString());
        JsonArray at = new JsonArray();
        at.add(pivot.get(0).getAsFloat());
        at.add(handHeight(arm, pivot.get(1).getAsFloat()));
        at.add(pivot.get(2).getAsFloat());
        bone.add("pivot", at);
        bones.add(bone);
        return true;
    }

    /** The bottom of the arm's lowest cube, or the arm pivot when it has no cubes to measure. */
    private static float handHeight(JsonObject arm, float fallback)
    {
        try
        {
            JsonArray cubes = arm.getAsJsonArray("cubes");
            if (cubes == null || cubes.isEmpty())
                return fallback;
            float lowest = Float.MAX_VALUE;
            for (JsonElement e : cubes)
            {
                JsonArray origin = e.getAsJsonObject().getAsJsonArray("origin");
                if (origin != null && origin.size() >= 3)
                    lowest = Math.min(lowest, origin.get(1).getAsFloat());
            }
            return lowest == Float.MAX_VALUE ? fallback : lowest;
        }
        catch (Throwable t)
        {
            return fallback;
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Textures.
    // -------------------------------------------------------------------------------------------------------------

    /**
     * {@code textures/entity/races/<race>/<model>.png} and its gendered forms, answered with the entry's own art.
     *
     * <p>The layered names ({@code <model>_0_layer1.png} and the {@code faces/} set) are deliberately NOT answered:
     * those belong to a race built out of three tinted greyscale layers, which is not what an rgnpc texture is. Such
     * a race should set {@code isLayered: false}, which is the path this serves.
     */
    private static byte[] texture(String path, java.util.function.Function<String, byte[]> file)
    {
        String rest = path.substring(RACE_TEXTURE_PREFIX.length(), path.length() - ".png".length());
        int slash = rest.lastIndexOf('/');
        if (slash < 0)
            return null; // no <race>/ segment: not a race body texture
        String model = rest.substring(slash + 1);
        if (model.indexOf('/') >= 0)
            return null;

        String id = entryId(model);
        if (id == null)
            return null;
        return file.apply(RGNPC_TEXTURE_PREFIX + RgNpcModels.defaultTexture(id) + ".png");
    }

    // -------------------------------------------------------------------------------------------------------------
    // Shared.
    // -------------------------------------------------------------------------------------------------------------

    /** The rgnpc entry a DMZ model name refers to, or null when it is not one of ours (or is DMZ's own). */
    private static String entryId(String model)
    {
        if (model == null || model.isBlank())
            return null;
        String name = model.toLowerCase(Locale.ROOT);
        if (DMZ_OWNED.contains(name))
            return null;
        for (String suffix : VARIANT_SUFFIXES)
        {
            if (name.endsWith(suffix))
            {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        if (DMZ_OWNED.contains(name) || !RgNpcModels.isValidId(name))
            return null;
        return name;
    }
}
