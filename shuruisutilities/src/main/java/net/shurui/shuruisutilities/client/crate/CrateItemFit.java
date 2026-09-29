package net.shurui.shuruisutilities.client.crate;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.core.state.BoneSnapshot;

/**
 * Fits a crate's GeckoLib model into the space an item is drawn in, so a crate in an inventory slot is the crate
 * itself rather than a flat picture of its texture sheet.
 *
 * <p>The crates are BLOCK models: they sit on the floor of their block, and several of them are furniture sized and
 * deliberately overhang it (the Lootcrates boxes are 26 wide, Toffy's explosive is 72 tall). An item is drawn in a
 * one block cube centred on the origin, so dropping a block model into it unchanged puts most crates half out of
 * frame at wildly different sizes. Rather than hand tune a display transform per crate, and get it wrong again for
 * the next crate somebody adds, the model measures ITSELF: the bounding box of everything it actually draws is
 * centred and scaled so its longest axis exactly fills the cube. From there the item model's ordinary block display
 * transforms position it the same way they position any other block item.
 *
 * <p>Measuring is done once per baked model and cached. The cache holds the baked model it measured, so a resource
 * reload (which re-bakes and hands back a different instance) re-measures rather than keeping stale numbers.
 */
public final class CrateItemFit
{
    /**
     * Bone names ModelEngine reserves as metadata and never draws. These crates came from ModelEngine packs, and the
     * hand exported dungeon set still carries them: a {@code hitbox} box on the ground and {@code light} /
     * {@code particle} markers around the crate. In world the idle animation scales them to zero, but they are still
     * real bones, and a bounding box that counted them would be far larger than the crate anybody can see. The
     * subtree goes too, since their children are hidden with them.
     */
    private static final Set<String> NEVER_DRAWN = Set.of("hitbox", "light", "particle", "mount", "seat");

    /** Centre of the drawn bounds and the scale that makes its longest axis one block, per model. */
    private record Fit(BakedGeoModel measured, float centreX, float centreY, float centreZ, float scale) {}

    private static final Map<ResourceLocation, Fit> CACHE = new HashMap<>();

    private CrateItemFit() {}

    /**
     * Centre and scale the crate into the item cube.
     *
     * <p>The half block shift undoes the one {@code ItemRenderer} applies before handing over to a custom renderer:
     * it moves a block model's corner origin onto the display transform's origin, which is right for a block model
     * and wrong for us, because we are about to centre on the crate's own bounds instead.
     */
    public static void apply(PoseStack poseStack, ResourceLocation modelKey, BakedGeoModel model)
    {
        Fit fit = fitFor(modelKey, model);
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.scale(fit.scale(), fit.scale(), fit.scale());
        poseStack.translate(-fit.centreX(), -fit.centreY(), -fit.centreZ());
    }

    private static Fit fitFor(ResourceLocation modelKey, BakedGeoModel model)
    {
        Fit cached = CACHE.get(modelKey);
        if (cached != null && cached.measured() == model)
        {
            return cached;
        }
        Fit fit = measure(model);
        CACHE.put(modelKey, fit);
        return fit;
    }

    private static Fit measure(BakedGeoModel model)
    {
        float[] bounds = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
        for (GeoBone bone : model.topLevelBones())
        {
            accumulate(bone, new Matrix4f(), bounds);
        }

        // A model with nothing to draw would leave the bounds inverted. Sit it in the middle at natural size rather
        // than dividing by a negative extent and turning the crate inside out.
        if (bounds[3] < bounds[0])
        {
            return new Fit(model, 0.5F, 0.5F, 0.5F, 1.0F);
        }

        float width = bounds[3] - bounds[0];
        float height = bounds[4] - bounds[1];
        float depth = bounds[5] - bounds[2];
        float longest = Math.max(width, Math.max(height, depth));
        float scale = longest > 1.0e-4F ? 1.0F / longest : 1.0F;
        return new Fit(model,
                (bounds[0] + bounds[3]) * 0.5F,
                (bounds[1] + bounds[4]) * 0.5F,
                (bounds[2] + bounds[5]) * 0.5F,
                scale);
    }

    /**
     * Walk one bone and its children, growing the bounds by every vertex they draw.
     *
     * <p>This repeats what {@code GeoRenderer.renderRecursively} does to the pose stack, because a bounding box that
     * ignored the hierarchy would miss anything a rotated bone swings out (the creature crate's legs, the piano's,
     * the sweet tooth's tongue). The transform is read from the bone's INITIAL snapshot rather than its live values:
     * bones are baked once and shared with the block renderer, so the live numbers may be part way through that
     * crate's idle. A model that has never been animated has no snapshot yet, and there its live values ARE the bind
     * pose.
     */
    private static void accumulate(GeoBone bone, Matrix4f parent, float[] bounds)
    {
        if (NEVER_DRAWN.contains(bone.getName().toLowerCase(java.util.Locale.ROOT)))
        {
            return;
        }

        BoneSnapshot pose = bone.getInitialSnapshot();
        float offsetX = pose != null ? pose.getOffsetX() : bone.getPosX();
        float offsetY = pose != null ? pose.getOffsetY() : bone.getPosY();
        float offsetZ = pose != null ? pose.getOffsetZ() : bone.getPosZ();
        float rotX = pose != null ? pose.getRotX() : bone.getRotX();
        float rotY = pose != null ? pose.getRotY() : bone.getRotY();
        float rotZ = pose != null ? pose.getRotZ() : bone.getRotZ();
        float scaleX = pose != null ? pose.getScaleX() : bone.getScaleX();
        float scaleY = pose != null ? pose.getScaleY() : bone.getScaleY();
        float scaleZ = pose != null ? pose.getScaleZ() : bone.getScaleZ();

        Matrix4f matrix = new Matrix4f(parent);
        matrix.translate(-offsetX / 16F, offsetY / 16F, offsetZ / 16F);
        matrix.translate(bone.getPivotX() / 16F, bone.getPivotY() / 16F, bone.getPivotZ() / 16F);
        rotate(matrix, rotX, rotY, rotZ);
        matrix.scale(scaleX, scaleY, scaleZ);
        matrix.translate(-bone.getPivotX() / 16F, -bone.getPivotY() / 16F, -bone.getPivotZ() / 16F);

        for (GeoCube cube : bone.getCubes())
        {
            Matrix4f cubeMatrix = new Matrix4f(matrix);
            cubeMatrix.translate((float) cube.pivot().x() / 16F, (float) cube.pivot().y() / 16F,
                    (float) cube.pivot().z() / 16F);
            rotate(cubeMatrix, (float) cube.rotation().x(), (float) cube.rotation().y(),
                    (float) cube.rotation().z());
            cubeMatrix.translate((float) -cube.pivot().x() / 16F, (float) -cube.pivot().y() / 16F,
                    (float) -cube.pivot().z() / 16F);

            for (GeoQuad quad : cube.quads())
            {
                if (quad == null)
                {
                    continue;
                }
                for (GeoVertex vertex : quad.vertices())
                {
                    Vector4f point = cubeMatrix.transform(new Vector4f(
                            vertex.position().x(), vertex.position().y(), vertex.position().z(), 1.0F));
                    bounds[0] = Math.min(bounds[0], point.x());
                    bounds[1] = Math.min(bounds[1], point.y());
                    bounds[2] = Math.min(bounds[2], point.z());
                    bounds[3] = Math.max(bounds[3], point.x());
                    bounds[4] = Math.max(bounds[4], point.y());
                    bounds[5] = Math.max(bounds[5], point.z());
                }
            }
        }

        for (GeoBone child : bone.getChildBones())
        {
            accumulate(child, matrix, bounds);
        }
    }

    /** Z then Y then X, which is the order GeckoLib composes a rotation in and is not interchangeable. */
    private static void rotate(Matrix4f matrix, float x, float y, float z)
    {
        if (z != 0)
        {
            matrix.rotate(new Quaternionf().rotationXYZ(0, 0, z));
        }
        if (y != 0)
        {
            matrix.rotate(new Quaternionf().rotationXYZ(0, y, 0));
        }
        if (x != 0)
        {
            matrix.rotate(new Quaternionf().rotationXYZ(x, 0, 0));
        }
    }
}
