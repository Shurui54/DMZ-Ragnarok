package net.shurui.shuruisutilities.gravitychamber;

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
 * Registry + mod-event-bus attribute creation for the single {@link ChamberSparEntity} guild-chamber sparring dummy.
 * Follows SU's existing entity-registration convention exactly (compare {@link
 * net.shurui.shuruisutilities.guilds.raid.clone.GuildRaidCloneEntities}): a {@link DeferredRegister} registered onto
 * the mod bus from {@link ShuruisUtilities}'s constructor, with attribute creation on the MOD bus here.
 *
 * <p>MobCategory.MISC keeps the dummy off natural-spawn / mob-cap accounting; it is only ever placed by {@link
 * ChamberSparManager}.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ChamberSparEntities
{
    private ChamberSparEntities()
    {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<EntityType<ChamberSparEntity>> CHAMBER_SPAR =
            ENTITY_TYPES.register("chamber_spar_dummy",
                    () -> EntityType.Builder.<ChamberSparEntity>of(ChamberSparEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(10)
                            .build("chamber_spar_dummy"));

    @SubscribeEvent
    public static void createAttributes(EntityAttributeCreationEvent event)
    {
        event.put(CHAMBER_SPAR.get(), ChamberSparEntity.createAttributes().build());
    }

    @SuppressWarnings("unchecked")
    public static EntityType<ChamberSparEntity> type()
    {
        return (EntityType<ChamberSparEntity>) (EntityType<?>) CHAMBER_SPAR.get();
    }
}
