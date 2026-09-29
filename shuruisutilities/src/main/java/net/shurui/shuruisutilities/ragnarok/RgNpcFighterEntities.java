package net.shurui.shuruisutilities.ragnarok;

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
 * Registry and attribute creation for {@link RgNpcFighterEntity}, the combat-capable ragnarok character.
 *
 * <p>Follows SU's entity-registration convention exactly (compare
 * {@link net.shurui.shuruisutilities.space.PlanetGarrisonDefenderEntities}): a {@link DeferredRegister} put on the
 * mod bus from {@link ShuruisUtilities}'s constructor, with attribute creation handled here on the MOD bus.
 *
 * <p>{@link MobCategory#MISC} keeps it out of natural-spawn and mob-cap accounting: one of these only ever exists
 * because an admin placed it through an editor. The registered box is a saga humanoid, but the entity's own
 * {@code getDimensions} replaces it per model, so a giant gets a giant's hitbox.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RgNpcFighterEntities
{
    private RgNpcFighterEntities()
    {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    /**
     * The registry PATH is load-bearing: it is what every saved spawner config, raid def and quest objective
     * stores, so renaming it orphans all of them (and would need a Forge registry remap, not a rename).
     */
    public static final RegistryObject<EntityType<RgNpcFighterEntity>> RGNPC_FIGHTER =
            ENTITY_TYPES.register("rgnpc_fighter",
                    () -> EntityType.Builder.<RgNpcFighterEntity>of(RgNpcFighterEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(16)
                            .build("rgnpc_fighter"));

    @SubscribeEvent
    public static void createAttributes(EntityAttributeCreationEvent event)
    {
        event.put(RGNPC_FIGHTER.get(), RgNpcFighterEntity.createAttributes().build());
    }
}
