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
 * Registry + attribute creation for Planet Vegeta's TOWN NPCs: the passive {@link PlanetSaiyanCitizenEntity} and the
 * {@link SaiyanTraderEntity} shopkeeper. Attribute creation is on the MOD bus; renderers register in
 * {@code SpaceClientBusEvents}.
 *
 * <p>Both {@link MobCategory#MISC}: never natural-spawn, only placed by the populate-on-arrival system.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PlanetSaiyanTownEntities
{
    private PlanetSaiyanTownEntities()
    {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<EntityType<PlanetSaiyanCitizenEntity>> CITIZEN =
            ENTITY_TYPES.register("planet_saiyan_citizen",
                    () -> EntityType.Builder.<PlanetSaiyanCitizenEntity>of(
                                    PlanetSaiyanCitizenEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(16)
                            .build("planet_saiyan_citizen"));

    public static final RegistryObject<EntityType<SaiyanTraderEntity>> TRADER =
            ENTITY_TYPES.register("planet_saiyan_trader",
                    () -> EntityType.Builder.<SaiyanTraderEntity>of(
                                    SaiyanTraderEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.95F)
                            .clientTrackingRange(16)
                            .build("planet_saiyan_trader"));

    @SubscribeEvent
    public static void createAttributes(EntityAttributeCreationEvent event)
    {
        event.put(CITIZEN.get(), PlanetSaiyanCitizenEntity.createAttributes().build());
        event.put(TRADER.get(), SaiyanTraderEntity.createAttributes().build());
    }
}
