package net.shurui.shuruisutilities.guilds.raid.clone;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;

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
 * Registry + mod-event-bus attribute creation for the single {@link GuildRaidCloneEntity} guild-raid clone driver.
 * Follows SU's existing entity-registration convention exactly (compare {@link
 * net.shurui.shuruisutilities.corrupted.ShadowShenronEntities} and {@link
 * net.shurui.shuruisutilities.ragnarok.RgNpcEntities}): a {@link DeferredRegister} registered onto the mod bus
 * from {@link ShuruisUtilities}'s constructor, and attribute creation handled here on the MOD bus.
 *
 * <p>MobCategory.MISC keeps the clone off the natural-spawn / mob-cap accounting: it is never naturally spawned,
 * only placed by the raid. The hitbox matches a DragonMineZ saga humanoid so melee reach and ki-blast collision
 * feel right.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GuildRaidCloneEntities
{
    private GuildRaidCloneEntities()
    {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<EntityType<GuildRaidCloneEntity>> GUILD_RAID_CLONE =
            ENTITY_TYPES.register("guild_raid_clone",
                    () -> EntityType.Builder.<GuildRaidCloneEntity>of(GuildRaidCloneEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(10)
                            .build("guild_raid_clone"));

    @SubscribeEvent
    public static void createAttributes(EntityAttributeCreationEvent event)
    {
        event.put(GUILD_RAID_CLONE.get(), GuildRaidCloneEntity.createAttributes().build());
    }

    // cast helper so callers do not have to spell out the DBSagasEntity upper bound at each spawn site.
    @SuppressWarnings("unchecked")
    public static EntityType<GuildRaidCloneEntity> type()
    {
        return (EntityType<GuildRaidCloneEntity>) (EntityType<?>) GUILD_RAID_CLONE.get();
    }
}
