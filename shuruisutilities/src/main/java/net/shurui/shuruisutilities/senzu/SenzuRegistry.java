package net.shurui.shuruisutilities.senzu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.content.ContentItems;
import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * All senzu-farming registrations: the bean items (consumable and totem), seeds, pots (blank + eight typed) and the shared
 * pot block-entity type. The three DeferredRegisters are added to the mod event bus from {@link ShuruisUtilities},
 * alongside SU's other registers.
 *
 * <p>Every id here is underscore-cased, so none collides with the pre-existing camel-run placeholders in
 * {@link ContentItems} ({@code senzubean}, {@code senzuhp}, {@code beanplant} and friends), which are separate legacy
 * content. As each item is registered it is also slotted into the right ContentItems list (beans and seeds into
 * CONSUMABLES, pot BlockItems into BLOCKS_MISC) so it shows up in the shared creative tabs; that runs when this class
 * is first touched (its register fields are pulled in the SU constructor), which forces ContentItems to fill its
 * placeholder lists first, so the additions land after the placeholders and never race the tab display.
 */
public final class SenzuRegistry
{
    private SenzuRegistry() {}

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, ShuruisUtilities.MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ShuruisUtilities.MODID);

    // every bean + cracked-variant item keyed by its registry id (bean_senzu, bean_cracked, bean_cracked_hp, ...), so
    // the pot harvest can resolve a drop by id.
    private static final Map<String, RegistryObject<Item>> BEANS_BY_ID = new LinkedHashMap<>();
    // one seed per bean type.
    private static final Map<BeanType, RegistryObject<Item>> SEEDS = new LinkedHashMap<>();
    // one typed pot block per bean type (the blank pot is separate; it grows nothing).
    private static final Map<BeanType, RegistryObject<Block>> POTS = new LinkedHashMap<>();

    public static RegistryObject<Item> GOLDEN_BEAN;
    public static RegistryObject<Item> CRACKED_GOLDEN_BEAN;

    // the blank pot and the shared pot block-entity type.
    public static RegistryObject<Block> BLANK_POT;
    public static RegistryObject<BlockEntityType<BeanPotBlockEntity>> BEAN_POT_BE;

    static
    {
        registerBeans();
        registerSeeds();
        registerPots();
    }

    private static void registerBeans()
    {
        // consumable beans. Fraction is share-of-max for the heal kinds and share-of-max-to-drain-to for DRAIN; POISON
        // ignores it. The senzu bean is the only one that also clears DMZ combat locks.
        bean("bean_senzu", () -> new BeanItem(BeanItem.Kind.FULL, 1.0f, true));
        bean("bean_hp", () -> new BeanItem(BeanItem.Kind.HEALTH, 1.0f, false));
        bean("bean_ki", () -> new BeanItem(BeanItem.Kind.ENERGY, 1.0f, false));
        bean("bean_stamina", () -> new BeanItem(BeanItem.Kind.STAMINA, 1.0f, false));
        // the cracked senzu: a weaker full restore (75% of each max) that does NOT clear combat locks.
        bean("bean_cracked", () -> new BeanItem(BeanItem.Kind.FULL, 0.35f, false));
        bean("bean_cracked_hp", () -> new BeanItem(BeanItem.Kind.HEALTH, 0.35f, false));
        bean("bean_cracked_ki", () -> new BeanItem(BeanItem.Kind.ENERGY, 0.35f, false));
        bean("bean_cracked_stamina", () -> new BeanItem(BeanItem.Kind.STAMINA, 0.35f, false));
        // the burnt bean: poison + hunger, no restore.
        bean("bean_burnt", () -> new BeanItem(BeanItem.Kind.POISON, 0.0f, false));
        // the death bean: drains energy and stamina to zero (no health damage). The cracked death bean drains to 25%.
        bean("bean_death", () -> new BeanItem(BeanItem.Kind.DRAIN, 0.0f, false));
        bean("bean_cracked_death", () -> new BeanItem(BeanItem.Kind.DRAIN, 0.25f, false));

        // the golden beans are NOT edible: they are passive death-totems handled on LivingDeathEvent (see SenzuModule),
        // so they register as plain items with no eating behaviour.
        GOLDEN_BEAN = plainBean("bean_golden");
        CRACKED_GOLDEN_BEAN = plainBean("bean_cracked_golden");
    }

    // register one consumable bean, index it by id, and slot it into the CONSUMABLES creative tab. The BeanItem is built
    // by the supplier, not passed in already-constructed, because an Item may only be instantiated inside the
    // DeferredRegister lambda: its constructor grabs an intrusive holder from the item registry, which is frozen by
    // the time this static block runs during mod construction.
    private static void bean(String id, Supplier<? extends Item> item)
    {
        RegistryObject<Item> ro = ITEMS.register(id, item);
        BEANS_BY_ID.put(id, ro);
        ContentItems.CONSUMABLES.add(ro);
    }

    // register one non-edible bean (the golden totems), index it by id, and slot it into CONSUMABLES too (it still lives
    // with the beans in the tab, it just is not eaten).
    private static RegistryObject<Item> plainBean(String id)
    {
        RegistryObject<Item> ro = ITEMS.register(id, () -> new Item(new Item.Properties()));
        BEANS_BY_ID.put(id, ro);
        ContentItems.CONSUMABLES.add(ro);
        return ro;
    }

    private static void registerSeeds()
    {
        // one plain seed per type. Planting is handled by the pot's right-click, so seeds need no behaviour of their own.
        for (BeanType type : BeanType.values())
        {
            RegistryObject<Item> ro = ITEMS.register(type.seedId(), () -> new Item(new Item.Properties()));
            SEEDS.put(type, ro);
            ContentItems.CONSUMABLES.add(ro);
        }
    }

    private static void registerPots()
    {
        // the blank pot: a plain decorative/crafting block, no planting, no block entity.
        BLANK_POT = BLOCKS.register("bean_pot", () -> new BeanPotBlock(potProps()));
        potItem("bean_pot", BLANK_POT);

        // the eight typed pots: each a BaseEntityBlock that grows its own bean type.
        for (BeanType type : BeanType.values())
        {
            RegistryObject<Block> block = BLOCKS.register(type.potId(), () -> new TypedBeanPotBlock(type, potProps()));
            POTS.put(type, block);
            potItem(type.potId(), block);
        }

        // one shared block-entity type across all eight typed pots (the blank pot has none).
        BEAN_POT_BE = BLOCK_ENTITIES.register("bean_pot",
                () -> BlockEntityType.Builder.of(BeanPotBlockEntity::new, typedPotBlocks()).build(null));
    }

    // register a pot's BlockItem and slot it into the BLOCKS_MISC creative tab.
    private static void potItem(String id, RegistryObject<Block> block)
    {
        RegistryObject<Item> ro = ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties()));
        ContentItems.BLOCKS_MISC.add(ro);
    }

    // every pot block (blank + eight typed), used by the client render-layer setup to force cutout so the pot and
    // plant textures do not render their transparent texels as black. Called after registration, so get() is safe.
    public static List<Block> allPotBlocks()
    {
        List<Block> blocks = new ArrayList<>(POTS.size() + 1);
        blocks.add(BLANK_POT.get());
        for (RegistryObject<Block> ro : POTS.values())
        {
            blocks.add(ro.get());
        }
        return blocks;
    }

    private static Block[] typedPotBlocks()
    {
        Block[] blocks = new Block[POTS.size()];
        int i = 0;
        for (RegistryObject<Block> ro : POTS.values())
        {
            blocks[i++] = ro.get();
        }
        return blocks;
    }

    // pot block properties: a small, non-full-cube terracotta pot. noOcclusion() is required because the pot only fills
    // part of the block (x/z 4..12, y 0..8), so without it the engine would cull the neighbour faces behind it and you
    // would see through the world, exactly the bug that bit the corrupted ball blocks.
    private static BlockBehaviour.Properties potProps()
    {
        return BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_ORANGE).sound(SoundType.STONE)
                .strength(0.6F).noOcclusion();
    }

    // the bean/cracked item for a registry id, resolved at runtime (after registration) by the pot harvest.
    public static Item beanById(String id)
    {
        RegistryObject<Item> ro = BEANS_BY_ID.get(id);
        return ro == null ? null : ro.get();
    }

    public static Item seedItem(BeanType type)
    {
        return SEEDS.get(type).get();
    }

    // stamp the shared bean cooldown onto EVERY edible bean at once, so a single click on any bean locks out all bean
    // kinds together (a per-item cooldown would let a player chain one of each kind back to back, defeating the point).
    // Stamping all of them also makes vanilla's hotbar sweep overlay show on every bean, not just the one clicked. The
    // golden totems live in this same map but are plain Items, not BeanItems, so the instanceof filter skips them: they
    // are passive death-totems with their own cooldown and must not be locked out by eating a bean. Called from
    // BeanItem#use on BOTH sides, because ItemCooldowns is a client-and-server structure (the client needs its copy to
    // block re-use and draw the overlay).
    public static void applyBeanCooldown(Player player, int ticks)
    {
        for (RegistryObject<Item> ro : BEANS_BY_ID.values())
        {
            Item item = ro.get();
            if (item instanceof BeanItem)
            {
                player.getCooldowns().addCooldown(item, ticks);
            }
        }
    }

    // true when this item is any registered bean: a consumable BeanItem OR one of the plain-Item golden death-totems.
    // Both live in BEANS_BY_ID, so a single membership scan covers both and is exactly what the senzu bean bag's slots
    // use to decide "is this a bean I may hold". The map is tiny (a dozen entries) so a linear scan is fine.
    public static boolean isBean(Item item)
    {
        if (item == null)
        {
            return false;
        }
        for (RegistryObject<Item> ro : BEANS_BY_ID.values())
        {
            if (ro.get() == item)
            {
                return true;
            }
        }
        return false;
    }

    // true while the player is on the SHARED bean cooldown. Every BeanItem is stamped with the same cooldown at once
    // (see applyBeanCooldown), so checking any one edible bean answers "are beans on cooldown right now". bean_senzu is
    // always registered, so it is a safe representative. Used by the bean bag's take-a-bean keybind so pulling a bean
    // out is gated by the same rate limit as eating one.
    public static boolean isOnBeanCooldown(Player player)
    {
        if (player == null)
        {
            return false;
        }
        Item representative = beanById("bean_senzu");
        return representative != null && player.getCooldowns().isOnCooldown(representative);
    }

    // the bean type a seed item plants, or null if the item is not one of our seeds. Reverse of the SEEDS map; the map
    // is tiny (eight entries) so a linear scan is fine on the interaction path.
    public static BeanType seedType(Item item)
    {
        if (item == null)
        {
            return null;
        }
        for (Map.Entry<BeanType, RegistryObject<Item>> e : SEEDS.entrySet())
        {
            if (e.getValue().get() == item)
            {
                return e.getKey();
            }
        }
        return null;
    }
}
