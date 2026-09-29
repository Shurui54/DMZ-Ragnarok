package net.shurui.shuruisutilities.crate.block;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The crate blocks the SU crate system can be bound to, and the keys that open them.
 *
 * <p>Thirty four crates. Fourteen of Toffy's: the original three and the four V3 blueprints came with their own
 * rigs and idle/open animations, while the seven colour crates arrived as flat item models and were rigged here.
 * The Lootcrates boxes are four geometries by five skins, which is exactly the twenty keys that pack ships, so
 * every key has the box it belongs to; they did not animate at all and were rigged and animated during conversion.
 *
 * <p>The keys are plain items on purpose. SU's crate config names its key by registry id
 * ({@code Crate.keyItem}), so registering them is all an admin needs to point a crate at one; the NBT stamp that
 * authorises an actual opening is still applied by {@code CrateManager}.
 */
public final class SuCrateBlocks
{
    private SuCrateBlocks() {}

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, ShuruisUtilities.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    /** Every crate block, in registration order, for the block entity type and the creative tab. */
    public static final List<RegistryObject<Block>> CRATE_BLOCKS = new ArrayList<>();
    /** Every crate's block item. */
    public static final List<RegistryObject<Item>> CRATE_ITEMS = new ArrayList<>();
    /** Every key item. */
    public static final List<RegistryObject<Item>> KEY_ITEMS = new ArrayList<>();

    /**
     * The Halloween crate block and its block item.
     *
     * <p>A full {@link SuCrateBlock} like the rest: the Halloween chest was rigged into a two-bone GeckoLib model
     * ({@code geo/block/crate/halloween_crate}, base plus a hinged lid) from the shipped static chest cubes, so it
     * idles and plays an open animation on a keyed open exactly as the Toffy and Lootcrates crates do. Its model is
     * authored at 1.5x, so the placed crate is 50% bigger while still sitting on the block floor and centred, and it
     * inherits the plain full-cube interaction shape every crate uses, which is what keeps the right-click reliable
     * at the larger size. Kept as its own field (not built through {@link #crate}) only so its item stays out of
     * {@link #CRATE_ITEMS} and the creative tab lists it once; it IS added to {@link #CRATE_BLOCKS} below so the
     * shared block entity type accepts it. Its key is {@code hw_crate_key}, registered in {@code CosmeticContentItems}.
     */
    public static final RegistryObject<Block> HALLOWEEN_CRATE = BLOCKS.register("halloween_crate",
            () -> new SuCrateBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5F)
                    .sound(SoundType.WOOD)
                    .noOcclusion(),
                    "halloween_crate", "block/crate/halloween_crate"));
    public static final RegistryObject<Item> HALLOWEEN_CRATE_ITEM = ITEMS.register("halloween_crate",
            () -> new SuCrateBlockItem(HALLOWEEN_CRATE.get(), new Item.Properties()));

    // Toffy's crates carry geometry, animation and texture under one name.
    //
    // Three waves of them: the original ModelEngine set, the V3 blueprints (which brought their own rigs and
    // idle/open animations), and the colour set, which arrived as flat item models with no rig at all and was
    // split into base and lid at the seam the artwork already draws, the same treatment the Lootcrates boxes got.
    private static final String[] TOFFY = {
            "toffy_crate_creature", "toffy_crate_raven", "toffy_crate_sweet_tooth",
            "toffy_crate_explosive", "toffy_crate_inhabitant", "toffy_crate_owl", "toffy_crate_piano",
            "toffy_crate_black", "toffy_crate_blue", "toffy_crate_cinder", "toffy_crate_green",
            "toffy_crate_purple", "toffy_crate_red", "toffy_crate_viking",
    };

    // one key per Toffy crate, in the same order, drawn from the pack's own key artwork.
    private static final String[] TOFFY_KEYS = {
            "toffy_key_creature", "toffy_key_raven", "toffy_key_sweet_tooth",
            "toffy_key_explosive", "toffy_key_inhabitant", "toffy_key_owl", "toffy_key_piano",
            "toffy_key_black", "toffy_key_blue", "toffy_key_cinder", "toffy_key_green",
            "toffy_key_paladin", "toffy_key_purple", "toffy_key_red", "toffy_key_viking",
    };

    private static final String[] LOOT_RARITIES = { "normal", "rare", "epic", "legendary" };
    private static final String[] LOOT_MATERIALS = { "amethyst", "diamond", "emerald", "gold", "iron" };

    // the pack draws epic and legendary with the rare artwork, so their skins come from the rare folder even
    // though their geometry is their own.
    private static String textureFolder(String rarity)
    {
        return rarity.equals("normal") ? "normal" : "rare";
    }

    private static void crate(String name, String modelName, String texturePath)
    {
        RegistryObject<Block> block = BLOCKS.register(name, () -> new SuCrateBlock(
                BlockBehaviour.Properties.of()
                        .mapColor(MapColor.WOOD)
                        .strength(2.5F)
                        .sound(SoundType.WOOD)
                        // furniture sized, and some overhang their block, so they must not occlude their
                        // neighbours or light would cut off squarely around them.
                        .noOcclusion(),
                modelName, texturePath));
        CRATE_BLOCKS.add(block);
        // a crate item, not a plain BlockItem: the inventory icon is the GeckoLib crate rather than a flat picture
        // of its texture sheet.
        CRATE_ITEMS.add(ITEMS.register(name, () -> new SuCrateBlockItem(block.get(), new Item.Properties())));
    }

    static
    {
        // The Halloween crate shares the animated crate block entity, so it must be in the valid-block list the
        // SU_CRATE type is built from. Its item is registered above and stays out of CRATE_ITEMS on purpose.
        CRATE_BLOCKS.add(HALLOWEEN_CRATE);
        for (String name : TOFFY)
        {
            crate(name, name, "block/crate/" + name);
        }
        for (String rarity : LOOT_RARITIES)
        {
            for (String material : LOOT_MATERIALS)
            {
                crate("lootcrate_box_" + rarity + "_" + material,
                        "lootcrate_box_" + rarity,
                        "block/crate/lootcrate/" + textureFolder(rarity) + "/" + material + "_box");
            }
        }
        for (String rarity : LOOT_RARITIES)
        {
            for (String material : LOOT_MATERIALS)
            {
                KEY_ITEMS.add(ITEMS.register("lootcrate_key_" + rarity + "_" + material,
                        () -> new Item(new Item.Properties())));
            }
        }
        // Toffy's pack ships a key per crate too, so its three crates are openable the same way the Lootcrates
        // ones are rather than having to borrow someone else's key.
        for (String name : TOFFY_KEYS)
        {
            KEY_ITEMS.add(ITEMS.register(name, () -> new Item(new Item.Properties())));
        }
    }

    /** Every crate block instance, for the shared block entity type. */
    public static Block[] blocks()
    {
        Block[] out = new Block[CRATE_BLOCKS.size()];
        for (int i = 0; i < out.length; i++)
        {
            out[i] = CRATE_BLOCKS.get(i).get();
        }
        return out;
    }
}
