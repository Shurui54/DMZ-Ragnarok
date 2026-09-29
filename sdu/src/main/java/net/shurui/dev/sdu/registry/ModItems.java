package net.shurui.dev.sdu.registry;

import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.item.MaxProgressionItem;
import net.shurui.dev.sdu.item.ShuruisArmorItem;
import net.shurui.dev.sdu.item.StatBuffTokenItem;
import net.shurui.dev.sdu.item.TpBuffTokenItem;

import java.util.ArrayList;
import java.util.List;

/** Item registry. The old NPC editor/spawner/egg items were removed with the legacy NPC system. */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, DmzNpc.MODID);

    // "Shurui's Armor": creative-only, invincible while worn, two-UUID wearer lock (no helmet)
    public static final RegistryObject<Item> SHURUIS_ARMOR_CHESTPLATE = ITEMS.register(
            "shuruis_armor_chestplate",
            () -> new ShuruisArmorItem(ArmorItem.Type.CHESTPLATE, armorProps()));

    public static final RegistryObject<Item> SHURUIS_ARMOR_LEGGINGS = ITEMS.register(
            "shuruis_armor_leggings",
            () -> new ShuruisArmorItem(ArmorItem.Type.LEGGINGS, armorProps()));

    public static final RegistryObject<Item> SHURUIS_ARMOR_BOOTS = ITEMS.register(
            "shuruis_armor_boots",
            () -> new ShuruisArmorItem(ArmorItem.Type.BOOTS, armorProps()));

    private static Item.Properties armorProps() {
        return new Item.Properties().stacksTo(1).rarity(Rarity.EPIC);
    }

    // Duke Snipperjack's signature trophy drop (the Halloween rift boss). A cosmetic collectible using the pack's
    // blade art; the single new item added for that boss.
    public static final RegistryObject<Item> DUKES_BLADE = ITEMS.register(
            "dukes_blade",
            () -> new net.shurui.dev.sdu.item.DukesBladeItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    // Admin debug tool: right-click maxes the holder's whole DMZ progression. OP/creative gated in the item.
    public static final RegistryObject<Item> MAX_PROGRESSION = ITEMS.register(
            "max_progression",
            () -> new MaxProgressionItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    // Ordered lists so the creative tab can add all nine of each in ascending strength.
    public static final List<RegistryObject<Item>> TP_BUFF_TOKENS = new ArrayList<>();
    public static final List<RegistryObject<Item>> STAT_BUFF_TOKENS = new ArrayList<>();

    // Per-tier tints for the nine buff-token tiers (10,15,...,50), multiplied onto one shared greyscale base
    // per family so each tier reads as its own colour without authoring nine images. Both ramps run dark ->
    // bright so a higher tier reads as "more"; TP tokens are aqua, stat tokens run green -> gold.
    private static final int[] TP_TIER_TINTS = {
            0xFF154D8C, 0xFF175C9B, 0xFF196CA9, 0xFF1B7EB7, 0xFF1E90C6,
            0xFF20A5D4, 0xFF22BAE2, 0xFF24D1F1, 0xFF26E9FF};
    private static final int[] STAT_TIER_TINTS = {
            0xFF189E45, 0xFF1AAA2D, 0xFF25B61B, 0xFF48C21D, 0xFF6FCF1F,
            0xFF9BDB21, 0xFFCAE723, 0xFFF3E824, 0xFFFFC926};

    static {
        // 10%..50% in steps of 5%. Fraction = tier/100.0.
        int idx = 0;
        for (int tier = 10; tier <= 50; tier += 5, idx++) {
            final double fraction = tier / 100.0;
            final int tpTint = TP_TIER_TINTS[idx];
            final int statTint = STAT_TIER_TINTS[idx];
            TP_BUFF_TOKENS.add(ITEMS.register(
                    "tp_buff_token_" + tier,
                    () -> new TpBuffTokenItem(new Item.Properties(), fraction, tpTint)));
            STAT_BUFF_TOKENS.add(ITEMS.register(
                    "stat_buff_token_" + tier,
                    () -> new StatBuffTokenItem(new Item.Properties(), fraction, statTint)));
        }
    }

    private ModItems() {
    }
}
