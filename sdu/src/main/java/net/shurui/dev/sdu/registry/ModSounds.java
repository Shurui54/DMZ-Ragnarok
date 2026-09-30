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

    // ------------------------------------------------------------------ Pilaf Mech voice lines
    //
    // One sound event per voice trigger for the Pilaf Mech raid boss (PilafMechEntity). Each ships as a
    // registered-but-silent event (empty "sounds" array in sounds.json) so the owner can later drop recorded
    // .ogg voice clips into assets/dmz_ragnarok/sounds/pilaf_mech/ and just list them under the matching event.
    // Minecraft picks a clip at random from the listed variants, so several files can back a single trigger.
    // The chat/actionbar line always plays; the sound is only heard once real clips are added.

    private static RegistryObject<SoundEvent> voice(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(DmzNpc.MODID, name)));
    }

    public static final RegistryObject<SoundEvent> PILAF_VOICE_SPAWN = voice("pilaf_mech.voice.spawn");
    public static final RegistryObject<SoundEvent> PILAF_VOICE_ATTACK = voice("pilaf_mech.voice.attack");
    public static final RegistryObject<SoundEvent> PILAF_VOICE_HURT = voice("pilaf_mech.voice.hurt");
    public static final RegistryObject<SoundEvent> PILAF_VOICE_ENRAGE = voice("pilaf_mech.voice.enrage");
    public static final RegistryObject<SoundEvent> PILAF_VOICE_PHASE2 = voice("pilaf_mech.voice.phase2");
    public static final RegistryObject<SoundEvent> PILAF_VOICE_LOW_HEALTH = voice("pilaf_mech.voice.low_health");
    public static final RegistryObject<SoundEvent> PILAF_VOICE_DEATH = voice("pilaf_mech.voice.death");
    public static final RegistryObject<SoundEvent> PILAF_VOICE_KILL = voice("pilaf_mech.voice.kill");

    private ModSounds() {
    }
}
