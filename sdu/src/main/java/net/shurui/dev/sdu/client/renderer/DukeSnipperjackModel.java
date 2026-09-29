package net.shurui.dev.sdu.client.renderer;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.shurui.dev.sdu.entity.DukeSnipperjackEntity;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

/**
 * Resolves Duke Snipperjack's geo, texture and animation library by his current PHASE, so the single entity walks
 * through the pack's three combat rigs (pre-fight tea scene, phase 1, phase 2/3) exactly as the ModelEngine model
 * swaps did. The geo/animation names are the converted-1:1 clip names, so the boss's skill state machine can drive
 * them by the same names the MythicMobs {@code state} mechanics used.
 */
public class DukeSnipperjackModel extends GeoModel<DukeSnipperjackEntity> {

    private static final String GEO = "dmz_ragnarok:geo/entity/snipperjack/";
    private static final String TEX = "dmz_ragnarok:textures/entity/snipperjack/";
    private static final String ANIM = "dmz_ragnarok:animations/entity/snipperjack/";

    private static String variant(DukeSnipperjackEntity e) {
        return switch (e.getRigPhase()) {
            case DukeSnipperjackEntity.RIG_PHASE1 -> "snipperjack_phase1";
            case DukeSnipperjackEntity.RIG_PHASE2 -> "snipperjack_phase2";
            default -> "snipperjack_phase0";
        };
    }

    @Override
    public ResourceLocation getModelResource(DukeSnipperjackEntity animatable) {
        return ResourceLocation.parse(GEO + variant(animatable) + ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(DukeSnipperjackEntity animatable) {
        return ResourceLocation.parse(TEX + variant(animatable) + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(DukeSnipperjackEntity animatable) {
        return ResourceLocation.parse(ANIM + variant(animatable) + ".animation.json");
    }

    /**
     * Turn the pumpkin head toward the boss's gaze, the way the ModelEngine {@code h_} head bone did in the pack.
     * Without this the head is a static bone that keeps the body's facing, so while the Duke roots himself through a
     * cast or blinks around a circling fighter, his body (and the fixed head) can point away and the face reads as
     * "on backwards". This is a no-op when the body already faces the viewer. Same formula GeckoLib's
     * DefaultedEntityGeoModel uses, applied to this rig's head bone name.
     */
    @Override
    public void setCustomAnimations(DukeSnipperjackEntity animatable, long instanceId,
                                    AnimationState<DukeSnipperjackEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CoreGeoBone head = getAnimationProcessor().getBone("h_head");
        if (head == null || animationState == null) {
            return;
        }
        EntityModelData data = animationState.getData(DataTickets.ENTITY_MODEL_DATA);
        if (data == null) {
            return;
        }
        head.setRotX(data.headPitch() * Mth.DEG_TO_RAD);
        head.setRotY(data.netHeadYaw() * Mth.DEG_TO_RAD);
    }
}
