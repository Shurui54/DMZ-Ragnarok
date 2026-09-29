package net.shurui.shuruisutilities.saiyan;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The pool of DragonMineZ saiyan armor SETS a planet saiyan can wear, and the one place that equips a whole set on a mob.
 * Every set is a NO-HELMET set of exactly three DragonMineZ items ({@code _chestplate}, {@code _leggings}, {@code
 * _boots}); all ten were verified present in the DragonMineZ 2.1.3 jar (each resolves and each ships both armor-layer
 * textures). A single NPC always wears ONE whole set (never a mix), rolled once at spawn, so the town reads as a real
 * saiyan army in assorted regalia rather than a wall of identical Vegeta armor.
 *
 * <p>Every id is resolved by {@link ResourceLocation} against the live registry and never compiled against, exactly as
 * the rest of the space code touches DragonMineZ ids: DragonMineZ being present is not proof a given item id matches this
 * build, so a missing/renamed piece is simply skipped (the saiyan wears fewer pieces) and never crashes the spawn. The
 * chestplate is the piece the client actually renders (see the armor render layer), so a set's identity is carried by its
 * chestplate; the leggings and boots ride along for drops/consistency.
 */
public final class SaiyanArmorSets
{
    private SaiyanArmorSets()
    {
    }

    // the ten verified no-helmet saiyan-style sets, each as {chestplate, leggings, boots} DragonMineZ item ids. Keep a
    // whole set together on one NPC; never mix pieces across sets.
    private static final String[][] SETS =
            {
                    {"vegeta_saiyan_armor_chestplate", "vegeta_saiyan_armor_leggings", "vegeta_saiyan_armor_boots"},
                    {"vegeta_namek_armor_chestplate", "vegeta_namek_armor_leggings", "vegeta_namek_armor_boots"},
                    {"king_vegeta_armor_chestplate", "king_vegeta_armor_leggings", "king_vegeta_armor_boots"},
                    {"raditz_armor_chestplate", "raditz_armor_leggings", "raditz_armor_boots"},
                    {"turles_armor_chestplate", "turles_armor_leggings", "turles_armor_boots"},
                    {"bardock_dbz_armor_chestplate", "bardock_dbz_armor_leggings", "bardock_dbz_armor_boots"},
                    {"bardock_super_armor_chestplate", "bardock_super_armor_leggings", "bardock_super_armor_boots"},
                    {"cooler_soldier_armor_chestplate", "cooler_soldier_armor_leggings", "cooler_soldier_armor_boots"},
                    {"capsule_corp_armor_chestplate", "capsule_corp_armor_leggings", "capsule_corp_armor_boots"},
                    {"fighter_armor_chestplate", "fighter_armor_leggings", "fighter_armor_boots"},
            };

    // chestplate -> CHEST, leggings -> LEGS, boots -> FEET, in the same order as each set row above.
    private static final EquipmentSlot[] SLOTS =
            {EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /**
     * Gear a saiyan mob: if it is a named NPC with a pinned armor set (e.g. Fenris wears Gine's armor), equip exactly that
     * set; otherwise roll a random set from the general pool. This is the single entry point every spawn path uses, so the
     * named pins live only in {@link SaiyanAppearance.NamedSaiyan} and never leak into the spawn code.
     */
    public static void equip(Mob mob, RandomSource random)
    {
        if (mob == null || random == null)
        {
            return;
        }
        if (mob instanceof SaiyanAppearance appearance && appearance.getNamed() != null
                && appearance.getNamed().armorSetPrefix() != null)
        {
            equipSet(mob, appearance.getNamed().armorSetPrefix());
            return;
        }
        equipRandomSet(mob, random);
    }

    /**
     * Equip one randomly chosen whole set on the mob, each piece with a zero drop chance so a kill never litters the
     * planet with saiyan gear. A missing/renamed DragonMineZ piece is skipped rather than crashing the spawn.
     */
    public static void equipRandomSet(Mob mob, RandomSource random)
    {
        if (mob == null || random == null)
        {
            return;
        }
        equipPieces(mob, SETS[random.nextInt(SETS.length)]);
    }

    // equip a specific set by its DragonMineZ item-id prefix (e.g. "gine_armor"), expanding it to the three no-helmet
    // pieces. Used to pin a named NPC's set.
    private static void equipSet(Mob mob, String prefix)
    {
        equipPieces(mob, new String[] {prefix + "_chestplate", prefix + "_leggings", prefix + "_boots"});
    }

    // resolve and equip each piece of a set with a zero drop chance; a missing/renamed piece is skipped, never fatal.
    private static void equipPieces(Mob mob, String[] set)
    {
        for (int i = 0; i < set.length && i < SLOTS.length; i++)
        {
            try
            {
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dragonminez", set[i]));
                if (item == null)
                {
                    continue;
                }
                mob.setItemSlot(SLOTS[i], new ItemStack(item));
                mob.setDropChance(SLOTS[i], 0.0F);
            }
            catch (Throwable ignored)
            {
                // a single missing armor piece just means the saiyan wears fewer pieces; never fatal.
            }
        }
    }
}
