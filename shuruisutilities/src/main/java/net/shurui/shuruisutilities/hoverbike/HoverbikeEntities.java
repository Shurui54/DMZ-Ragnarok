package net.shurui.shuruisutilities.hoverbike;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

// entity registry for the hoverbikes. one type, synched Variant byte (1-4); the four items pick the variant.
public final class HoverbikeEntities
{
    private HoverbikeEntities() {}

    public static final DeferredRegister<EntityType<?>> REGISTER =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    // ~1.25w x 1.0t vehicle box. MISC = no natural-spawn/mob-cap. width trimmed 1.4->1.25 so it stops catching
    // corner lips a 0.6 player box slides past; seat/render are width-independent, and both getBbWidth() consumers
    // (dismount escape, sweep threshold) scale with it.
    public static final RegistryObject<EntityType<HoverbikeEntity>> HOVERBIKE = REGISTER.register("hoverbike",
            () -> EntityType.Builder.<HoverbikeEntity>of(HoverbikeEntity::new, MobCategory.MISC)
                    .sized(1.25F, 1.0F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build("hoverbike"));
}
