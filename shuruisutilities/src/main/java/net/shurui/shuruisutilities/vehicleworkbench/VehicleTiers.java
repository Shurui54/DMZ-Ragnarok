package net.shurui.shuruisutilities.vehicleworkbench;

import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Vehicle tiers: where a tier lives, what it is worth, and how much the next one costs.
 *
 * <p>An item's tier is an int in its NBT ({@value #TAG}); no tag means tier 1, so every vehicle that already exists
 * is a valid tier 1. Which items can carry a tier is the {@code dmz_ragnarok:tiered_vehicles} item tag.
 *
 * <p>A spawned DMZ vehicle keeps its tier in its persistent data and carries its multipliers as attribute modifiers on
 * MOVEMENT_SPEED (which DMZ's vehicles register but never read): attributes save with the entity and sync to the
 * driving client, which is where DMZ simulates a ridden vehicle, so the tier mixin can read them on either side.
 */
public final class VehicleTiers
{
    private VehicleTiers() {}

    public static final String TAG = "RagnarokTier";

    public static final TagKey<Item> TIERED = ItemTags.create(new ResourceLocation(ShuruisUtilities.MODID, "tiered_vehicles"));
    public static final TagKey<Item> UPGRADE_KITS = ItemTags.create(new ResourceLocation(ShuruisUtilities.MODID, "vehicle_upgrades"));

    // Fixed ids so each modifier is found again, replaced rather than stacked, and read back by the mixin.
    public static final UUID SPEED_ID = UUID.fromString("6f1c2b9e-4b6e-4c1e-9d0e-7a5e1c3b2a01");
    public static final UUID ACCEL_ID = UUID.fromString("6f1c2b9e-4b6e-4c1e-9d0e-7a5e1c3b2a02");
    public static final UUID CLIMB_ID = UUID.fromString("6f1c2b9e-4b6e-4c1e-9d0e-7a5e1c3b2a03");

    public static boolean isTiered(ItemStack stack)
    {
        return !stack.isEmpty() && stack.is(TIERED);
    }

    public static int tier(ItemStack stack)
    {
        CompoundTag tag = stack.getTag();
        return tag == null ? 1 : Math.max(1, tag.getInt(TAG));
    }

    /** Stamp a tier on a stack; tier 1 removes the tag so a tier 1 stack is identical to an untouched one. */
    public static ItemStack withTier(ItemStack stack, int tier)
    {
        if (tier <= 1)
        {
            if (stack.getTag() != null)
            {
                stack.getTag().remove(TAG);
                if (stack.getTag().isEmpty())
                    stack.setTag(null);
            }
        }
        else
            stack.getOrCreateTag().putInt(TAG, tier);
        return stack;
    }

    public static int maxTier()
    {
        return Math.max(1, Math.min(VehicleTierConfig.maxTier, VehicleTierConfig.TIER_CEILING));
    }

    /** Copies of a tier t vehicle merged into one tier t+1: two for tiers 1 and 2, then t (3 of tier 3, ...). */
    public static int mergeCopies(int tier)
    {
        return Math.max(2, tier);
    }

    public static double speedMult(int tier)
    {
        return 1.0D + VehicleTierConfig.speedPerTier * (Math.max(1, tier) - 1);
    }

    public static double accelMult(int tier)
    {
        return 1.0D + VehicleTierConfig.accelerationPerTier * (Math.max(1, tier) - 1);
    }

    public static double climbMult(int tier)
    {
        return 1.0D + VehicleTierConfig.climbPerTier * (Math.max(1, tier) - 1);
    }

    public static double durabilityMult(int tier)
    {
        return 1.0D + VehicleTierConfig.durabilityPerTier * (Math.max(1, tier) - 1);
    }

    public static String roman(int n)
    {
        return switch (n)
        {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            default -> Integer.toString(n);
        };
    }

    /** "Tier III", gold. */
    public static MutableComponent label(int tier)
    {
        return Component.translatable("tooltip.dmz_ragnarok.vehicle_tier", roman(tier)).withStyle(ChatFormatting.GOLD);
    }

    // ---------------------------------------------------------------- DMZ vehicle entities

    /** The tier a spawned DMZ vehicle carries (1 when it never had one). */
    public static int entityTier(LivingEntity vehicle)
    {
        return Math.max(1, vehicle.getPersistentData().getInt(TAG));
    }

    /** Record a tier on a DMZ vehicle and (re)apply its multipliers from the current config. Server side. */
    public static void applyToEntity(LivingEntity vehicle, int tier)
    {
        tier = Math.max(1, Math.min(tier, VehicleTierConfig.TIER_CEILING));
        if (tier <= 1)
            vehicle.getPersistentData().remove(TAG);
        else
            vehicle.getPersistentData().putInt(TAG, tier);
        AttributeInstance attr = vehicle.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr == null)
            return;
        set(attr, SPEED_ID, "ragnarok tier speed", speedMult(tier) - 1.0D);
        set(attr, ACCEL_ID, "ragnarok tier acceleration", accelMult(tier) - 1.0D);
        set(attr, CLIMB_ID, "ragnarok tier climb", climbMult(tier) - 1.0D);
    }

    private static void set(AttributeInstance attr, UUID id, String name, double amount)
    {
        attr.removeModifier(id);
        if (amount != 0.0D)
            attr.addPermanentModifier(new AttributeModifier(id, name, amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    /** The multiplier one of our modifiers encodes (1 without it). Read by the DMZ vehicle mixin on either side. */
    public static double factor(LivingEntity vehicle, UUID id)
    {
        AttributeInstance attr = vehicle.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr == null)
            return 1.0D;
        AttributeModifier mod = attr.getModifier(id);
        return mod == null ? 1.0D : 1.0D + mod.getAmount();
    }
}
