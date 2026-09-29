package net.shurui.dev.sdu.registry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.dev.sdu.DmzNpc;

/** Custom sound events for this addon. */
public final class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, DmzNpc.MODID);

    /** Firing sound for the "biden_blast" ki technique (played from a mixin on DMZ's ki projectile). */
    public static final RegistryObject<SoundEvent> BIDEN_BLAST = SOUND_EVENTS.register("biden_blast",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(DmzNpc.MODID, "biden_blast")));

    /** Charge/erase sound for the bespoke "shuruis_hakai" admin technique (~10.43s; played from HakaiSequence). */
    public static final RegistryObject<SoundEvent> HAKAI = SOUND_EVENTS.register("hakai",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(DmzNpc.MODID, "hakai")));

    private ModSounds() {
    }
}
