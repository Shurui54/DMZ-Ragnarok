package net.shurui.dev.sdu.client.renderer;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.shurui.dev.sdu.entity.PilafMechEntity;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

/**
 * Resolves the Pilaf Mech's geo, texture and animation library. It uses the owner's dedicated Pilaf Mech rig
 * (converted from the supplied Blockbench model): the piglin mech body geo with Pilaf's baked skin, and the model's
 * OWN animation library. Its bones are the mech's own rig (pelvis, hi_torso, piglin_*, hi_piglin_head, the two arm
 * chains, ...), so it binds to its own clips, not the DMZ saga rig. The entity plays those clip names directly.
 *
 * <h2>Swapping in a different skin later</h2>
 * The model and texture are separate ids on purpose. To repaint the mech, replace the PNG at
 * {@code assets/dmz_ragnarok/textures/entity/pilaf_mech.png}; that is the ONE place the skin lives. A wholly new rig
 * means new geo + animation files and repointing {@link #GEO}/{@link #ANIM} (and the clip names in the entity).
 */
public class PilafMechModel extends GeoModel<PilafMechEntity> {

    private static final ResourceLocation GEO =
            ResourceLocation.parse("dmz_ragnarok:geo/entity/pilaf_mech.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.parse("dmz_ragnarok:textures/entity/pilaf_mech.png");
    private static final ResourceLocation ANIM =
            ResourceLocation.parse("dmz_ragnarok:animations/entity/pilaf_mech.animation.json");

    @Override
    public ResourceLocation getModelResource(PilafMechEntity animatable) {
        return GEO;
    }

    @Override
    public ResourceLocation getTextureResource(PilafMechEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(PilafMechEntity animatable) {
        return ANIM;
    }

    /** Turn the mech's head toward its gaze, so it does not read as facing away while rooted through a cast. */
    @Override
    public void setCustomAnimations(PilafMechEntity animatable, long instanceId,
                                    AnimationState<PilafMechEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CoreGeoBone head = getAnimationProcessor().getBone("hi_piglin_head");
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
