package net.shurui.shuruisutilities.katchin;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The katchin and katchi katchin tool items plus the two smithing templates.
 *
 * <p>Ten tools: a pickaxe, axe, shovel, hoe and hammer at each tier. There is NO sword at any tier, matching gete.
 * The per-tool damage/speed deltas mirror DragonMineZ's gete pattern (pickaxe +1 / -2.8, axe +5.0 / -3.0, shovel
 * +1.5 / -3.0, hoe -4 / 0.0) so the tools feel like the same family; the hammer is a heavier pickaxe (+2 / -3.0).
 * All tools are {@code fireResistant} because they sit above netherite in the tier order.
 *
 * <p>The katchi katchin family is ONE item per tool type: the colour (blue_grey / orange / cream) lives on the
 * stack's NBT ({@link KatchiKatchinColour}) and is chosen at random when the tool is smithed. The model
 * {@code overrides} render the right texture from the client-side {@code shuruisutilities:colour} property.
 *
 * <p>Smithing: gete pickaxe/axe/shovel/hoe upgrade into their katchin equivalents with
 * {@code katchin_upgrade_smithing_template}; katchin pickaxe/axe/shovel/hoe/hammer upgrade into katchi katchin with
 * {@code katchi_katchin_upgrade_smithing_template} (random colour). The katchin HAMMER has no gete predecessor
 * (gete ships no hammer), so it is the one tool crafted from raw material rather than smithed. The recipe JSON is
 * owned by the asset/datapack side; these are just the item registrations.
 */
public final class KatchinItems
{
    private KatchinItems() {}

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    // Ordered for the creative tab.
    public static final List<RegistryObject<Item>> TOOLS = new ArrayList<>();
    public static final List<RegistryObject<Item>> TEMPLATES = new ArrayList<>();

    private static Item.Properties toolProps()
    {
        return new Item.Properties().fireResistant();
    }

    public static final RegistryObject<Item> KATCHIN_PICKAXE = tool("katchin_pickaxe",
            () -> new net.minecraft.world.item.PickaxeItem(KatchinTiers.KATCHIN_TIER, 1, -2.8F, toolProps()));
    public static final RegistryObject<Item> KATCHIN_AXE = tool("katchin_axe",
            () -> new net.minecraft.world.item.AxeItem(KatchinTiers.KATCHIN_TIER, 5.0F, -3.0F, toolProps()));
    public static final RegistryObject<Item> KATCHIN_SHOVEL = tool("katchin_shovel",
            () -> new net.minecraft.world.item.ShovelItem(KatchinTiers.KATCHIN_TIER, 1.5F, -3.0F, toolProps()));
    public static final RegistryObject<Item> KATCHIN_HOE = tool("katchin_hoe",
            () -> new net.minecraft.world.item.HoeItem(KatchinTiers.KATCHIN_TIER, -4, 0.0F, toolProps()));
    public static final RegistryObject<Item> KATCHIN_HAMMER = tool("katchin_hammer",
            () -> new KatchinHammerItem(KatchinTiers.KATCHIN_TIER, 2, -3.0F, toolProps()));

    public static final RegistryObject<Item> KATCHI_KATCHIN_PICKAXE = tool("katchi_katchin_pickaxe",
            () -> new KatchiKatchinPickaxeItem(KatchinTiers.KATCHI_KATCHIN_TIER, 1, -2.8F, toolProps()));
    public static final RegistryObject<Item> KATCHI_KATCHIN_AXE = tool("katchi_katchin_axe",
            () -> new KatchiKatchinAxeItem(KatchinTiers.KATCHI_KATCHIN_TIER, 5.0F, -3.0F, toolProps()));
    public static final RegistryObject<Item> KATCHI_KATCHIN_SHOVEL = tool("katchi_katchin_shovel",
            () -> new KatchiKatchinShovelItem(KatchinTiers.KATCHI_KATCHIN_TIER, 1.5F, -3.0F, toolProps()));
    public static final RegistryObject<Item> KATCHI_KATCHIN_HOE = tool("katchi_katchin_hoe",
            () -> new KatchiKatchinHoeItem(KatchinTiers.KATCHI_KATCHIN_TIER, -4, 0.0F, toolProps()));
    public static final RegistryObject<Item> KATCHI_KATCHIN_HAMMER = tool("katchi_katchin_hammer",
            () -> new KatchiKatchinHammerItem(KatchinTiers.KATCHI_KATCHIN_TIER, 2, -3.0F, toolProps()));

    public static final RegistryObject<Item> KATCHIN_UPGRADE_TEMPLATE = template("katchin_upgrade_smithing_template");
    public static final RegistryObject<Item> KATCHI_KATCHIN_UPGRADE_TEMPLATE =
            template("katchi_katchin_upgrade_smithing_template");

    private static RegistryObject<Item> tool(String name, java.util.function.Supplier<Item> sup)
    {
        RegistryObject<Item> obj = ITEMS.register(name, sup);
        TOOLS.add(obj);
        return obj;
    }

    // Plain items: a template only needs to exist for the recipe to reference. Kept plain (not vanilla
    // SmithingTemplateItem) so the asset side needs only one flat icon per template, no empty-slot sprite set.
    private static RegistryObject<Item> template(String name)
    {
        RegistryObject<Item> obj = ITEMS.register(name, () -> new Item(new Item.Properties().rarity(Rarity.RARE)));
        TEMPLATES.add(obj);
        return obj;
    }
}
