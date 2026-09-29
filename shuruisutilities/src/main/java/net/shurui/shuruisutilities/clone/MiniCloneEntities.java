package net.shurui.shuruisutilities.clone;

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
 * Registry + mod-bus attribute creation for the {@link MiniCloneEntity}, following {@code SaibamanPetEntities}
 * exactly: a {@link DeferredRegister} attached to the mod bus from {@link ShuruisUtilities}'s constructor, attributes
 * created here on the MOD bus.
 *
 * <p>Registry name {@code dmz_ragnarok:mini_clone}. MobCategory.CREATURE matches a vanilla tamable so its goals and
 * despawn rules read naturally; it is never natural-spawned (only summoned by the technique), so it never touches the
 * natural-spawn tables. Sized to a 60% player (0.6 * 0.6 wide, 0.6 * 1.8 tall) so the physical hitbox matches the
 * rendered {@link MiniCloneEntity#CLONE_SCALE}.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MiniCloneEntities
{
    private MiniCloneEntities()
    {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<EntityType<MiniCloneEntity>> MINI_CLONE =
            ENTITY_TYPES.register("mini_clone",
                    () -> EntityType.Builder.<MiniCloneEntity>of(MiniCloneEntity::new, MobCategory.CREATURE)
                            .sized(0.6F * MiniCloneEntity.CLONE_SCALE, 1.8F * MiniCloneEntity.CLONE_SCALE)
                            .clientTrackingRange(10)
                            .build("mini_clone"));

    @SubscribeEvent
    public static void createAttributes(EntityAttributeCreationEvent event)
    {
        event.put(MINI_CLONE.get(), MiniCloneEntity.createAttributes().build());
    }
}
