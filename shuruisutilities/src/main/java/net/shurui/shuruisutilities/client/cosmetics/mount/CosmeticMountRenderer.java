package net.shurui.shuruisutilities.client.cosmetics.mount;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;

import software.bernie.geckolib.renderer.GeoEntityRenderer;

import net.shurui.shuruisutilities.cosmetics.wardrobe.mount.CosmeticMountEntity;
import net.shurui.shuruisutilities.cosmetics.wardrobe.mount.CosmeticMountType;

/**
 * Standard GeckoLib renderer for the cosmetic mount, over {@link CosmeticMountModel}. Registering this SU-owned
 * renderer is what draws the entity through our own model. A per-mount uniform scale from {@link CosmeticMountType}
 * is applied around the base render so rigs of different native sizes can be brought to a sensible in-world size
 * without re-authoring the geo.
 */
public class CosmeticMountRenderer extends GeoEntityRenderer<CosmeticMountEntity>
{
    public CosmeticMountRenderer(EntityRendererProvider.Context context)
    {
        super(context, new CosmeticMountModel());
        this.shadowRadius = 0.6F;
    }

    /**
     * Turn the rig to face where the mount is heading. GeckoLib's {@code GeoEntityRenderer} derives the body yaw it
     * rotates by from {@code LivingEntity.yBodyRot}, a field a plain {@link net.minecraft.world.entity.Entity} like
     * this mount does not have, so it arrives as 0 and the model is drawn facing one fixed direction no matter which
     * way the mount steers (the "mounts do not turn" report). Feed the mount's own interpolated yaw instead, which is
     * the same value a vanilla entity renderer, and so the hoverbike, turns by. {@code driveMovement} eases
     * {@code yRot} toward the rider's look and leaves {@code yRotO} as the real previous-frame yaw, so this
     * interpolation curves the heading smoothly rather than snapping.
     */
    @Override
    protected void applyRotations(CosmeticMountEntity mount, PoseStack poseStack, float ageInTicks, float rotationYaw,
            float partialTick)
    {
        float yaw = Mth.rotLerp(partialTick, mount.yRotO, mount.getYRot());
        super.applyRotations(mount, poseStack, ageInTicks, yaw, partialTick);
    }

    @Override
    public void render(CosmeticMountEntity mount, float entityYaw, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight)
    {
        // The rig is streamed by the Ragnarok Key (CosmeticAssetCache). Until it is baked GeckoLib would THROW on the
        // render thread, so the entity is simply not drawn until the pack arrives and resources reload.
        if (!net.shurui.shuruisutilities.cosmetics.wardrobe.client.CosmeticArt.rigBaked(
                getGeoModel().getModelResource(mount), getGeoModel().getAnimationResource(mount)))
            return;
        CosmeticMountType spec = mount.spec();
        float s = spec == null ? 1.0F : spec.renderScale;
        if (s != 1.0F)
        {
            poseStack.pushPose();
            poseStack.scale(s, s, s);
            super.render(mount, entityYaw, partialTick, poseStack, bufferSource, packedLight);
            poseStack.popPose();
        }
        else
        {
            super.render(mount, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        }
    }
}
