package net.shurui.dev.shuruis_raid_bosses.registry;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.dev.shuruis_raid_bosses.Shuruis_raid_bosses;
import net.shurui.dev.shuruis_raid_bosses.entity.DimensionalTear;
import net.shurui.dev.shuruis_raid_bosses.entity.RaidNpc;

public final class ModEntities {
    private ModEntities() {}

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Shuruis_raid_bosses.MODID);

    public static final RegistryObject<EntityType<RaidNpc>> RAID_NPC = ENTITIES.register(
            "raid_npc",
            () -> EntityType.Builder.of(RaidNpc::new, MobCategory.MISC)
                    .sized(0.6f, 1.95f)
                    .clientTrackingRange(10)
                    .build("raid_npc"));

    /**
     * An open dimensional tear. Tracked far out (meant to be spotted across the area it opened in), never
     * spawned by egg or command, and updated slowly since it does not move: only label and existence matter.
     */
    public static final RegistryObject<EntityType<DimensionalTear>> DIMENSIONAL_TEAR = ENTITIES.register(
            "dimensional_tear",
            () -> EntityType.Builder.<DimensionalTear>of(DimensionalTear::new, MobCategory.MISC)
                    // Matched to the drawn swirl: 3 across, 0.5 to 3.5 tall. The box IS what "walking into
                    // the tear" tests, so smaller than the art means players pass through the picture.
                    .sized(3.0f, 3.5f)
                    .clientTrackingRange(12)
                    .updateInterval(40)
                    .noSummon()
                    .fireImmune()
                    .build("dimensional_tear"));
}
