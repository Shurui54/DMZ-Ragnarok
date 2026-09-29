package net.shurui.shuruisutilities.corrupted;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

// entity registry for the corrupted cinematic's shadow shenron prop. one type, MISC so it never counts against
// the mob cap or natural-spawns. hitbox is 4w x 8t: a chunky floating box for a large dragon prop, big enough to
// read as "something is there" without the model's full ~16-block visual overflow (which is fine, it is not
// collidable). registered to the mod bus in ShuruisUtilities.
public final class ShadowShenronEntities
{
    private ShadowShenronEntities() {}

    public static final DeferredRegister<EntityType<?>> REGISTER =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<EntityType<ShadowShenronEntity>> SHADOW_SHENRON =
            REGISTER.register("shadow_shenron",
                    () -> EntityType.Builder.<ShadowShenronEntity>of(ShadowShenronEntity::new, MobCategory.MISC)
                            .sized(4.0F, 8.0F)
                            .clientTrackingRange(16)
                            .build("shadow_shenron"));
}
