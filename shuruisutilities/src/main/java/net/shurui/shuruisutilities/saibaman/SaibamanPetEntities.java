package net.shurui.shuruisutilities.saibaman;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Registry + mod-bus attribute creation for the {@link SaibamanPetEntity} tamed companion. Follows SU's entity
 * convention (compare {@link net.shurui.shuruisutilities.corrupted.ShadowShenronEntities} and the space defenders):
 * a {@link DeferredRegister} attached to the mod bus from {@link ShuruisUtilities}'s constructor, attributes
 * created here on the MOD bus.
 *
 * <p>The registry name {@code shuruisutilities:saibaman_pet} and {@link SaibamanPetEntity#spawnTamed} are the
 * contract the follow-up plant task builds against. MobCategory.CREATURE matches a vanilla tamable so its goals and
 * despawn rules read naturally; it is never natural-spawned (only grown by the plant), so it never touches the
 * natural-spawn tables.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SaibamanPetEntities
{
    private SaibamanPetEntities()
    {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<EntityType<SaibamanPetEntity>> SAIBAMAN_PET =
            ENTITY_TYPES.register("saibaman_pet",
                    () -> EntityType.Builder.<SaibamanPetEntity>of(SaibamanPetEntity::new, MobCategory.CREATURE)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(10)
                            .build("saibaman_pet"));

    @SubscribeEvent
    public static void createAttributes(EntityAttributeCreationEvent event)
    {
        event.put(SAIBAMAN_PET.get(), SaibamanPetEntity.createAttributes().build());
    }
}
