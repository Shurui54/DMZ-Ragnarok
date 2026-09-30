package net.shurui.shuruisutilities.space;

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
 * Registry + attribute creation for the {@link PlanetOwnerAvatarEntity} owner-avatar planet defender. Attribute creation
 * is on the MOD bus. MobCategory.MISC keeps it off natural-spawn / mob-cap accounting: it is only placed by
 * {@link PlanetOwnerAvatar}.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PlanetOwnerAvatarEntities
{
    private PlanetOwnerAvatarEntities()
    {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<EntityType<PlanetOwnerAvatarEntity>> OWNER_AVATAR =
            ENTITY_TYPES.register("planet_owner_avatar",
                    () -> EntityType.Builder.<PlanetOwnerAvatarEntity>of(
                                    PlanetOwnerAvatarEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(16)
                            .build("planet_owner_avatar"));

    @SubscribeEvent
    public static void createAttributes(EntityAttributeCreationEvent event)
    {
        event.put(OWNER_AVATAR.get(), PlanetOwnerAvatarEntity.createAttributes().build());
    }

    // cast helper so callers need not spell out the DBSagasEntity upper bound at each spawn site.
    @SuppressWarnings("unchecked")
    public static EntityType<PlanetOwnerAvatarEntity> type()
    {
        return (EntityType<PlanetOwnerAvatarEntity>) (EntityType<?>) OWNER_AVATAR.get();
    }
}
