package net.shurui.shuruisutilities.swordsman;

import java.util.Locale;
import java.util.Set;

import com.dragonminez.common.combat.logic.player.PlayerAttackHelper;
import com.dragonminez.common.combat.logic.weapon.WeaponRegistry;
import com.dragonminez.common.combat.weapon.WeaponAttributes;
import com.dragonminez.common.passives.ClassPassives;
import com.dragonminez.common.stats.StatsData;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.dev.sdu.passive.SduClassPassives;

/**
 * The Konatsian swordsman class: its id, the passive value keys it reads, and the one answer everything else asks,
 * "is this player wielding a sword right now".
 *
 * <p>The class itself is just an entry in the tapion race's {@code stats.json} (DMZ lists a race's classes straight
 * from that file, so no other race offers it). DMZ only runs passives it has a handler for, which is why
 * {@link #init} registers {@link SwordsmanPassive}.
 */
public final class Swordsman
{
    private Swordsman() {}

    public static final String CLASS_ID = "swordsman";

    /** Extra melee damage while wielding a sword, as a fraction (0.15 = +15%). */
    public static final String DAMAGE_KEY = "swordDamageBonus";
    /** Extra attack speed while wielding a sword, as a fraction (0.2 = +20%). */
    public static final String SPEED_KEY = "swordAttackSpeedBonus";

    /** The common modded sword tag (Forge 47 ships no constant for it). */
    private static final TagKey<Item> FORGE_SWORDS =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("forge", "tools/swords"));

    /** A sane ceiling so a mistyped 15 (meant as 15%) cannot make a player hit 16 times as hard. */
    private static final double MAX_BONUS = 2.0;

    /** Register the passive with DMZ (and keep SDU's generic custom-class handler from replacing it). Both sides. */
    public static void init()
    {
        SduClassPassives.registerOwned(new SwordsmanPassive(), DAMAGE_KEY, SPEED_KEY);
    }

    /** True when {@code data} is a created character whose class runs the swordsman passive (and it is enabled). */
    public static boolean isSwordsman(StatsData data)
    {
        return data != null && data.getStatus().isHasCreatedCharacter()
                && ClassPassives.get(data) instanceof SwordsmanPassive;
    }

    /** A configured bonus, clamped to [0, MAX_BONUS]. 0 when the class's config does not set it. */
    public static double bonus(StatsData data, String key)
    {
        double v = ClassPassives.value(data, key, 0.0);
        if (!Double.isFinite(v) || v <= 0.0)
            return 0.0;
        return Math.min(v, MAX_BONUS);
    }

    /** The main hand holds a sword or a sword-type ki weapon is drawn. */
    public static boolean wieldingSword(Player player)
    {
        return player != null && (isSword(player.getMainHandItem()) || kiSwordDrawn(player));
    }

    /**
     * Whether a held item is a sword. Explicit config lists win; otherwise DMZ's own weapon category decides (that is
     * what DMZ's combat uses to pick a moveset, and it already maps vanilla and modded swords through its fallback);
     * an item DMZ has no category for falls back to "is a SwordItem or tagged forge:tools/swords".
     */
    public static boolean isSword(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
            return false;
        Item item = stack.getItem();
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (matches(SwordsmanConfig.excludedItems, stack, id))
            return false;
        if (matches(SwordsmanConfig.extraSwordItems, stack, id))
            return true;
        String category = null;
        try
        {
            WeaponAttributes attributes = WeaponRegistry.getAttributes(stack);
            category = attributes == null ? null : attributes.category();
        }
        catch (Throwable ignored)
        {
            // registry not loaded yet: fall through to the item checks
        }
        if (category != null && !category.isBlank())
            return SwordsmanConfig.swordCategories.contains(category.toLowerCase(Locale.ROOT));
        return item instanceof SwordItem || stack.is(FORGE_SWORDS);
    }

    /** A drawn DMZ ki weapon whose type is listed as a sword (DMZ's "blade" by default). */
    public static boolean kiSwordDrawn(Player player)
    {
        try
        {
            if (!PlayerAttackHelper.isKiWeaponActive(player))
                return false;
            StatsData data = player.getCapability(com.dragonminez.common.stats.StatsCapability.INSTANCE).resolve()
                    .orElse(null);
            String type = data == null ? null : data.getStatus().getKiWeaponType();
            return type != null && SwordsmanConfig.kiWeaponTypes.contains(type.toLowerCase(Locale.ROOT));
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static boolean matches(Set<String> entries, ItemStack stack, ResourceLocation id)
    {
        if (entries.isEmpty())
            return false;
        if (id != null && entries.contains(id.toString()))
            return true;
        for (String e : entries)
        {
            if (!e.startsWith("#"))
                continue;
            ResourceLocation tag = ResourceLocation.tryParse(e.substring(1));
            if (tag != null && stack.is(TagKey.create(Registries.ITEM, tag)))
                return true;
        }
        return false;
    }
}
