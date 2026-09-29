package net.shurui.shuruisutilities.zorb;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Entity registry for {@link ZOrbEntity}, {@code dmz_ragnarok:z_orb}. Registered UNCONDITIONALLY (in both suite
 * outputs, key present or not), so client and server always agree the type exists and a keyless summon has
 * something to build before its first tick discards it. {@link MobCategory#MISC} so it never natural-spawns and
 * never counts against the mob cap; a small 0.5 x 0.5 hull; short tracking range with a light update interval.
 * Registered from {@link ShuruisUtilities}'s constructor, alongside the time machine.
 */
public final class ZOrbEntities
{
    private ZOrbEntities() {}

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<EntityType<ZOrbEntity>> Z_ORB =
            ENTITY_TYPES.register("z_orb",
                    () -> EntityType.Builder.<ZOrbEntity>of(ZOrbEntity::new, MobCategory.MISC)
                            .sized(0.5F, 0.5F)
                            .clientTrackingRange(32)
                            .updateInterval(2)
                            .fireImmune()
                            .build("z_orb"));
}
