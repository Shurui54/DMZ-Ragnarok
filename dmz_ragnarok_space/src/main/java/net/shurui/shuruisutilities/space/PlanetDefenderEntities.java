package net.shurui.shuruisutilities.space;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Registry + attribute creation for the {@link PlanetDefenderEntity} planet-clash holder. Attribute creation is on the
 * MOD bus.
 *
 * <p>MobCategory.MISC keeps it off natural-spawn / mob-cap accounting: only placed by the planet-buster clash gate.
 * Hitbox matches a DMZ saga humanoid, though it never collides (non-pushable and invisible).
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PlanetDefenderEntities
{
    private PlanetDefenderEntities()
    {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<EntityType<PlanetDefenderEntity>> PLANET_DEFENDER =
            ENTITY_TYPES.register("planet_defender",
                    () -> EntityType.Builder.<PlanetDefenderEntity>of(PlanetDefenderEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(12)
                            .build("planet_defender"));

    @SubscribeEvent
    public static void createAttributes(EntityAttributeCreationEvent event)
    {
        event.put(PLANET_DEFENDER.get(), PlanetDefenderEntity.createAttributes().build());
    }

    // cast helper so callers need not spell out the DBSagasEntity upper bound at each spawn site.
    @SuppressWarnings("unchecked")
    public static EntityType<PlanetDefenderEntity> type()
    {
        return (EntityType<PlanetDefenderEntity>) (EntityType<?>) PLANET_DEFENDER.get();
    }

    /**
     * Remove every planet-defender holder on server start. A clash interrupted by a crash leaves its persistent holder
     * with no module record to discard it, so it would hang forever in the space void. Never throws.
     */
    public static void sweepOrphans(MinecraftServer server)
    {
        if (server == null)
        {
            return;
        }
        try
        {
            int removed = 0;
            for (ServerLevel level : server.getAllLevels())
            {
                for (Entity entity : level.getAllEntities())
                {
                    if (entity instanceof PlanetDefenderEntity)
                    {
                        entity.discard();
                        removed++;
                    }
                }
            }
            if (removed > 0)
            {
                LoggingHandler.sulog.info(
                        "[PlanetBuster] Swept {} orphaned planet-defender holder(s) left by a previous session.", removed);
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[PlanetBuster] Orphan planet-defender sweep failed; continuing.", t);
        }
    }
}
