package net.shurui.shuruisutilities.client.saiyan;

import java.util.ArrayList;
import java.util.List;

import com.dragonminez.client.render.layer.DMZSkinLayer;
import com.dragonminez.client.render.util.ModRenderTypes;
import com.dragonminez.client.util.ColorUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import net.shurui.shuruisutilities.saiyan.SaiyanAppearance;

/**
 * SU-owned reimplementation of DragonMineZ's player body/face/tail layer stack, driven by the {@link SaiyanAppearance}
 * contract so it serves any planet-saiyan chassis (hostile garrison, passive citizen, trader). DMZ's own {@code
 * DMZSkinLayer} is hard-typed to {@code AbstractClientPlayer} and reads player-only {@code StatsCapability}, so it
 * cannot be reused; this reproduces just the saiyan humanoid paths it needs, driven by the entity's synced appearance
 * fields instead. It reuses DMZ's public render helpers ({@link ModRenderTypes}
 * for the overlay render type and {@link DMZSkinLayer#getSafeTexture} for the validate-or-fall-back-and-log-once texture
 * lookup), so a missing DMZ texture degrades to DMZ's blank {@code null.png} rather than crashing or vanishing.
 *
 * <h2>What it draws (mirrors DMZ's SkinGathererProvider + DMZSkinLayer for a saiyan, bodyType != 0)</h2>
 * <ul>
 *   <li>EVERY saiyan, named or generic: the whole model re-rendered with {@code humansaiyan/bodytype_{male|female}_{1,2}
 *       .png} tinted by the skin colour, then the human face overlays on the head bone, then the {@code tail1.png} tail
 *       tinted by the tail colour.</li>
 * </ul>
 *
 * <p>Named saiyans are drawn with this SAME DMZ custom-character look as everyone else; they are distinguished only by
 * their PINNED gender, hair (a DMZ hair code, in the hair layer), hair colour, tail colour and armor set, plus the
 * account-name nameplate above their head. They no longer render a real Minecraft player skin, so this layer has no
 * network path and nothing to go wrong offline, on a dedicated server, or when Mojang throttles.
 *
 * <p>Unlike DMZ's female human/saiyan path, the chestplate is NEVER suppressed here: armor is drawn by a stock GeckoLib
 * {@code ItemArmorGeoLayer} in the renderer, which has no such gender rule, so a female garrison saiyan shows her armor.
 */
public class PlanetSaiyanBodyLayer<T extends Mob & GeoEntity & SaiyanAppearance> extends GeoRenderLayer<T>
{
    private static final String RACES = "textures/entity/races/";
    private static final String BODY_DIR = RACES + "humansaiyan/";
    private static final String FACE_DIR = RACES + "humansaiyan/faces/";
    private static final ResourceLocation TAIL_TEX =
            ResourceLocation.fromNamespaceAndPath("dragonminez", RACES + "tail1.png");

    // the armor bones present on the human / majin_slim race geos. Hidden during every body/face/tail re-render so the
    // body texture never paints over the armor, exactly as DMZ's DMZSkinLayer does.
    private static final String[] ARMOR_BONES =
            {"armorHead", "armorBody", "armorLeggingsBody", "armorRightArm", "armorLeftArm",
                    "armorRightLeg", "armorRightBoot", "armorLeftLeg", "armorLeftBoot"};

    // the MAJIN straight-tail bone chain. It exists ONLY on the female body geo (majin_slim.geo.json), which DragonMineZ
    // reuses for female humans and saiyans; that geo carries BOTH the saiyan tail (tail1..tail5) AND this separate majin
    // tail (tail1m..tail6m). Our whole-model tail pass paints tail1.png over every bone, so without hiding this chain a
    // female saiyan renders the majin tail too, which reads as a thick straight (frost-demon-looking) tail alongside the
    // real saiyan tail. DragonMineZ hides exactly this chain for non-majin races in BoneVisibilityHandler.updateVisibility
    // (it setHiddenRecursive on tail1m unless the model is a majin), a step our hand-rolled stack never ran. Hidden during
    // every whole-model re-render below; on the male human geo (no such bones) each lookup is simply a no-op.
    private static final String[] MAJIN_TAIL_BONES =
            {"tail1m", "tail2m", "tail3m", "tail4m", "tail5m", "tail6m"};

    private static final float[] WHITE = {1.0F, 1.0F, 1.0F};

    public PlanetSaiyanBodyLayer(GeoRenderer<T> renderer)
    {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, T animatable, BakedGeoModel model,
                       RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                       float partialTick, int packedLight, int packedOverlay)
    {
        if (animatable == null)
        {
            return;
        }

        float[] tailColor = ColorUtils.rgbIntToFloat(animatable.getTailColor());

        // every saiyan, named or generic, draws the DMZ custom-character look: body texture tinted by the skin colour,
        // then the human face overlays. Named NPCs no longer render a real player skin; their identity comes from their
        // pinned colours/hair/armor and their nameplate.
        float[] skinColor = ColorUtils.rgbIntToFloat(animatable.getSkinColor());
        ResourceLocation bodyTex = resolveBodyTexture(animatable);
        reRenderWholeModel(model, poseStack, bufferSource, animatable,
                RenderType.entityCutoutNoCull(bodyTex), skinColor, partialTick, packedLight, packedOverlay);
        renderFace(model, poseStack, bufferSource, animatable, partialTick, packedLight, packedOverlay);

        // the tail rides tail bones present on both the human and majin_slim geos; drawn for every saiyan.
        reRenderWholeModel(model, poseStack, bufferSource, animatable, RenderType.entityCutoutNoCull(TAIL_TEX),
                tailColor, partialTick, packedLight, packedOverlay);
    }

    private static ResourceLocation resolveBodyTexture(SaiyanAppearance animatable)
    {
        String gender = animatable.isMale() ? "male" : "female";
        int bodyType = animatable.getBodyType();
        ResourceLocation primary =
                ResourceLocation.fromNamespaceAndPath("dragonminez", BODY_DIR + "bodytype_" + gender + "_" + bodyType + ".png");
        // fall back to body type 1 (which DragonMineZ always ships for both genders), NOT a "_0" that does not exist:
        // an out-of-range/unshipped body type must land on a real skin texture, never on the blank null.png that would
        // leave a see-through head reading as blank/white.
        ResourceLocation fallback =
                ResourceLocation.fromNamespaceAndPath("dragonminez", BODY_DIR + "bodytype_" + gender + "_1.png");
        return DMZSkinLayer.getSafeTexture(primary, fallback);
    }

    // re-render the entire model with one texture and a flat RGB tint, hiding the armor bones for the duration so the
    // body texture never paints the armor. Mirrors DMZSkinLayer.renderLayerWholeModel (minus the transformation tint,
    // which is form/aura state a garrison saiyan does not have).
    private void reRenderWholeModel(BakedGeoModel model, PoseStack poseStack, MultiBufferSource bufferSource,
                                    T animatable, RenderType renderType, float[] rgb,
                                    float partialTick, int packedLight, int packedOverlay)
    {
        List<GeoBone> hidden = new ArrayList<>();
        for (String boneName : ARMOR_BONES)
        {
            model.getBone(boneName).ifPresent(bone ->
            {
                if (!bone.isHidden())
                {
                    bone.setHidden(true);
                    hidden.add(bone);
                }
            });
        }
        // hide the majin straight-tail chain too, so a female saiyan (majin_slim geo) never paints it. A no-op on the
        // male human geo, which has no such bones.
        for (String boneName : MAJIN_TAIL_BONES)
        {
            model.getBone(boneName).ifPresent(bone ->
            {
                if (!bone.isHidden())
                {
                    bone.setHidden(true);
                    hidden.add(bone);
                }
            });
        }
        try
        {
            this.getRenderer().reRender(model, poseStack, bufferSource, animatable, renderType,
                    bufferSource.getBuffer(renderType), partialTick, packedLight, packedOverlay, rgb[0], rgb[1], rgb[2], 1.0F);
        }
        finally
        {
            // restore only the bones this pass hid, so a thrown render cannot leave the shared baked model in a hidden
            // state mid-frame. The renderer re-establishes the full baseline next frame regardless, but keep every pass
            // self-contained so hiding never leaks even within a single frame.
            for (GeoBone bone : hidden)
            {
                bone.setHidden(false);
            }
        }
    }

    // draw the human face overlays on the head bone only: hide every other top-level bone and lightly inflate the head
    // so the overlays sit just outside the skin, exactly as DMZSkinLayer.renderFace does before dispatching a human face.
    private void renderFace(BakedGeoModel model, PoseStack poseStack, MultiBufferSource bufferSource,
                            T animatable, float partialTick, int packedLight, int packedOverlay)
    {
        model.getBone("head").ifPresent(headBone ->
        {
            float originalZ = headBone.getPosZ();
            float originalSX = headBone.getScaleX();
            float originalSY = headBone.getScaleY();
            float originalSZ = headBone.getScaleZ();
            float inflation = 0.002F;
            headBone.setPosZ(originalZ - inflation);
            headBone.setScaleX(originalSX + inflation);
            headBone.setScaleY(originalSY + inflation);
            headBone.setScaleZ(originalSZ + inflation);
            List<GeoBone> hidden = hideAllTopLevelExceptHead(model, headBone);
            try
            {
                dispatchHumanFace(model, poseStack, bufferSource, animatable, partialTick, packedLight, packedOverlay);
            }
            finally
            {
                for (GeoBone bone : hidden)
                {
                    bone.setHidden(false);
                }
                headBone.setPosZ(originalZ);
                headBone.setScaleX(originalSX);
                headBone.setScaleY(originalSY);
                headBone.setScaleZ(originalSZ);
            }
        });
    }

    private void dispatchHumanFace(BakedGeoModel model, PoseStack poseStack, MultiBufferSource bufferSource,
                                   T animatable, float partialTick, int packedLight,
                                   int packedOverlay)
    {
        int eyes = animatable.getEyesType();
        int nose = animatable.getNoseType();
        int mouth = animatable.getMouthType();
        float[] eye1 = ColorUtils.rgbIntToFloat(animatable.getEye1Color());
        float[] eye2 = ColorUtils.rgbIntToFloat(animatable.getEye2Color());
        float[] skin = ColorUtils.rgbIntToFloat(animatable.getSkinColor());
        float[] hair = ColorUtils.rgbIntToFloat(animatable.getHairColor());

        String eyeBase = "humansaiyan_eye_" + eyes;
        renderColoredLayer(model, poseStack, bufferSource, animatable,
                safeFace(eyeBase + "_0.png", "humansaiyan_eye_0_0.png"), WHITE, partialTick, packedLight, packedOverlay);
        renderColoredLayer(model, poseStack, bufferSource, animatable,
                safeFace(eyeBase + "_1.png", "humansaiyan_eye_0_1.png"), eye1, partialTick, packedLight, packedOverlay);
        renderColoredLayer(model, poseStack, bufferSource, animatable,
                safeFace(eyeBase + "_2.png", "humansaiyan_eye_0_2.png"), eye2, partialTick, packedLight, packedOverlay);
        renderColoredLayer(model, poseStack, bufferSource, animatable,
                safeFace(eyeBase + "_3.png", "humansaiyan_eye_0_3.png"), hair, partialTick, packedLight, packedOverlay);
        renderColoredLayer(model, poseStack, bufferSource, animatable,
                safeFace("humansaiyan_nose_" + nose + ".png", "humansaiyan_nose_0.png"), skin,
                partialTick, packedLight, packedOverlay);
        renderColoredLayer(model, poseStack, bufferSource, animatable,
                safeFace("humansaiyan_mouth_" + mouth + ".png", "humansaiyan_mouth_0.png"), skin,
                partialTick, packedLight, packedOverlay);
    }

    // resolve a face texture with a per-feature "index 0" fallback, validated-and-logged-once through DMZ's cache.
    private static ResourceLocation safeFace(String file, String fallbackFile)
    {
        ResourceLocation primary = ResourceLocation.fromNamespaceAndPath("dragonminez", FACE_DIR + file);
        ResourceLocation fallback = ResourceLocation.fromNamespaceAndPath("dragonminez", FACE_DIR + fallbackFile);
        return DMZSkinLayer.getSafeTexture(primary, fallback);
    }

    private void renderColoredLayer(BakedGeoModel model, PoseStack poseStack, MultiBufferSource bufferSource,
                                    T animatable, ResourceLocation texture, float[] rgb,
                                    float partialTick, int packedLight, int packedOverlay)
    {
        RenderType renderType = ModRenderTypes.skinOverlayCutout(texture);
        this.getRenderer().reRender(model, poseStack, bufferSource, animatable, renderType,
                bufferSource.getBuffer(renderType), partialTick, packedLight, packedOverlay, rgb[0], rgb[1], rgb[2], 1.0F);
    }

    private static List<GeoBone> hideAllTopLevelExceptHead(BakedGeoModel model, GeoBone headBone)
    {
        List<GeoBone> hidden = new ArrayList<>();
        for (GeoBone bone : model.topLevelBones())
        {
            if (!bone.isHidden())
            {
                hidden.add(bone);
                bone.setHidden(true);
            }
        }
        headBone.setHidden(false);
        for (GeoBone parent = headBone.getParent(); parent != null; parent = parent.getParent())
        {
            parent.setHidden(false);
        }
        return hidden;
    }
}
