package net.shurui.dev.sdu.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.shurui.dev.sdu.entity.DukeSnipperjackEntity;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Duke Snipperjack's renderer. Scales the whole rig by {@link DukeSnipperjackEntity#SCALE} (the one
 * place the fight's size is defined, kept in step with the scaled bounding box so he is clickable and hittable
 * where he is drawn), and toggles the boss's optional prop bones (blade, knife, puppet, scissors, tea set,
 * monocle, death hat) by rig and current action, standing in for the ModelEngine part-visibility mechanics.
 */
public class DukeSnipperjackRenderer extends GeoEntityRenderer<DukeSnipperjackEntity> {

    public DukeSnipperjackRenderer(EntityRendererProvider.Context context) {
        super(context, new DukeSnipperjackModel());
        this.shadowRadius = 0.7f * DukeSnipperjackEntity.SCALE;
    }

    private static void show(BakedGeoModel model, String bone, boolean visible) {
        model.getBone(bone).ifPresent(b -> b.setHidden(!visible));
    }

    @Override
    public void preRender(PoseStack poseStack, DukeSnipperjackEntity animatable, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, float red, float green,
                          float blue, float alpha) {
        // Grow the whole model from its feet origin. Applied for both the base pass and the glow-layer re-render
        // so the emissive atlas lines up with the scaled body.
        float s = DukeSnipperjackEntity.SCALE;
        poseStack.scale(s, s, s);

        int rig = animatable.getRigPhase();
        int stance = animatable.getStance();
        String action = animatable.getAction();
        if (action == null) {
            action = "";
        }
        switch (rig) {
            case DukeSnipperjackEntity.RIG_PHASE1 -> {
                boolean throwing = action.equals("throw");
                boolean puppeting = action.equals("puppet");
                show(model, "blade_vfx", false);
                show(model, "knife", throwing);
                show(model, "puppet", puppeting);
                show(model, "mustache", puppeting);
                show(model, "pocketWatch", action.equals("pocket_watch"));
                // Blade is his weapon, hidden while he conjures a puppet or vanishes under his hat.
                show(model, "blade", !puppeting && !action.equals("hat"));
            }
            case DukeSnipperjackEntity.RIG_PHASE2 -> {
                show(model, "scissor", true);
                show(model, "scissor2", true);
                // The monocle shatters entering phase 3.
                show(model, "h_monocle", stance == DukeSnipperjackEntity.ST_PHASE2);
            }
            default -> {
                // Phase-0 tea rig: seated pre-fight, rising, interlude, re-arm, and death.
                boolean dead = stance == DukeSnipperjackEntity.ST_DEAD;
                show(model, "table", !dead);
                show(model, "chair", !dead);
                show(model, "teaCup", !dead);
                show(model, "teaPlate", !dead);
                show(model, "h_monocle", !dead);
                show(model, "deathHat", dead);
                // Weapons only appear at death (he pulls the blade before shattering).
                show(model, "blade", dead);
                show(model, "blade2", dead);
                show(model, "scissor", false);
                show(model, "scissor2", false);
            }
        }
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, red, green, blue, alpha);
    }
}
