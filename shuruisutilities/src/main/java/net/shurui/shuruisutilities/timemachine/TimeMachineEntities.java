package net.shurui.shuruisutilities.timemachine;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Entity registry for the rideable {@link TimeMachineEntity}. One type, no AI, {@link MobCategory#MISC} so it never
 * natural-spawns and never counts against the mob cap. The hitbox is sized to the visible hull at
 * {@link TimeMachineSeat#RENDER_SCALE}: the rendered geo is 3.43 x 5.85 x 3.19 blocks natural (measured off the
 * GeckoLib-transformed geometry, not the raw file), so at 0.6 scale it draws about 2.06 wide by 3.51 tall by 1.91
 * deep. A 2.2 x 3.6 box fully contains that silhouette so the machine is clickable and un-walk-through across its
 * whole footprint. Registered from {@link ShuruisUtilities}'s constructor.
 */
public final class TimeMachineEntities
{
    private TimeMachineEntities() {}

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<EntityType<TimeMachineEntity>> TIME_MACHINE =
            ENTITY_TYPES.register("time_machine",
                    () -> EntityType.Builder.<TimeMachineEntity>of(TimeMachineEntity::new, MobCategory.MISC)
                            .sized(2.2F, 3.6F)
                            .clientTrackingRange(12)
                            .updateInterval(1)
                            .build("time_machine"));
}
