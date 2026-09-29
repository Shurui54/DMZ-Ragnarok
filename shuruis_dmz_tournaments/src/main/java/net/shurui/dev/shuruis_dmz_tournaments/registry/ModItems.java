package net.shurui.dev.shuruis_dmz_tournaments.registry;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.dev.shuruis_dmz_tournaments.Shuruis_dmz_tournaments;
import net.shurui.dev.shuruis_dmz_tournaments.item.StatGemItem;

import java.util.List;

public final class ModItems {
    private ModItems() {}

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Shuruis_dmz_tournaments.MODID);

    /** The stat-gem amounts, ordered lowest → highest for the creative tab. */
    public static final int[] GEM_AMOUNTS = {10, 25, 50, 100, 250, 500};

    /** All stat gems in {@link #GEM_AMOUNTS} order. */
    public static final List<RegistryObject<Item>> STAT_GEMS = List.of(
            statGem(10), statGem(25), statGem(50), statGem(100), statGem(250), statGem(500));

    private static RegistryObject<Item> statGem(int amount) {
        return ITEMS.register("stat_gem_" + amount,
                () -> new StatGemItem(amount, new Item.Properties().stacksTo(64)));
    }
}
