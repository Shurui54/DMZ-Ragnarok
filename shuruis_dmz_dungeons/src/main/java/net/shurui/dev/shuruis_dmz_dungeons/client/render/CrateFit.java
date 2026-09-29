package net.shurui.dev.shuruis_dmz_dungeons.client.render;

import java.util.Map;
import java.util.WeakHashMap;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.core.state.BoneSnapshot;

/**
 * How far a crate model has to shrink to sit inside its own block.
 *
 * <p>These models are furniture sized. Measured, the dungeon crates run from 1.42 blocks across (commons) to 1.69
 * (rares and mythics), so at built size they reach into whatever stands beside them. This works out one uniform
 * factor per model, around 0.58 to 0.69, so a crate is never larger than its block. It scales the DRAWING only;
 * no model file is touched, and proportions hold because the factor is uniform.
 *
 * <p>Two things make the measurement fiddly, and both were got wrong first time round:
 *
 * <ul>
 *   <li>UNITS. Baked quad vertices are already in BLOCK units (the loader divides by 16), while bone and cube
 *       pivots are still in MODEL units and only divided at render time. Measuring pivots against vertices without
 *       converting compares sixteenths to wholes and yields nonsense.</li>
 *   <li>REST POSE. A {@link GeoBone}'s rotation, offset and scale are live animation state on a model instance
 *       shared by every crate on screen, so reading them mid-animation measures one arbitrary frame. The initial
 *       snapshot holds the values the model FILE asked for, which is the pose to measure.</li>
 * </ul>
 *
 * <p>Cube rotations matter too: a crate's lock is modelled out to one side and rotated back against the front
 * face, so measuring raw corners would call the model twice as deep as it looks. The bone and cube transforms are
 * composed here exactly as GeoRenderer composes them.
 *
 * <p>Cached per model, because it depends only on the geometry and never changes once loaded.
 */
public final class CrateFit {

    private CrateFit() {
    }

    /** Model units per block. Pivots are authored in these; baked vertices are not. */
    private static final float PER_BLOCK = 16.0f;

    /** Leave a sliver of room so a crate that fits exactly does not z-fight its neighbour. */
    private static final float MARGIN = 0.98f;

    // weak, so a model dropped on a resource reload does not pin its measurement for ever.
    private static final Map<BakedGeoModel, Float> CACHE = new WeakHashMap<>();

    /** The uniform factor to draw this model at, never above 1: a model already inside its block is left alone. */
    public static float scaleFor(BakedGeoModel model) {
        synchronized (CACHE) {
            Float cached = CACHE.get(model);
            if (cached != null) {
                return cached;
            }
        }
        Bounds bounds = new Bounds();
        for (GeoBone bone : model.topLevelBones()) {
            measure(bone, new Matrix4f(), bounds);
        }
        float span = bounds.empty() ? 0.0f : bounds.largestSpan();
        float scale = span <= 0.0f ? 1.0f : Math.min(1.0f, MARGIN / span);
        synchronized (CACHE) {
            CACHE.put(model, scale);
        }
        return scale;
    }

    /** The model's largest side in blocks: 1.0 is exactly a block. Kept for diagnosing a crate that looks wrong. */
    static float spanFor(BakedGeoModel model) {
        Bounds bounds = new Bounds();
        for (GeoBone bone : model.topLevelBones()) {
            measure(bone, new Matrix4f(), bounds);
        }
        return bounds.empty() ? 0.0f : bounds.largestSpan();
    }

    /**
     * ModelEngine's reserved bone names. These carry no artwork: they mark where the plugin puts a light,
     * particle, rider or collision box, and packs park them well outside the crate, a block and a half below it in
     * places. Never drawn, so measuring them would describe a box nothing occupies and shrink every crate to a
     * third size.
     */
    private static boolean isMetadataBone(GeoBone bone) {
        String name = bone.getName();
        if (name == null) {
            return false;
        }
        name = name.toLowerCase(java.util.Locale.ROOT);
        return name.equals("hitbox") || name.equals("light") || name.equals("particle")
                || name.equals("mount") || name.equals("seat");
    }

    private static void measure(GeoBone bone, Matrix4f parent, Bounds bounds) {
        if (bone.isHidden() || isMetadataBone(bone)) {
            return;
        }
        // the pose the MODEL FILE asks for, not whatever frame an animation happens to be on: these bones are
        // shared by every crate being drawn, so their live rotation and scale belong to some other crate's frame.
        BoneSnapshot rest = bone.getInitialSnapshot();
        float rotX = rest != null ? rest.getRotX() : bone.getRotX();
        float rotY = rest != null ? rest.getRotY() : bone.getRotY();
        float rotZ = rest != null ? rest.getRotZ() : bone.getRotZ();
        float posX = rest != null ? rest.getOffsetX() : bone.getPosX();
        float posY = rest != null ? rest.getOffsetY() : bone.getPosY();
        float posZ = rest != null ? rest.getOffsetZ() : bone.getPosZ();
        float scaleX = rest != null ? rest.getScaleX() : bone.getScaleX();
        float scaleY = rest != null ? rest.getScaleY() : bone.getScaleY();
        float scaleZ = rest != null ? rest.getScaleZ() : bone.getScaleZ();

        // a bone scaled away contributes nothing visible, and crates park effect props outside themselves and
        // hide them this way, so including one would shrink the whole crate to a fraction of its size.
        if (Math.abs(scaleX) < 1.0e-4f || Math.abs(scaleY) < 1.0e-4f || Math.abs(scaleZ) < 1.0e-4f) {
            return;
        }

        // the same order GeoRenderer uses (RenderUtils.prepMatrixForBone). X offset is negated there, as it is
        // throughout GeckoLib's mirrored coordinate space.
        Matrix4f local = new Matrix4f(parent);
        local.translate(-posX / PER_BLOCK, posY / PER_BLOCK, posZ / PER_BLOCK);
        local.translate(bone.getPivotX() / PER_BLOCK, bone.getPivotY() / PER_BLOCK, bone.getPivotZ() / PER_BLOCK);
        local.rotateZ(rotZ);
        local.rotateY(rotY);
        local.rotateX(rotX);
        local.scale(scaleX, scaleY, scaleZ);
        local.translate(-bone.getPivotX() / PER_BLOCK, -bone.getPivotY() / PER_BLOCK, -bone.getPivotZ() / PER_BLOCK);

        for (GeoCube cube : bone.getCubes()) {
            Matrix4f cubeSpace = new Matrix4f(local);
            cubeSpace.translate((float) cube.pivot().x() / PER_BLOCK,
                    (float) cube.pivot().y() / PER_BLOCK,
                    (float) cube.pivot().z() / PER_BLOCK);
            cubeSpace.rotateZ((float) cube.rotation().z());
            cubeSpace.rotateY((float) cube.rotation().y());
            cubeSpace.rotateX((float) cube.rotation().x());
            cubeSpace.translate(-(float) cube.pivot().x() / PER_BLOCK,
                    -(float) cube.pivot().y() / PER_BLOCK,
                    -(float) cube.pivot().z() / PER_BLOCK);

            for (GeoQuad quad : cube.quads()) {
                if (quad == null) {
                    continue;
                }
                for (GeoVertex vertex : quad.vertices()) {
                    // already block units, straight out of the loader
                    Vector4f p = cubeSpace.transform(new Vector4f(
                            vertex.position().x(), vertex.position().y(), vertex.position().z(), 1.0f));
                    bounds.add(p.x(), p.y(), p.z());
                }
            }
        }
        if (bone.isHidingChildren()) {
            return;
        }
        for (GeoBone child : bone.getChildBones()) {
            measure(child, local, bounds);
        }
    }

    private static final class Bounds {
        private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        void add(float x, float y, float z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        boolean empty() {
            return minX > maxX;
        }

        /**
         * The longest side of the model's box, in blocks. Width and depth are measured from the block's centre
         * line outward rather than as a plain span, because a crate is drawn centred on its block: a model reaching
         * a third of a block to one side needs the same room on the other, or turning it would push it into the
         * next block.
         */
        float largestSpan() {
            float horizontal = 2.0f * Math.max(
                    Math.max(Math.abs(minX), Math.abs(maxX)),
                    Math.max(Math.abs(minZ), Math.abs(maxZ)));
            return Math.max(horizontal, maxY - Math.min(0.0f, minY));
        }
    }
}
