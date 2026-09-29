package net.shurui.shuruisutilities.cosmetics.wardrobe.mount;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Registry for the one cosmetic mount entity type. One class covers every mount; the rig is picked by a synched
 * mount id, exactly as the hoverbike picks its model by a synched variant.
 *
 * <h2>Registered UNCONDITIONALLY</h2>
 * A registry must be identical on the client and the server or the two cannot connect, so this is bound to the mod
 * bus from {@code ShuruisUtilities}'s constructor regardless of any key tier or the Cosmetics module switch. What
 * the key and the switchboard gate is whether a mount may be SUMMONED, decided at the summon call in
 * {@code CosmeticMountManager}, never whether the entity type exists.
 *
 * <p>{@code .noSave()} is the load-bearing flag: a summoned mount is never written to disk, so a chunk unload or a
 * restart discards it rather than leaving a stray vehicle behind. {@code .fireImmune()} because a cosmetic must not
 * catch fire, and the box is a generous default; the real per-mount box comes from
 * {@link CosmeticMountEntity#getDimensions}.
 */
public final class CosmeticMountEntities
{
    private CosmeticMountEntities()
    {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    public static final RegistryObject<EntityType<CosmeticMountEntity>> COSMETIC_MOUNT =
            ENTITY_TYPES.register("cosmetic_mount",
                    () -> EntityType.Builder.<CosmeticMountEntity>of(CosmeticMountEntity::new, MobCategory.MISC)
                            .sized(1.8F, 1.8F)
                            .fireImmune()
                            .noSave()
                            .clientTrackingRange(10)
                            .updateInterval(1)
                            .build("cosmetic_mount"));
}
