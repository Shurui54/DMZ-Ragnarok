package net.shurui.shuruisutilities.katchin;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.ForgeTier;
import net.minecraftforge.common.TierSortingRegistry;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import com.dragonminez.common.init.item.tools.ToolTiers;

/**
 * The two katchin tool tiers, sitting strictly above DragonMineZ's gete tier.
 *
 * <p>Verified gete numbers (from the 2.1.3 deobf jar, {@code ToolTiers.GETE_TIER}): level 5, uses 3250, speed 12.0,
 * attack bonus 6.0, enchantability 25, harvest tag {@code dragonminez:needs_gete_tool}. The two tiers here step up
 * durability, speed and attack by a steady, non-inflated amount and keep gete's high enchantability so the whole
 * line feels like one family:
 * <ul>
 *   <li>katchin: level 6, uses 4200 (+29% over gete), speed 14.0 (+2), attack 8.0 (+2), enchantability 25.</li>
 *   <li>katchi katchin: level 7, uses 5400 (+29% over katchin), speed 16.0 (+2), attack 10.0 (+2), enchantability 25.</li>
 * </ul>
 *
 * <p>Harvest gating relies on {@link TierSortingRegistry}. A tier that is NOT registered there falls back to
 * checking only the vanilla {@code needs_*_tool} tags (see {@code TierSortingRegistry.isCorrectTierForDrops}), so
 * a custom tag like {@code needs_gete_tool} does nothing on its own. DragonMineZ ships gete UNSORTED (confirmed:
 * it never calls {@code TierSortingRegistry}), which is fine while {@code needs_gete_tool} has zero members, but the
 * moment katchin joins that tag the gate would be dead. {@link #registerSorting()} therefore folds gete into the
 * sorting registry (if DMZ has not) and places katchin above it and katchi katchin above katchin, so:
 * gete-tagged blocks need a gete tool, katchin-tagged blocks need a katchin tool, and so on.
 */
public final class KatchinTiers
{
    private KatchinTiers() {}

    public static final ResourceLocation GETE_NAME = new ResourceLocation("dragonminez", "gete");
    public static final ResourceLocation KATCHIN_NAME = new ResourceLocation(ShuruisUtilities.MODID, "katchin");
    public static final ResourceLocation KATCHI_KATCHIN_NAME = new ResourceLocation(ShuruisUtilities.MODID, "katchi_katchin");

    // Repair material: katchin tools are repaired with the katchin ore item; katchi katchin tools with any of the
    // three colour ore items (the interchangeable item tag). Suppliers stay lazy so the item registry is ready.
    public static final ForgeTier KATCHIN_TIER = new ForgeTier(
            6, 4200, 14.0F, 8.0F, 25,
            KatchinTags.NEEDS_KATCHIN_TOOL,
            () -> Ingredient.of(KatchinBlocks.KATCHIN_ORE_ITEM.get()));

    public static final ForgeTier KATCHI_KATCHIN_TIER = new ForgeTier(
            7, 5400, 16.0F, 10.0F, 25,
            KatchinTags.NEEDS_KATCHI_KATCHIN_TOOL,
            () -> Ingredient.of(KatchinTags.KATCHI_KATCHIN_ITEMS));

    /**
     * Fold gete, katchin and katchi katchin into {@link TierSortingRegistry} in strict order. Safe to call once from
     * common setup on both physical sides (registerTier is synchronised, and the final topological sort happens later).
     * Guards make it idempotent and tolerant of a future DMZ that sorts gete itself.
     */
    public static void registerSorting()
    {
        // 1) Ensure gete is sorted so its needs_gete_tool tag actually gates. If a future DMZ already sorted it (under
        //    any name), leave it be.
        if (!TierSortingRegistry.isTierSorted(ToolTiers.GETE_TIER) && TierSortingRegistry.byName(GETE_NAME) == null)
        {
            TierSortingRegistry.registerTier(ToolTiers.GETE_TIER, GETE_NAME, List.of(Tiers.NETHERITE), List.of());
            LoggingHandler.sulog.info("[katchin] Registered DragonMineZ gete tier into the tier sorting registry.");
        }

        // 2) katchin: above netherite and, whenever gete is sorted, above gete too. Passing the gete Tier OBJECT makes
        //    the edge resolve to whatever name gete was actually registered under, so the gate holds regardless.
        if (TierSortingRegistry.byName(KATCHIN_NAME) == null)
        {
            List<Object> after = new ArrayList<>();
            after.add(Tiers.NETHERITE);
            if (TierSortingRegistry.isTierSorted(ToolTiers.GETE_TIER))
            {
                after.add(ToolTiers.GETE_TIER);
            }
            TierSortingRegistry.registerTier(KATCHIN_TIER, KATCHIN_NAME, after, List.of(KATCHI_KATCHIN_NAME));
        }

        // 3) katchi katchin: strictly above katchin.
        if (TierSortingRegistry.byName(KATCHI_KATCHIN_NAME) == null)
        {
            TierSortingRegistry.registerTier(KATCHI_KATCHIN_TIER, KATCHI_KATCHIN_NAME, List.of(KATCHIN_TIER), List.of());
        }
    }
}
