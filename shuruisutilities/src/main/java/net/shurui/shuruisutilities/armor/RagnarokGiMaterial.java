package net.shurui.shuruisutilities.armor;

import java.util.EnumMap;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Armor material for the Ragnarok Gi. Ordinary wearable gear, not an admin set: it protects roughly like iron, a
 * little better in the chest, so it is worth wearing without displacing anything players work toward.
 *
 * <p>The name is a plain {@code [a-z0-9_]} identifier with NO colon, matching the vanilla and DMZ convention.
 * {@code HumanoidArmorLayer} formats this straight into a resource location, and a colon here yields a malformed
 * one and a broken render. It is only ever a fallback and cache key in practice, because the real worn look comes
 * from the GeckoLib model in {@code RagnarokGiRenderer}.</p>
 */
public enum RagnarokGiMaterial implements ArmorMaterial
{
    RAGNAROK_GI;

    private static final String NAME = "ragnarok_gi";

    /** No helmet piece exists, but HEAD is filled in anyway so a lookup can never return null. */
    private static final EnumMap<ArmorItem.Type, Integer> PROTECTION = new EnumMap<>(ArmorItem.Type.class);

    static
    {
        PROTECTION.put(ArmorItem.Type.BOOTS, 2);
        PROTECTION.put(ArmorItem.Type.LEGGINGS, 5);
        PROTECTION.put(ArmorItem.Type.CHESTPLATE, 7);
        PROTECTION.put(ArmorItem.Type.HELMET, 2);
    }

    @Override
    public int getDurabilityForType(ArmorItem.Type type)
    {
        int base = switch (type)
        {
            case BOOTS -> 13;
            case LEGGINGS -> 15;
            case CHESTPLATE -> 16;
            case HELMET -> 11;
        };
        return base * 22;
    }

    @Override
    public int getDefenseForType(ArmorItem.Type type)
    {
        return PROTECTION.get(type);
    }

    @Override
    public int getEnchantmentValue()
    {
        return 12;
    }

    @Override
    public SoundEvent getEquipSound()
    {
        return SoundEvents.ARMOR_EQUIP_LEATHER;
    }

    @Override
    public Ingredient getRepairIngredient()
    {
        return Ingredient.of(Items.LEATHER);
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public float getToughness()
    {
        return 0.0F;
    }

    @Override
    public float getKnockbackResistance()
    {
        return 0.0F;
    }
}
