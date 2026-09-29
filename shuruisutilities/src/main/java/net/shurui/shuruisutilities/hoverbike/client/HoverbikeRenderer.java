package net.shurui.shuruisutilities.hoverbike.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.shurui.shuruisutilities.hoverbike.HoverbikeEntity;
import net.shurui.shuruisutilities.hoverbike.HoverbikeTunables;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;

// renders a hoverbike as its variant's baked OBJ (in the block atlas), quads pushed through the block model
// renderer onto an entity-cutout type. yaw from entity rotation + per-variant scale and centering translate.
public class HoverbikeRenderer extends EntityRenderer<HoverbikeEntity>
{
    public HoverbikeRenderer(EntityRendererProvider.Context ctx)
    {
        super(ctx);
    }

    @Override
    public void render(HoverbikeEntity bike, float entityYaw, float partialTicks, PoseStack pose,
            MultiBufferSource buffers, int packedLight)
    {
        super.render(bike, entityYaw, partialTicks, pose, buffers, packedLight);

        // Afterimage (Boo): the user is INVISIBLE to other players. From any viewer that is not the bike's own rider,
        // a race bike whose effective FX carries the afterimage bit draws NOTHING (bike hidden); the rider's player
        // model is hidden separately in RaceClientEvents. The rider's OWN client still sees a solid self plus the
        // ghost trail below, so effectiveFx returns the local DATA_RACE_FX there and this branch does not fire.
        boolean localOwn = Minecraft.getInstance().player != null
                && bike.getControllingPassenger() == Minecraft.getInstance().player;
        int effFx = net.shurui.shuruisutilities.racing.client.RaceBikeFx.effectiveFx(bike);
        if (bike.isRaceBike() && !localOwn
                && net.shurui.shuruisutilities.racing.physics.RaceFx.has(
                        effFx, net.shurui.shuruisutilities.racing.physics.RaceFx.AFTERIMAGE))
            return;

        int variant = Mth.clamp(bike.getVariant(), 1, 4);
        BakedModel model = HoverbikeModels.get(variant);
        if (model == null)
            return;
        BakedModel emissive = HoverbikeModels.getEmissiveModel(variant);

        // Afterimage (Boo): the rider's own client draws three fading trailing copies of the bike behind its heading,
        // BEFORE the solid bike, so the ghosts read behind it. Only when the local afterimage FX bit is set (a remote
        // afterimaged bike already returned above, invisible).
        boolean afterimage = bike.isRaceBike()
                && net.shurui.shuruisutilities.racing.physics.RaceFx.has(
                        bike.getRaceFx(), net.shurui.shuruisutilities.racing.physics.RaceFx.AFTERIMAGE);
        if (afterimage)
        {
            double rad = Math.toRadians(bike.getYRot());
            double backX = Math.sin(rad);   // opposite the +Z forward heading
            double backZ = -Math.cos(rad);
            for (int i = 3; i >= 1; i--)
            {
                pose.pushPose();
                pose.translate(backX * 0.45 * i, 0.05 * i, backZ * 0.45 * i);
                // dim, bluish, further copies fainter (no per-quad alpha on a block model, so brightness carries it).
                float dim = 0.55F - 0.12F * (i - 1);
                drawModel(pose, buffers, variant, model, null, entityYaw, LightTexture.FULL_BRIGHT,
                        dim * 0.7F, dim * 0.85F, dim, true);
                pose.popPose();
            }
        }

        // Gravity Crush: a squashed race bike renders flat (a pancake) while its squash window runs. Reads only the
        // effective (server-authored for a remote rider) FX, so everyone sees the crush.
        boolean squashed = bike.isRaceBike()
                && net.shurui.shuruisutilities.racing.physics.RaceFx.has(
                        effFx, net.shurui.shuruisutilities.racing.physics.RaceFx.SQUASH);

        // base pass (+ emissive), the solid bike.
        if (squashed)
        {
            pose.pushPose();
            pose.scale(0.9F, 0.4F, 0.9F); // flatten: keep most of the footprint, crush the height
        }
        drawModel(pose, buffers, variant, model, emissive, entityYaw, packedLight, 1.0F, 1.0F, 1.0F, false);
        if (squashed)
            pose.popPose();

        // Race auras / Flying Nimbus (self powerups). Drawn at the entity origin; reads only synced FX.
        if (bike.isRaceBike())
            net.shurui.shuruisutilities.racing.client.RaceBikeFx.render(bike, pose, buffers, packedLight, partialTicks);
    }

    // Draw the baked bike model (and its emissive glow pass) at the current pose with the shared transform. When
    // {@code translucent} the base pass goes through a translucent culling type (the afterimage ghost copies).
    private void drawModel(PoseStack pose, MultiBufferSource buffers, int variant, BakedModel model,
            BakedModel emissive, float entityYaw, int packedLight, float r, float g, float b, boolean translucent)
    {
        pose.pushPose();

        // ground clearance in world space (RENDER_Y_OFFSET = -minY*scale), before scaling so it stays world units
        pose.translate(0.0D, HoverbikeTunables.RENDER_Y_OFFSET[variant], 0.0D);

        // yaw from entity rotation (models face +Z)
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-entityYaw + 180.0F));

        // per-variant facing correction (v1/3/4 X-long need 90, v2 Z-long = 0). see MODEL_YAW_OFFSET_DEG.
        float yawOffset = HoverbikeTunables.MODEL_YAW_OFFSET_DEG[variant];
        if (yawOffset != 0.0F)
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yawOffset));

        float s = HoverbikeTunables.SCALE[variant];
        pose.scale(s, s, s);
        // center on origin: negate the OBJ bbox center. last in source order = applied first (model space,
        // pre-scale/rotation), so it's orientation-independent.
        pose.translate(-HoverbikeTunables.MODEL_CENTER_X[variant], 0.0D, -HoverbikeTunables.MODEL_CENTER_Z[variant]);

        var modelRenderer = Minecraft.getInstance().getBlockRenderer().getModelRenderer();

        // base pass: entity cutout makes alpha >=~10% pixels fully opaque (ghost copies use a translucent type).
        VertexConsumer vc = translucent
                ? buffers.getBuffer(RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS))
                : buffers.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS));
        modelRenderer.renderModel(pose.last(), vc, null, model,
                r, g, b, packedLight, OverlayTexture.NO_OVERLAY);

        // emissive pass (v1/3/4): same geometry/transform, fullbright translucent-emissive so the glow texture
        // lights up over the base. identical depth = no z-fighting.
        if (emissive != null)
        {
            VertexConsumer glow = buffers.getBuffer(RenderType.entityTranslucentEmissive(InventoryMenu.BLOCK_ATLAS));
            modelRenderer.renderModel(pose.last(), glow, null, emissive,
                    1.0F, 1.0F, 1.0F, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        }

        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(HoverbikeEntity entity)
    {
        // textures are in the block atlas via the baked model
        return InventoryMenu.BLOCK_ATLAS;
    }
}
