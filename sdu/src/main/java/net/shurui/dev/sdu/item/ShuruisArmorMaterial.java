package net.shurui.dev.sdu.item;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.EnumMap;

/**
 * Armor material for Shurui's Armor. The wearer is invincible while worn (see
 * {@link ShuruisArmorHandler}), so the protection/durability values are mostly cosmetic.
 */
public enum ShuruisArmorMaterial implements ArmorMaterial {

    SHURUIS_ARMOR;

    // Vanilla/DMZ convention: the material name is a plain [a-z0-9_] identifier with NO colon.
    // HumanoidArmorLayer formats this straight into a texture path/ResourceLocation; a colon here
    // yields a malformed ResourceLocation and a broken/missing armor render. The real worn texture
    // is supplied by ShuruisArmorItem#getArmorTexture, so this only acts as a fallback/cache key.
    private static final String NAME = "sdu_shuruis_armor";

    /** Full diamond-tier-and-beyond protection per slot. No helmet exists, but keep HEAD sane anyway. */
    private static final EnumMap<ArmorItem.Type, Integer> PROTECTION = new EnumMap<>(ArmorItem.Type.class);

    static {
        PROTECTION.put(ArmorItem.Type.BOOTS, 5);
        PROTECTION.put(ArmorItem.Type.LEGGINGS, 8);
        PROTECTION.put(ArmorItem.Type.CHESTPLATE, 10);
        PROTECTION.put(ArmorItem.Type.HELMET, 5);
    }

    @Override
    public int getDurabilityForType(ArmorItem.Type type) {
        int base = switch (type) {
            case BOOTS -> 13;
            case LEGGINGS -> 15;
            case CHESTPLATE -> 16;
            case HELMET -> 11;
        };
        return base * 40;
    }

    @Override
    public int getDefenseForType(ArmorItem.Type type) {
        return PROTECTION.get(type);
    }

    @Override
    public int getEnchantmentValue() {
        return 15;
    }

    @Override
    public SoundEvent getEquipSound() {
        return SoundEvents.ARMOR_EQUIP_NETHERITE;
    }

    @Override
    public Ingredient getRepairIngredient() {
        // Creative-only, no repair recipe intended.
        return Ingredient.EMPTY;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public float getToughness() {
        return 3.0F;
    }

    @Override
    public float getKnockbackResistance() {
        return 0.1F;
    }
}
