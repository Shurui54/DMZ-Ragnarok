package net.shurui.shuruisutilities.guilds;

import java.util.List;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The four guild claim-upgrade consumables (+1, +5, +10, +25 chunks). Registered on the mod bus from the main
 * class like every other SU item register, and slotted into the CONSUMABLES creative tab by ContentTabs. Creative
 * only for now: they carry no recipe, loot table or shop entry, so the sole source is the creative tab or a give
 * command. The chunk amount lives on each {@link ClaimUpgradeItem} instance.
 */
public final class ClaimUpgradeItems
{
    private ClaimUpgradeItems() {}

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    public static final RegistryObject<Item> CLAIM_1 = ITEMS.register("guild_claim_upgrade_1",
            () -> new ClaimUpgradeItem(1, new Item.Properties()));
    public static final RegistryObject<Item> CLAIM_5 = ITEMS.register("guild_claim_upgrade_5",
            () -> new ClaimUpgradeItem(5, new Item.Properties()));
    public static final RegistryObject<Item> CLAIM_10 = ITEMS.register("guild_claim_upgrade_10",
            () -> new ClaimUpgradeItem(10, new Item.Properties()));
    public static final RegistryObject<Item> CLAIM_25 = ITEMS.register("guild_claim_upgrade_25",
            () -> new ClaimUpgradeItem(25, new Item.Properties()));

    /** Display order for the creative tab: smallest bonus first. */
    public static final List<RegistryObject<Item>> ALL = List.of(CLAIM_1, CLAIM_5, CLAIM_10, CLAIM_25);
}
