package net.shurui.shuruisutilities.cosmetics.wardrobe.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Draws a triggered-animation RIG with no entity, using GeckoLib's {@link GeoObjectRenderer}. One shared instance
 * ({@link #INSTANCE}) draws every playing rig, bound each pass to {@link CosmeticAnimAnimatable#INSTANCE}.
 *
 * <h2>Why preRender is overridden</h2>
 * {@code GeoObjectRenderer} is built for a block-like object: its {@code preRender} centres the model with a
 * {@code translate(0.5, 0.51, 0.5)}. These rigs are authored in the ENTITY convention (feet at the origin), the same
 * Bedrock export the cosmetic pets and mounts use, so the block centre offset is wrong. The override reproduces the
 * standard entity model transform instead (scale the model, no block centre), and {@link #draw} applies the
 * {@code 180 - yaw} facing GeckoLib's entity renderer applies, so the rig stands at the effect's feet oriented to the
 * subject's yaw exactly as a standing NPC at that yaw would.
 *
 * <h2>Full bright</h2>
 * The rigs are magical FX (fire, gates, spirits), so they are drawn at {@link LightTexture#FULL_BRIGHT} to read as
 * glowing at any time of day. The source pack ships no separate emissive map, so an {@link AutoGlowingGeoLayer} is
 * added only defensively: it lights a {@code _glowmask} texture if the converter ever produces one, and does nothing
 * when (as now) there is none.
 */
@OnlyIn(Dist.CLIENT)
public final class CosmeticAnimGeoRenderer extends GeoObjectRenderer<CosmeticAnimAnimatable>
{
    public static final CosmeticAnimGeoRenderer INSTANCE = new CosmeticAnimGeoRenderer();

    private CosmeticAnimGeoRenderer()
    {
        super(new CosmeticAnimGeoModel());
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
    }

    /**
     * Whether a rig key can be drawn on THIS client: both its in and out geo are present in the baked (listed) model
     * cache. The store checks this before claiming a rig slot, so the list-vs-read crash cannot happen: a key whose
     * geo was never listed falls back to particles and never reaches the renderer.
     */
    public static boolean rigAvailable(String key)
    {
        if (key == null || key.isBlank())
            return false;
        java.util.Map<ResourceLocation, BakedGeoModel> baked = GeckoLibCache.getBakedModels();
        ResourceLocation in = new ResourceLocation(ShuruisUtilities.MODID, "geo/fx/cosmetic_anim/anim_in_" + key + ".geo.json");
        ResourceLocation out = new ResourceLocation(ShuruisUtilities.MODID, "geo/fx/cosmetic_anim/anim_out_" + key + ".geo.json");
        // The animation files are checked too: the rigs are streamed by the Ragnarok Key with their animations, and
        // GeckoLib throws on an unbaked animation exactly as it does on an unbaked geo.
        java.util.Map<ResourceLocation, ?> anims = GeckoLibCache.getBakedAnimations();
        boolean hasIn = baked.containsKey(in) && anims.containsKey(new ResourceLocation(ShuruisUtilities.MODID,
                "animations/fx/cosmetic_anim/anim_in_" + key + ".animation.json"));
        boolean hasOut = baked.containsKey(out) && anims.containsKey(new ResourceLocation(ShuruisUtilities.MODID,
                "animations/fx/cosmetic_anim/anim_out_" + key + ".animation.json"));
        if ((!hasIn || !hasOut) && MISSING_RIGS_LOGGED.add(key))
        {
            // Log ONCE per key so this is never silent again: the exact keys looked for and which the baked model
            // cache is missing. If both read false the rigs are not in the resource pack this client loaded (or the
            // namespace/path drifted); if only one is missing the pair is incomplete.
            net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.warn(
                    "[Cosmetics] Animation rig '{}' unavailable, falling back to particles. Looked for in={} (present={}), out={} (present={}). Baked cosmetic_anim keys present: {}",
                    key, in, hasIn, out, hasOut, listCosmeticAnimKeys(baked));
        }
        return hasIn && hasOut;
    }

    /** One-shot guard so a missing rig key is reported exactly once per client session rather than every accept. */
    private static final java.util.Set<String> MISSING_RIGS_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The cosmetic_anim geo keys actually baked, for the one-shot diagnostic, so a path/namespace drift is visible. */
    private static java.util.List<String> listCosmeticAnimKeys(java.util.Map<ResourceLocation, BakedGeoModel> baked)
    {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (ResourceLocation rl : baked.keySet())
            if (rl.getPath().contains("fx/cosmetic_anim/"))
                out.add(rl.toString());
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * Draw one rig at a camera-relative position, facing the subject's yaw, at the given scale. On the first pass for
     * an effect the store passes {@code firstDraw} so the {@code actived} clip is triggered for this instance id and
     * plays from the start.
     */
    public void draw(PoseStack poseStack, MultiBufferSource buffer, double dx, double dy, double dz, float yaw,
            String rigKey, boolean arriving, float scale, long instanceId, boolean firstDraw)
    {
        CosmeticAnimAnimatable.INSTANCE.set(rigKey, arriving, instanceId);
        if (firstDraw)
        {
            // Trigger on the manager for THIS instance id so each concurrent rig has its own timeline. getManagerForId
            // creates the manager (registering the controller) if it is the id's first use.
            CosmeticAnimAnimatable.INSTANCE.getAnimatableInstanceCache().getManagerForId(instanceId)
                    .tryTriggerAnimation("actived");
        }
        poseStack.pushPose();
        poseStack.translate(dx, dy, dz);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        if (scale != 1.0F && scale > 0.0F)
            poseStack.scale(scale, scale, scale);
        render(poseStack, CosmeticAnimAnimatable.INSTANCE, buffer, null, null, LightTexture.FULL_BRIGHT);
        poseStack.popPose();
    }

    @Override
    public long getInstanceId(CosmeticAnimAnimatable animatable)
    {
        // Per-effect id (ring-allocated by the store), so one shared animatable drives many independent timelines.
        return animatable.instanceId();
    }

    @Override
    public void preRender(PoseStack poseStack, CosmeticAnimAnimatable animatable, BakedGeoModel model,
            MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender, float partialTick,
            int packedLight, int packedOverlay, float red, float green, float blue, float alpha)
    {
        // Entity-convention transform, NOT the GeoObjectRenderer block centre: scale the model at the feet with no
        // 0.5 block offset, so the rig stands where draw() placed it.
        this.objectRenderTranslations = new Matrix4f(poseStack.last().pose());
        scaleModelForRender(this.scaleWidth, this.scaleHeight, poseStack, animatable, model, isReRender, partialTick,
                packedLight, packedOverlay);
        this.modelRenderTranslations = new Matrix4f(poseStack.last().pose());
    }
}
