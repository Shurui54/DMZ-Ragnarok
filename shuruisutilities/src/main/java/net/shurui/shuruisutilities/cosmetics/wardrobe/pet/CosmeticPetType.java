package net.shurui.shuruisutilities.cosmetics.wardrobe.pet;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The per-pet behaviour a catalogue entry cannot express: whether the pet flies (floats and holds a hover height)
 * or walks (obeys gravity), how fast it follows, how large it draws and how big its collision box is. This is the
 * pet twin of {@code CosmeticMountType}, and it exists for the same reason: {@code CosmeticDef} does not pre-declare
 * fields for a following creature, so a tiny code table keyed by the catalogue id keeps the data next to the code
 * that reads it and costs one line to extend when a new rig lands.
 *
 * <h2>The geo, animation and texture share the id</h2>
 * The converter writes {@code geo/entity/cosmetic_pet/<id>.geo.json} and the matching animation and texture, so the
 * id is also the resource base name. Nothing here needs to carry a separate model path.
 *
 * <h2>Flying comes from the source YAML, not a guess</h2>
 * Each MythicMobs pet declares its mob {@code Type}. The four Halloween chibis are all {@code Type: wolf}, i.e. a
 * ground creature, so every seeded entry here WALKS. A flier would set {@link Motion#FLYING}; the entity then holds
 * a hover offset and never falls, and its idle animation carries the bob. Keeping the flag here means turning a
 * future pet into a flier is one line, not a code change in the entity.
 */
public final class CosmeticPetType
{
    /** How the pet moves vertically. WALKING obeys gravity; FLYING floats at a hover height and never falls. */
    public enum Motion
    {
        WALKING,
        FLYING
    }

    /** Follow speed in blocks per tick fed into the follow step. Chibi pets trot along a little faster than a walk. */
    public final double followSpeed;

    /** How the pet handles the vertical axis. */
    public final Motion motion;

    /** For a FLYING pet, the height above the owner's feet it holds while hovering, in blocks. Ignored when walking. */
    public final double hoverHeight;

    /** Uniform render scale. The chibis are authored small, so this brings them to a sensible in-world size. */
    public final float renderScale;

    /** Collision box width and height in blocks. Small: a cosmetic pet must never block a doorway or a hit. */
    public final float boxWidth;
    public final float boxHeight;

    private CosmeticPetType(double followSpeed, Motion motion, double hoverHeight, float renderScale, float boxWidth,
            float boxHeight)
    {
        this.followSpeed = followSpeed;
        this.motion = motion;
        this.hoverHeight = hoverHeight;
        this.renderScale = renderScale;
        this.boxWidth = boxWidth;
        this.boxHeight = boxHeight;
    }

    public boolean flying()
    {
        return motion == Motion.FLYING;
    }

    // The seeded set. Follow speeds and scales are first-pass values, flagged unverified: they cannot be checked
    // without launching, and getting them wrong is a visual/feel issue, never a crash. All four are Type: wolf in
    // the source MythicMobs, so all four WALK; none is a flier.
    private static final Map<String, CosmeticPetType> BY_ID = new LinkedHashMap<>();

    private static void put(String id, CosmeticPetType type)
    {
        BY_ID.put(id, type);
    }

    static
    {
        put("hw_pet_devil",
                new CosmeticPetType(0.14D, Motion.WALKING, 0.0D, 0.8F, 0.5F, 0.7F));
        put("hw_pet_frank",
                new CosmeticPetType(0.14D, Motion.WALKING, 0.0D, 0.8F, 0.5F, 0.7F));
        put("hw_pet_mummy",
                new CosmeticPetType(0.14D, Motion.WALKING, 0.0D, 0.8F, 0.5F, 0.7F));
        put("hw_pet_scarecrow",
                new CosmeticPetType(0.14D, Motion.WALKING, 0.0D, 0.8F, 0.5F, 0.7F));
    }

    /** The spec for a pet id, or null when the id is not a known pet. */
    public static CosmeticPetType of(String catalogId)
    {
        return catalogId == null ? null : BY_ID.get(catalogId);
    }

    /** A fallback used only to keep the renderer from ever baking a geo that is not on disk. */
    public static String defaultPetId()
    {
        return "hw_pet_devil";
    }

    /** Whether this id names a pet this build can actually spawn and draw. */
    public static boolean known(String catalogId)
    {
        return catalogId != null && BY_ID.containsKey(catalogId);
    }
}
