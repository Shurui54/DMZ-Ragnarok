package net.shurui.shuruisutilities.client.cosmetics.pet;

import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import software.bernie.geckolib.renderer.GeoEntityRenderer;

import net.shurui.shuruisutilities.cosmetics.wardrobe.pet.CosmeticPetEntity;
import net.shurui.shuruisutilities.cosmetics.wardrobe.pet.CosmeticPetType;

/**
 * Standard GeckoLib renderer for the cosmetic pet, over {@link CosmeticPetModel}. A per-pet uniform scale from
 * {@link CosmeticPetType} is applied around the base render so the small chibi rigs come to a sensible in-world size
 * without re-authoring the geo.
 *
 * <p>Honours the client's {@link CosmeticPetClientOptions#hidden()} preference: a viewer who turned pets off draws
 * nothing at all, which is the cheap escape hatch for a low-end machine. The server still spawns and tracks the pet;
 * only this viewer's draw is skipped.
 */
public class CosmeticPetRenderer extends GeoEntityRenderer<CosmeticPetEntity>
{
    public CosmeticPetRenderer(EntityRendererProvider.Context context)
    {
        super(context, new CosmeticPetModel());
        this.shadowRadius = 0.3F;
    }

    @Override
    public void render(CosmeticPetEntity pet, float entityYaw, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight)
    {
        if (CosmeticPetClientOptions.hidden())
            return;
        // The rig is streamed by the Ragnarok Key (CosmeticAssetCache). Until it is baked GeckoLib would THROW on the
        // render thread, so the entity is simply not drawn until the pack arrives and resources reload.
        if (!net.shurui.shuruisutilities.cosmetics.wardrobe.client.CosmeticArt.rigBaked(
                getGeoModel().getModelResource(pet), getGeoModel().getAnimationResource(pet)))
            return;
        CosmeticPetType spec = pet.spec();
        float s = spec == null ? 1.0F : spec.renderScale;
        if (s != 1.0F)
        {
            poseStack.pushPose();
            poseStack.scale(s, s, s);
            super.render(pet, entityYaw, partialTick, poseStack, bufferSource, packedLight);
            poseStack.popPose();
        }
        else
        {
            super.render(pet, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        }
    }

    // GeoEntityRenderer feeds applyRotations a body yaw of 0 for anything that is not a LivingEntity, so a plain
    // Entity like the pet would face one fixed direction and never turn. Drive the rotation from the pet's own
    // interpolated yaw instead, which the entity sets each tick from its heading (movement, or the owner when idle).
    @Override
    protected void applyRotations(CosmeticPetEntity pet, PoseStack poseStack, float ageInTicks, float rotationYaw,
            float partialTick)
    {
        float yaw = Mth.rotLerp(partialTick, pet.yRotO, pet.getYRot());
        super.applyRotations(pet, poseStack, ageInTicks, yaw, partialTick);
    }

    // The owner-name tag (set as the pet's custom name server side) is shown only while the viewer is LOOKING at the
    // pet, and is suppressed for a viewer who hid pets and when the owner is invisible, so an invisible player's pet
    // does not give them away.
    /** How far away the viewer may be and still see the name, in blocks. */
    private static final double NAME_LOOK_RANGE = 12.0D;

    /** How closely the viewer must be looking at it: the cosine of the allowed angle, about 12 degrees off centre. */
    private static final double NAME_LOOK_DOT = 0.978D;

    @Override
    public boolean shouldShowName(CosmeticPetEntity pet)
    {
        if (CosmeticPetClientOptions.hidden())
            return false;
        if (!super.shouldShowName(pet))
            return false;
        UUID ownerId = pet.getOwnerUUID();
        if (ownerId != null)
        {
            Player owner = pet.level().getPlayerByUUID(ownerId);
            if (owner != null && owner.isInvisible())
                return false;
        }
        // Only while the viewer is actually LOOKING at it. A pet trails its owner everywhere, so a tag floating over
        // every pet in a busy area is noise. The crosshair cannot answer this: a cosmetic pet is deliberately not
        // pickable (it must never eat a click meant for something behind it), so mc.hitResult never names one. So
        // compare the viewer's look direction with the direction to the pet's head, which is what "looking at it"
        // means to a player, and require it to be reasonably close by.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return false;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 toPet = new Vec3(pet.getX() - eye.x, pet.getY() + pet.getBbHeight() * 0.75D - eye.y, pet.getZ() - eye.z);
        double distance = toPet.length();
        if (distance > NAME_LOOK_RANGE || distance < 1.0E-4D)
            return false;
        return mc.player.getLookAngle().dot(toPet.scale(1.0D / distance)) >= NAME_LOOK_DOT;
    }
}
