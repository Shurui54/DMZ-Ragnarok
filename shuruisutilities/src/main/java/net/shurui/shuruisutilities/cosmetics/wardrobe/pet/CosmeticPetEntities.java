package net.shurui.shuruisutilities.cosmetics.wardrobe.pet;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Registry for the one cosmetic pet entity type. One class covers every pet; the rig is picked by a synched pet id,
 * exactly as the cosmetic mount picks its model by a synched mount id.
 *
 * <h2>Registered UNCONDITIONALLY</h2>
 * A registry must be identical on the client and the server or the two cannot connect, so this is bound to the mod
 * bus from {@code ShuruisUtilities}'s constructor regardless of any key tier or the Cosmetics module switch. What
 * the switchboard gates is whether a pet may be SPAWNED, decided at the spawn call in {@link CosmeticPetManager},
 * never whether the entity type exists.
 *
 * <p>{@code .noSave()} is the load-bearing flag: a spawned pet is never written to disk, so a chunk unload or a
 * restart discards it rather than leaving a stray creature behind. There is therefore no recall SavedData to keep
 * in step: the in-memory {@link CosmeticPetManager} map plus non-persistence is the whole story.
 * {@code .fireImmune()} because a cosmetic must not catch fire.
 */
public final class CosmeticPetEntities
{
    private CosmeticPetEntities()
    {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<EntityType<CosmeticPetEntity>> COSMETIC_PET =
            ENTITY_TYPES.register("cosmetic_pet",
                    () -> EntityType.Builder.<CosmeticPetEntity>of(CosmeticPetEntity::new, MobCategory.MISC)
                            .sized(0.5F, 0.7F)
                            .fireImmune()
                            .noSave()
                            .clientTrackingRange(10)
                            .updateInterval(1)
                            .build("cosmetic_pet"));
}
