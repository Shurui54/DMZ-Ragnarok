package net.shurui.dev.sdu.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.shurui.dev.sdu.DmzNpc;

/**
 * Custom 64x64 humanoid armor model for "Shurui's Armor", copied geometry-verbatim from DMZ's
 * {@code com.dragonminez.common.init.armor.client.model.ArmorBaseModel} (DMZ 2.1.2) and
 * re-namespaced into sdu so we do not depend on DMZ's registered layer at runtime.
 *
 * <p>Our recolored 64x64 armor textures are authored against these UVs; the vanilla armor model
 * (which this replaces via {@code ShuruisArmorItem#initializeClient}) would garble them.
 *
 * <p>Extends {@link HumanoidModel} so vanilla's {@code HumanoidArmorLayer} can drive per-slot part
 * visibility (via {@code setPartVisibility}) exactly as it does for DMZ's model: the standard parts
 * head/hat/body/rightArm/leftArm/rightLeg/leftLeg carry the extra shoulder-pad and belt cubes as
 * children (under body/arms/legs), so CHEST shows body+arms(+shoulder pads), LEGS shows legs(+belt),
 * FEET shows the lower legs.
 */
public class ShuruisArmorModel extends HumanoidModel<LivingEntity> {

    public static final ModelLayerLocation LAYER_LOCATION =
            new ModelLayerLocation(new ResourceLocation(DmzNpc.MODID, "armors"), "base");

    private final ModelPart head;
    private final ModelPart body;
    private final ModelPart right_arm;
    private final ModelPart left_arm;
    private final ModelPart right_leg;
    private final ModelPart left_leg;
    private final ModelPart hat;

    public ShuruisArmorModel(ModelPart root) {
        super(root);
        this.head = root.getChild("head");
        this.body = root.getChild("body");
        this.right_arm = root.getChild("right_arm");
        this.left_arm = root.getChild("left_arm");
        this.right_leg = root.getChild("right_leg");
        this.left_leg = root.getChild("left_leg");
        this.hat = root.getChild("hat");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();
        PartDefinition head = partdefinition.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-4.0f, -8.0f, -4.0f, 8.0f, 8.0f, 8.0f, new CubeDeformation(0.2f)), PartPose.offset(0.0f, 0.0f, 0.0f));
        PartDefinition body = partdefinition.addOrReplaceChild("body", CubeListBuilder.create().texOffs(16, 16).addBox(-4.0f, 0.0f, -2.0f, 8.0f, 12.0f, 4.0f, new CubeDeformation(0.2f)), PartPose.offset(0.0f, 0.0f, 0.0f));
        PartDefinition cube_r1 = body.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(3, 56).addBox(-2.0f, -2.0f, -1.0f, 5.0f, 7.0f, 1.0f, new CubeDeformation(0.0f)), PartPose.offsetAndRotation(-0.5f, 10.9f, -1.65f, -0.2618f, 0.0f, 0.0f));
        PartDefinition cube_r2 = body.addOrReplaceChild("cube_r2", CubeListBuilder.create().texOffs(51, 50).mirror().addBox(-1.0f, -2.0f, -2.0f, 1.0f, 8.0f, 4.0f, new CubeDeformation(0.0f)).mirror(false), PartPose.offsetAndRotation(4.8f, 10.25f, 0.0f, 0.0f, 0.0f, -0.1309f));
        PartDefinition cube_r3 = body.addOrReplaceChild("cube_r3", CubeListBuilder.create().texOffs(51, 50).addBox(-2.0f, -2.0f, -2.0f, 1.0f, 8.0f, 4.0f, new CubeDeformation(0.0f)), PartPose.offsetAndRotation(-2.85f, 10.65f, 0.0f, 0.0f, 0.0f, 0.1309f));
        PartDefinition right_arm = partdefinition.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(40, 16).addBox(-3.0f, -2.0f, -2.0f, 4.0f, 12.0f, 4.0f, new CubeDeformation(0.18f)), PartPose.offset(-5.0f, 2.0f, 0.0f));
        PartDefinition cube_r4 = right_arm.addOrReplaceChild("cube_r4", CubeListBuilder.create().texOffs(42, 37).addBox(-2.0f, -2.0f, -2.0f, 6.0f, 4.0f, 5.0f, new CubeDeformation(0.1f)), PartPose.offsetAndRotation(-2.7f, -0.75f, -0.5f, 0.0f, 0.0f, 0.1309f));
        PartDefinition left_arm = partdefinition.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(32, 48).addBox(-1.0f, -2.0f, -2.0f, 4.0f, 12.0f, 4.0f, new CubeDeformation(0.18f)), PartPose.offset(5.0f, 2.0f, 0.0f));
        PartDefinition cube_r5 = left_arm.addOrReplaceChild("cube_r5", CubeListBuilder.create().texOffs(25, 33).addBox(-1.0f, -2.0f, -2.5f, 6.0f, 4.0f, 5.0f, new CubeDeformation(0.1f)), PartPose.offsetAndRotation(-0.25f, -0.35f, 0.0f, 0.0f, 0.0f, -0.1309f));
        PartDefinition right_leg = partdefinition.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(0, 16).addBox(-2.0f, 0.0f, -2.0f, 4.0f, 12.0f, 4.0f, new CubeDeformation(0.18f)), PartPose.offset(-1.9f, 12.0f, 0.0f));
        PartDefinition left_leg = partdefinition.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(16, 48).addBox(-2.0f, 0.0f, -2.0f, 4.0f, 12.0f, 4.0f, new CubeDeformation(0.18f)), PartPose.offset(1.9f, 12.0f, 0.0f));
        PartDefinition hat = partdefinition.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.offset(0.0f, 24.0f, 0.0f));
        return LayerDefinition.create(meshdefinition, 64, 64);
    }

    @Override
    public void setupAnim(LivingEntity pEntity, float pLimbSwing, float pLimbSwingAmount, float pAgeInTicks, float pNetHeadYaw, float pHeadPitch) {
        super.setupAnim(pEntity, pLimbSwing, pLimbSwingAmount, pAgeInTicks, pNetHeadYaw, pHeadPitch);
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        this.head.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        this.body.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        this.right_arm.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        this.left_arm.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        this.right_leg.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        this.left_leg.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        this.hat.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
