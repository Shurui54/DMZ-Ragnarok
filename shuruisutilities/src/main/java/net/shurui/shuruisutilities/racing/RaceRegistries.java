package net.shurui.shuruisutilities.racing;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.racing.block.RaceBoostPadBlock;
import net.shurui.shuruisutilities.racing.block.RaceFinishLineBlock;
import net.shurui.shuruisutilities.racing.block.RaceItemSpawnerBlock;
import net.shurui.shuruisutilities.racing.block.RaceItemSpawnerBlockEntity;
import net.shurui.shuruisutilities.racing.entity.RaceItemBoxEntity;
import net.shurui.shuruisutilities.racing.entity.RaceKiOrbEntity;
import net.shurui.shuruisutilities.racing.entity.RaceSaibamanEntity;
import net.shurui.shuruisutilities.racing.item.TrackWandItem;

/**
 * Every core registry object the PRIVATE racing feature needs, all in the {@code dmz_ragnarok} namespace and all
 * registered UNCONDITIONALLY (in both suite outputs, key present or not), so client and server always agree these
 * ids exist and a keyless server has something to build before its behaviour discards it. Nothing here carries
 * behaviour: the entities tick into {@link net.shurui.shuruisutilities.api.key.RaceHooks} (keyless: discard), the
 * blocks are inert markers, and the wand is a side-safe tool. There is nothing for a module-absence guard to reap.
 *
 * <p>Registered from {@link ShuruisUtilities}'s constructor via {@link #register(IEventBus)}.
 */
public final class RaceRegistries
{
    private RaceRegistries() {}

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, ShuruisUtilities.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ShuruisUtilities.MODID);
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ShuruisUtilities.MODID);

    // --- entities ---

    public static final RegistryObject<EntityType<RaceItemBoxEntity>> RACE_ITEM_BOX =
            ENTITY_TYPES.register("race_item_box",
                    () -> EntityType.Builder.<RaceItemBoxEntity>of(RaceItemBoxEntity::new, MobCategory.MISC)
                            .sized(1.1F, 1.1F)
                            .clientTrackingRange(8)
                            .updateInterval(1)
                            .fireImmune()
                            .build("race_item_box"));

    public static final RegistryObject<EntityType<RaceKiOrbEntity>> RACE_KI_ORB =
            ENTITY_TYPES.register("race_ki_orb",
                    () -> EntityType.Builder.<RaceKiOrbEntity>of(RaceKiOrbEntity::new, MobCategory.MISC)
                            .sized(0.5F, 0.5F)
                            .clientTrackingRange(8)
                            .updateInterval(1)
                            .fireImmune()
                            .build("race_ki_orb"));

    public static final RegistryObject<EntityType<RaceSaibamanEntity>> RACE_SAIBAMAN =
            ENTITY_TYPES.register("race_saibaman",
                    () -> EntityType.Builder.<RaceSaibamanEntity>of(RaceSaibamanEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.2F)
                            .clientTrackingRange(8)
                            .updateInterval(2)
                            .fireImmune()
                            .build("race_saibaman"));

    // --- blocks + their block items ---

    /** Kept in registration order so the creative tab pours the four racing items in as one group. */
    public static final List<RegistryObject<Item>> BLOCK_ITEMS = new ArrayList<>();

    public static final RegistryObject<Block> RACE_BOOST_PAD = BLOCKS.register("race_boost_pad",
            () -> new RaceBoostPadBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .sound(SoundType.METAL)
                    .strength(1.5F, 6.0F)
                    .lightLevel(s -> 7)
                    .noOcclusion()
                    .noCollission()));

    public static final RegistryObject<Block> RACE_ITEM_SPAWNER = BLOCKS.register("race_item_spawner",
            () -> new RaceItemSpawnerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE)
                    .sound(SoundType.METAL)
                    .strength(2.0F, 6.0F)
                    .noOcclusion()));

    public static final RegistryObject<Block> RACE_FINISH_LINE = BLOCKS.register("race_finish_line",
            () -> new RaceFinishLineBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.SNOW)
                    .sound(SoundType.STONE)
                    .strength(2.0F, 6.0F)));

    public static final RegistryObject<Item> RACE_BOOST_PAD_ITEM = blockItem("race_boost_pad", RACE_BOOST_PAD);
    public static final RegistryObject<Item> RACE_ITEM_SPAWNER_ITEM = blockItem("race_item_spawner", RACE_ITEM_SPAWNER);
    public static final RegistryObject<Item> RACE_FINISH_LINE_ITEM = blockItem("race_finish_line", RACE_FINISH_LINE);

    // --- block entity ---

    public static final RegistryObject<BlockEntityType<RaceItemSpawnerBlockEntity>> RACE_ITEM_SPAWNER_BE =
            BLOCK_ENTITIES.register("race_item_spawner",
                    () -> BlockEntityType.Builder.of(RaceItemSpawnerBlockEntity::new, RACE_ITEM_SPAWNER.get())
                            .build(null));

    // --- items ---

    public static final RegistryObject<Item> RACE_TRACK_WAND = ITEMS.register("race_track_wand",
            () -> new TrackWandItem(new Item.Properties().stacksTo(1)));

    // --- sounds (race.*, twelve; aliased in sounds.json to DMZ/vanilla oggs, finalised in R11) ---

    public static final String[] SOUND_NAMES = {
            "race.countdown", "race.go", "race.roulette", "race.item_get", "race.lap", "race.final_lap",
            "race.finish", "race.drift", "race.mini_turbo", "race.boost", "race.hit", "race.rescue",
            // R7 self powerups (aliased in sounds.json to the DMZ equivalents).
            "race.senzu", "race.aura_start", "race.kaioken", "race.nimbus", "race.afterimage", "race.zeni",
            // R9 globals, finalised in R11: their own DMZ oggs so a Spirit Bomb, Kiai, Gravity Crush, Solar Flare
            // and Saibaman each read distinctly (spirit_bomb_charge is the wind-up, impact is the shared detonation).
            "race.spirit_bomb", "race.spirit_bomb_charge", "race.kiai", "race.gravity", "race.solar_flare",
            "race.saibaman", "race.impact"
    };

    /** Sound events by name, in {@link #SOUND_NAMES} order. */
    public static final List<RegistryObject<SoundEvent>> SOUND_EVENTS = new ArrayList<>();
    private static final java.util.Map<String, RegistryObject<SoundEvent>> SOUND_BY_NAME = new java.util.HashMap<>();

    static
    {
        for (String name : SOUND_NAMES)
        {
            RegistryObject<SoundEvent> ro = SOUNDS.register(name,
                    () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(ShuruisUtilities.MODID, name)));
            SOUND_EVENTS.add(ro);
            SOUND_BY_NAME.put(name, ro);
        }
    }

    /** The registered {@code race.*} SoundEvent for a name (e.g. {@code "race.senzu"}), or null if unknown. */
    public static SoundEvent sound(String name)
    {
        RegistryObject<SoundEvent> ro = SOUND_BY_NAME.get(name);
        return ro == null ? null : ro.get();
    }

    private static RegistryObject<Item> blockItem(String name, RegistryObject<Block> block)
    {
        RegistryObject<Item> i = ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
        BLOCK_ITEMS.add(i);
        return i;
    }

    /** Attach every racing register to the mod event bus. Called once from {@link ShuruisUtilities}'s constructor. */
    public static void register(IEventBus modBus)
    {
        ENTITY_TYPES.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        SOUNDS.register(modBus);
    }
}
