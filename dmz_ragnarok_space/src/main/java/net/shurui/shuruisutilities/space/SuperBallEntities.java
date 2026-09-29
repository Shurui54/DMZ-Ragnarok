package net.shurui.shuruisutilities.space;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Registry for the collectible {@link SuperBallEntity}. A plain {@link net.minecraft.world.entity.Entity}, so no
 * {@code EntityAttributeCreationEvent} (that is a {@code LivingEntity} concept).
 *
 * <p>{@link MobCategory#MISC} keeps the ball out of every natural-spawn table. Size {@code 2.9 x 2.9} verified against
 * {@code dball_super4x.geo.json}: inflated cubes span x -23..+23 (2.875 blocks wide) and y 0..45 (2.8125 tall), so a
 * 2.9 hitbox contains the model with a hair of margin and is clickable across its whole silhouette.
 */
public final class SuperBallEntities
{
    private SuperBallEntities()
    {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<EntityType<SuperBallEntity>> SUPER_BALL =
            ENTITY_TYPES.register("super_ball",
                    () -> EntityType.Builder.<SuperBallEntity>of(SuperBallEntity::new, MobCategory.MISC)
                            .sized(2.9F, 2.9F)
                            .fireImmune()
                            .clientTrackingRange(16)
                            .updateInterval(20)
                            .build("super_ball"));
}
