package net.shurui.shuruisutilities.vehicleworkbench;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;

/**
 * The compact console's upgrade rule, shared by the server (which applies it) and the screen (which previews it), so
 * the preview and the result can never disagree when both sides see the same inputs and limits.
 *
 * <ul>
 *   <li><b>Merge</b>: copies of one vehicle at one tier t become a single tier t+1. Tiers 1 and 2 take two copies,
 *       tier t from 3 on takes t copies (3 of tier 3 make tier 4), so the base cost grows factorially.</li>
 *   <li><b>Kit</b>: one vehicle plus its matching upgrade kits, as many as its {@code vehicle_upgrade} recipe says
 *       for that tier (doubling by default).</li>
 * </ul>
 * Merge wins when two or more vehicles are in; a single vehicle uses kits.
 */
public final class VehicleUpgrades
{
    private VehicleUpgrades() {}

    public enum Mode { MERGE, KIT }

    /**
     * @param result  the upgraded vehicle, or empty when nothing can be upgraded (then {@code problem} says why)
     * @param vehicleSlots which vehicle slots are consumed
     * @param kits    kits consumed
     */
    public record Plan(ItemStack result, List<Integer> vehicleSlots, int kits, Mode mode, Component problem)
    {
        public boolean ok()
        {
            return !result.isEmpty();
        }

        static Plan fail(String key, Object... args)
        {
            return new Plan(ItemStack.EMPTY, List.of(), 0, null,
                    Component.translatable("gui.dmz_ragnarok.vehicle_upgrade." + key, args));
        }
    }

    public static Plan plan(List<ItemStack> vehicles, ItemStack kit, RecipeManager recipes, int maxTier,
            boolean mergeEnabled, boolean kitsEnabled)
    {
        List<Integer> filled = new ArrayList<>();
        for (int i = 0; i < vehicles.size(); i++)
            if (!vehicles.get(i).isEmpty())
                filled.add(i);
        if (filled.isEmpty())
            return Plan.fail("insert");

        ItemStack first = vehicles.get(filled.get(0));
        if (!VehicleTiers.isTiered(first))
            return Plan.fail("not_vehicle");
        int tier = VehicleTiers.tier(first);
        for (int i : filled)
        {
            ItemStack s = vehicles.get(i);
            if (s.getItem() != first.getItem() || VehicleTiers.tier(s) != tier)
                return Plan.fail("mixed");
        }
        if (tier >= maxTier)
            return Plan.fail("at_max", VehicleTiers.roman(maxTier));

        ItemStack out = VehicleTiers.withTier(first.copyWithCount(1), tier + 1);
        if (filled.size() >= 2)
        {
            if (!mergeEnabled)
                return Plan.fail("merge_off");
            int need = VehicleTiers.mergeCopies(tier);
            if (filled.size() < need)
                return Plan.fail("need_copies", need, VehicleTiers.roman(tier));
            return new Plan(out, List.copyOf(filled.subList(0, need)), 0, Mode.MERGE, Component.empty());
        }

        if (!kitsEnabled)
            return Plan.fail("need_copies", VehicleTiers.mergeCopies(tier), VehicleTiers.roman(tier));
        VehicleUpgradeRecipe rule = ruleFor(recipes, first);
        if (rule == null)
            return Plan.fail("need_copies", VehicleTiers.mergeCopies(tier), VehicleTiers.roman(tier));
        int need = rule.kitsFor(tier);
        if (kit.isEmpty() || !rule.kit().test(kit) || kit.getCount() < need)
            return Plan.fail("need_kits", need, VehicleTiers.mergeCopies(tier), VehicleTiers.roman(tier));
        return new Plan(out, List.of(filled.get(0)), need, Mode.KIT, Component.empty());
    }

    /** The kit rule that covers this vehicle, if any. */
    public static VehicleUpgradeRecipe ruleFor(RecipeManager recipes, ItemStack vehicle)
    {
        for (VehicleUpgradeRecipe r : recipes.getAllRecipesFor(VehicleWorkbenchRegistry.UPGRADE_TYPE.get()))
            if (r.vehicles().test(vehicle))
                return r;
        return null;
    }
}
