package net.shurui.dev.shuruis_dmz_dungeons.registry;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.item.CrateBlockItem;
import net.shurui.dev.shuruis_dmz_dungeons.item.FloorTicketItem;
import net.shurui.dev.shuruis_dmz_dungeons.item.TpGemItem;

import java.util.List;

public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Shuruis_dmz_dungeons.MODID);

    public static final RegistryObject<Item> ADVANCED_SPAWNER = ITEMS.register("advanced_spawner",
            () -> new BlockItem(ModBlocks.ADVANCED_SPAWNER.get(), new Item.Properties()));

    // block items for the two crate blocks. They place at tier 0 (common, grey) unless the stack carries a
    // CrateVariant; the generation pass stamps higher tiers. CrateBlockItem, not BlockItem, so the inventory icon
    // is the GeckoLib crate the stack will place, telling the twelve rarity/metal and seven colour variants apart.
    public static final RegistryObject<Item> CRATE_CHEST = ITEMS.register("crate_chest",
            () -> new CrateBlockItem(ModBlocks.CRATE_CHEST.get(), new Item.Properties()));
    public static final RegistryObject<Item> CRATE_BARREL = ITEMS.register("crate_barrel",
            () -> new CrateBlockItem(ModBlocks.CRATE_BARREL.get(), new Item.Properties()));

    // floor-unlock ticket: one item whose target floor is set on its stack NBT (see FloorTicketItem). right-click
    // warps the holder straight to that floor, bypassing the boss gate. modest stack so a set of them reads cleanly.
    public static final RegistryObject<Item> FLOOR_TICKET = ITEMS.register("floor_ticket",
            () -> new FloorTicketItem(new Item.Properties().stacksTo(16)));

    // TP gems: consumables granting Training Points on right-click, ascending value. The four largest have no
    // texture of their own, so they reuse the largest placeholder with a permanent glint (foil = true) to read
    // as the "beyond max" tier.
    public static final RegistryObject<Item> TP_GEM_1000 = registerTpGem(1000, false);
    public static final RegistryObject<Item> TP_GEM_5000 = registerTpGem(5000, false);
    public static final RegistryObject<Item> TP_GEM_10000 = registerTpGem(10000, false);
    public static final RegistryObject<Item> TP_GEM_50000 = registerTpGem(50000, false);
    public static final RegistryObject<Item> TP_GEM_100000 = registerTpGem(100000, false);
    public static final RegistryObject<Item> TP_GEM_250000 = registerTpGem(250000, false);
    public static final RegistryObject<Item> TP_GEM_500000 = registerTpGem(500000, false);
    public static final RegistryObject<Item> TP_GEM_1000000 = registerTpGem(1000000, false);
    public static final RegistryObject<Item> TP_GEM_2500000 = registerTpGem(2500000, false);
    public static final RegistryObject<Item> TP_GEM_5000000 = registerTpGem(5000000, false);
    public static final RegistryObject<Item> TP_GEM_10000000 = registerTpGem(10000000, true);
    public static final RegistryObject<Item> TP_GEM_25000000 = registerTpGem(25000000, true);
    public static final RegistryObject<Item> TP_GEM_50000000 = registerTpGem(50000000, true);
    public static final RegistryObject<Item> TP_GEM_100000000 = registerTpGem(100000000, true);

    // all gems in one list so the creative tab builds them in one place
    public static final List<RegistryObject<Item>> TP_GEMS = List.of(
            TP_GEM_1000, TP_GEM_5000, TP_GEM_10000, TP_GEM_50000, TP_GEM_100000, TP_GEM_250000,
            TP_GEM_500000, TP_GEM_1000000, TP_GEM_2500000, TP_GEM_5000000, TP_GEM_10000000,
            TP_GEM_25000000, TP_GEM_50000000, TP_GEM_100000000);

    private static RegistryObject<Item> registerTpGem(int amount, boolean foil) {
        return ITEMS.register("tp_gem_" + amount,
                () -> new TpGemItem(amount, foil, new Item.Properties().stacksTo(64)));
    }

    private ModItems() {
    }
}
