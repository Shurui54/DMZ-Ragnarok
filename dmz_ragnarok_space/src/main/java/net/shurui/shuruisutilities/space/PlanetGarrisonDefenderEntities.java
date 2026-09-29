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
 * Registry + attribute creation for the {@link PlanetGarrisonDefenderEntity} wild-planet garrison fighter. Attribute
 * creation is on the MOD bus.
 *
 * <p>MobCategory.MISC keeps it off natural-spawn / mob-cap accounting: only placed by {@link PlanetGarrison}. Hitbox
 * matches a DMZ saga humanoid so melee reach and ki-blast collision feel right; the saga chassis' {@code getDimensions}
 * refines it further.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PlanetGarrisonDefenderEntities
{
    private PlanetGarrisonDefenderEntities()
    {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<EntityType<PlanetGarrisonDefenderEntity>> GARRISON_DEFENDER =
            ENTITY_TYPES.register("planet_garrison_defender",
                    () -> EntityType.Builder.<PlanetGarrisonDefenderEntity>of(
                                    PlanetGarrisonDefenderEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(16)
                            .build("planet_garrison_defender"));

    @SubscribeEvent
    public static void createAttributes(EntityAttributeCreationEvent event)
    {
        event.put(GARRISON_DEFENDER.get(), PlanetGarrisonDefenderEntity.createAttributes().build());
    }

    // cast helper so callers need not spell out the DBSagasEntity upper bound at each spawn site.
    @SuppressWarnings("unchecked")
    public static EntityType<PlanetGarrisonDefenderEntity> type()
    {
        return (EntityType<PlanetGarrisonDefenderEntity>) (EntityType<?>) GARRISON_DEFENDER.get();
    }
}
