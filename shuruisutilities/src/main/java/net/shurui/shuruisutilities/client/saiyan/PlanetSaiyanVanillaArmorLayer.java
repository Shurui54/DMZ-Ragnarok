package net.shurui.shuruisutilities.client.saiyan;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.ItemArmorGeoLayer;

import net.shurui.shuruisutilities.saiyan.SaiyanAppearance;

/**
 * Mob-safe reimplementation of DragonMineZ's {@code DMZPlayerArmorLayer} for a planet saiyan. DMZ registers TWO armor
 * layers on its player: this vanilla-{@code HumanoidModel} one, which maps each armor bone (head/body/arms/leggings/boots)
 * to a vanilla model part and draws the equipped piece there, AND the inflated-body {@code DMZCustomArmorLayer}
 * reproduced by {@link PlanetSaiyanArmorLayer}. A normal male saiyan gets the whole set from THIS layer (chest torso,
 * both arms, leggings, both boots); the inflated layer stays dark for him. A female saiyan's chest TORSO comes from the
 * inflated layer instead (so the armor bulges over the female chest bone), while her arms, leggings and boots still come
 * from THIS layer. So the chest is drawn exactly once for either gender.
 *
 * <p>DMZ's own layer is hard-typed to {@code AbstractClientPlayer} and reads the player-only inventory
 * ({@code getInventory().armor}); this reads {@link Mob#getItemBySlot}, which works for any mob. Every armor piece is a
 * {@code DbzArmorItem}, whose Forge client extension supplies DMZ's own armor mesh ({@code ArmorBaseModel}) and whose
 * {@code getArmorTexture} routes the texture through DMZ's {@code ArmorTextureResolver} via {@code
 * ForgeHooksClient.getArmorTexture}. Because {@code DbzArmorItem}'s model is a plain {@code HumanoidModel} and not a
 * {@code GeoArmorRenderer}, {@code ItemArmorGeoLayer} takes its vanilla-armor-piece branch, so no texture-path override
 * is needed here: the same mechanism DMZ uses to paint DBZ armor on a player paints it on these NPCs.
 *
 * <p>This class is under {@code client/} and must never be referenced from common code.
 */
public class PlanetSaiyanVanillaArmorLayer<T extends Mob & GeoEntity & SaiyanAppearance> extends ItemArmorGeoLayer<T>
{
    public PlanetSaiyanVanillaArmorLayer(GeoRenderer<T> renderer)
    {
        super(renderer);
    }

    /**
     * The equipped piece for a given armor bone, or null if the bone is not an armor bone or the slot is empty. Read from
     * {@link Mob#getItemBySlot} so a mob works (DMZ reads the player-only inventory here). The chest TORSO bone
     * ({@code armorBody}) returns null for a female so it is not drawn twice: DMZ hands the female chest torso to the
     * inflated {@link PlanetSaiyanArmorLayer} (its {@code isFemaleHumanOrSaiyan} case). The arms map to the CHEST slot too
     * but are never suppressed, matching DMZ, so a female still gets her arm armor from this layer.
     */
    @Override
    @Nullable
    protected ItemStack getArmorItemForBone(GeoBone bone, T animatable)
    {
        String boneName = bone.getName();
        EquipmentSlot slot = slotForBone(boneName);
        if (slot == null)
        {
            return null;
        }
        ItemStack stack = animatable.getItemBySlot(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem))
        {
            return null;
        }
        // Mirror DMZPlayerArmorLayer's per-bone suppression: the chest torso bone is not drawn by this vanilla-model layer
        // for a body whose chest the inflated custom layer owns. For these plain saiyan NPCs that body is a female one
        // (DMZ's isFemaleHumanOrSaiyan branch; the other DMZ cases -- oozaru, buffed, majin, custom models -- never apply
        // to a stock saiyan, and the "armored" cosmetic flag needs a StatsCapability a mob never has, so it reads false).
        // Returning null here is the whole reason the chest cannot draw twice.
        if (("armorBody".equals(boneName) || "armor_body".equals(boneName)) && !animatable.isMale())
        {
            return null;
        }
        return stack;
    }

    // bone name -> equipment slot, mirroring DMZPlayerArmorLayer exactly (both camelCase geo names and the snake_case
    // variants DMZ also accepts). Any non-armor bone yields null so the layer ignores it.
    @Nullable
    private static EquipmentSlot slotForBone(String boneName)
    {
        return switch (boneName)
        {
            case "armorHead", "armor_head" -> EquipmentSlot.HEAD;
            case "armorBody", "armor_body", "armorRightArm", "armor_right_arm", "armorLeftArm", "armor_left_arm" ->
                    EquipmentSlot.CHEST;
            case "armorLeggingsBody", "armor_leggings_body", "armorRightLeg", "armor_right_leg", "armorLeftLeg",
                 "armor_left_leg" -> EquipmentSlot.LEGS;
            case "armorRightBoot", "armor_right_boot", "armorLeftBoot", "armor_left_boot" -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    // which vanilla slot's model to use for a bone. Identical mapping to DMZPlayerArmorLayer.
    @Override
    @NotNull
    protected EquipmentSlot getEquipmentSlotForBone(GeoBone bone, ItemStack stack, T animatable)
    {
        EquipmentSlot slot = slotForBone(bone.getName());
        return slot != null ? slot : super.getEquipmentSlotForBone(bone, stack, animatable);
    }

    // which HumanoidModel part carries a bone's armor mesh. Identical mapping to DMZPlayerArmorLayer: armorBody and
    // armorLeggingsBody both ride the body part; the leg/boot pairs share their side's leg part.
    @Override
    @NotNull
    protected ModelPart getModelPartForBone(GeoBone bone, EquipmentSlot slot, ItemStack stack, T animatable,
                                            HumanoidModel<?> baseModel)
    {
        return switch (bone.getName())
        {
            case "armorHead", "armor_head" -> baseModel.head;
            case "armorBody", "armor_body", "armorLeggingsBody", "armor_leggings_body" -> baseModel.body;
            case "armorRightArm", "armor_right_arm" -> baseModel.rightArm;
            case "armorLeftArm", "armor_left_arm" -> baseModel.leftArm;
            case "armorRightLeg", "armor_right_leg", "armorRightBoot", "armor_right_boot" -> baseModel.rightLeg;
            case "armorLeftLeg", "armor_left_leg", "armorLeftBoot", "armor_left_boot" -> baseModel.leftLeg;
            default -> super.getModelPartForBone(bone, slot, stack, animatable, baseModel);
        };
    }
}
