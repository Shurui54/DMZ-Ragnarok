package net.shurui.dev.shuruis_raid_bosses.registry;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.dev.shuruis_raid_bosses.Shuruis_raid_bosses;
import net.shurui.dev.shuruis_raid_bosses.item.RaidSoulItem;
import net.shurui.dev.shuruis_raid_bosses.item.ZSoulItem;
import net.shurui.dev.shuruis_raid_bosses.item.ZSoulTier;
import net.shurui.dev.shuruis_raid_bosses.item.ZStat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link RaidSoulItem} and the Z-Soul (Potara) charms: three tiers each of six dedicated stat souls
 * plus the rainbow soul (7 x 3 = 21), all worn in the {@link #ZSOUL_SLOT} Curios slot.
 */
public final class ModItems {
    private ModItems() {}

    /** Curios slot identifier Z-Souls are worn in (see {@code data/.../curios/slots/z_souls.json}). */
    public static final String ZSOUL_SLOT = "z_souls";

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Shuruis_raid_bosses.MODID);

    public static final RegistryObject<Item> RAID_SOUL = ITEMS.register(
            "raid_soul", () -> new RaidSoulItem(new Item.Properties().stacksTo(16)));

    /** Dedicated stat souls, keyed by stat then tier. */
    public static final Map<ZStat, Map<ZSoulTier, RegistryObject<Item>>> STAT_SOULS = new EnumMap<>(ZStat.class);
    /** Rainbow souls (all stats), keyed by tier. */
    public static final Map<ZSoulTier, RegistryObject<Item>> RAINBOW_SOULS = new EnumMap<>(ZSoulTier.class);
    /** Every Z-Soul, in a stable order, for the creative tab. */
    public static final List<RegistryObject<Item>> ALL_ZSOULS = new ArrayList<>();

    static {
        for (ZStat stat : ZStat.values()) {
            if (!stat.hasDedicatedSoul()) continue;
            Map<ZSoulTier, RegistryObject<Item>> byTier = new EnumMap<>(ZSoulTier.class);
            for (ZSoulTier tier : ZSoulTier.values()) {
                RegistryObject<Item> ro = ITEMS.register("z_soul_" + stat.itemName + "_" + tier.id,
                        () -> new ZSoulItem(new Item.Properties().stacksTo(1), stat, tier));
                byTier.put(tier, ro);
                ALL_ZSOULS.add(ro);
            }
            STAT_SOULS.put(stat, byTier);
        }
        for (ZSoulTier tier : ZSoulTier.values()) {
            RegistryObject<Item> ro = ITEMS.register("z_soul_rainbow_" + tier.id,
                    () -> new ZSoulItem(new Item.Properties().stacksTo(1), null, tier));
            RAINBOW_SOULS.put(tier, ro);
            ALL_ZSOULS.add(ro);
        }
    }
}
