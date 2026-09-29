package net.shurui.shuruisutilities.cosmetics.wardrobe.mount;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The per-mount behaviour a catalogue entry cannot express yet: whether the mount flies, how fast it moves, how
 * high the rider sits and which "fast" animation the converted rig actually carries.
 *
 * <h2>Why a small code table rather than a def field</h2>
 * {@link net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticDef} deliberately does not pre-declare fields for
 * unbuilt milestones, and a flying flag plus a seat height is exactly that shape. Keying a tiny table by the
 * catalogue id keeps the data next to the code that reads it and costs nothing to extend when a new rig lands: add
 * one line here and one seed entry. If the def ever grows a generic "mount flags" field these values move onto it
 * with no behaviour change, because everything downstream asks this table, never the raw id.
 *
 * <p>The keys are the seeded mount catalogue ids. An id with no entry here is simply not summonable: the manager
 * refuses it rather than guessing, so a mislabelled entry fails loudly at summon rather than silently flying a
 * horse.
 *
 * <h2>The geo, animation and texture share the id</h2>
 * The converter writes {@code geo/entity/cosmetic_mount/<id>.geo.json} and the matching animation and texture, so
 * the id is also the resource base name. Nothing here needs to carry a separate model path.
 */
public final class CosmeticMountType
{
    /** How the mount moves vertically. WALKING obeys gravity and jumps; FLYING holds altitude and climbs on jump. */
    public enum Motion
    {
        WALKING,
        FLYING
    }

    /** Ground speed in blocks per tick fed into the drive step, before the sprint multiplier. */
    public final double speed;

    /** How the mount handles the vertical axis. */
    public final Motion motion;

    /** Rider seat height above the entity origin, in blocks. UNVERIFIED per rig until launch-tested. */
    public final double seatHeight;

    /** The looping animation played while moving fast. The premium rigs name it "sprint", the free ones "run". */
    public final String fastAnimation;

    /** Uniform render scale. 1.0 draws the rig at its native converted size. */
    public final float renderScale;

    /** Collision box width and height in blocks. Generous so the rider can always be seated and dismounted. */
    public final float boxWidth;
    public final float boxHeight;

    private CosmeticMountType(double speed, Motion motion, double seatHeight, String fastAnimation, float renderScale,
            float boxWidth, float boxHeight)
    {
        this.speed = speed;
        this.motion = motion;
        this.seatHeight = seatHeight;
        this.fastAnimation = fastAnimation;
        this.renderScale = renderScale;
        this.boxWidth = boxWidth;
        this.boxHeight = boxHeight;
    }

    public boolean flying()
    {
        return motion == Motion.FLYING;
    }

    // The seeded set. Speeds, seat heights and scales are first-pass values, flagged unverified: they cannot be
    // checked without launching, and getting them wrong is a visual/feel issue, never a crash. Flying vs walking
    // follows the brief: broomstick, ghost ship and watcher fly; the rest walk.
    private static final Map<String, CosmeticMountType> BY_ID = new LinkedHashMap<>();

    private static void put(String id, CosmeticMountType type)
    {
        BY_ID.put(id, type);
    }

    static
    {
        put("hw_mount_deadhorse",
                new CosmeticMountType(0.34D, Motion.WALKING, 1.35D, "sprint", 1.0F, 1.6F, 1.8F));
        put("hw_mount_broomstick",
                new CosmeticMountType(0.42D, Motion.FLYING, 1.05D, "sprint", 1.0F, 1.4F, 1.2F));
        put("hw_mount_ghostship",
                new CosmeticMountType(0.45D, Motion.FLYING, 2.1D, "sprint", 1.0F, 2.6F, 2.6F));
        put("hw_mount_skeleptor",
                new CosmeticMountType(0.36D, Motion.WALKING, 1.6D, "sprint", 1.0F, 1.8F, 2.0F));
        put("hw_mount_watcher",
                new CosmeticMountType(0.40D, Motion.FLYING, 1.6D, "sprint", 1.0F, 2.2F, 2.2F));
        put("hw_mount_pumpkin_hound",
                new CosmeticMountType(0.35D, Motion.WALKING, 1.3D, "run", 1.0F, 1.6F, 1.6F));
        // The spider rig has no "run" animation (its extra clip is a backflip), so its fast animation reuses
        // "walk". Referencing an animation a rig does not contain would throw in GeckoLib on the render thread.
        put("hw_mount_pumpkin_spider",
                new CosmeticMountType(0.32D, Motion.WALKING, 1.0D, "walk", 1.0F, 1.6F, 1.2F));
    }

    /** The spec for a mount id, or null when the id is not a known summonable mount. */
    public static CosmeticMountType of(String catalogId)
    {
        return catalogId == null ? null : BY_ID.get(catalogId);
    }

    /** A fallback used only to keep the renderer from ever baking a geo that is not on disk. */
    public static String defaultMountId()
    {
        return "hw_mount_deadhorse";
    }

    /** Whether this id names a mount this build can actually summon and draw. */
    public static boolean known(String catalogId)
    {
        return catalogId != null && BY_ID.containsKey(catalogId);
    }

    /**
     * The editable movement defaults for a seeded mount id, from this table's flying flag and speed, or null when the
     * id is not a known mount. Used to seed a fresh catalogue's {@code CosmeticDef.mount} sub-record so the editor
     * shows the same values the code table has always driven; an already-seeded catalogue reads its stored value.
     */
    public static net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticMount defaultMovement(String catalogId)
    {
        CosmeticMountType t = of(catalogId);
        return t == null ? null
                : new net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticMount(t.flying(), t.speed);
    }
}
