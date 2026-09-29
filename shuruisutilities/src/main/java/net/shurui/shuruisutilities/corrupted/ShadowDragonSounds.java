package net.shurui.shuruisutilities.corrupted;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

// the two cinematic voice lines; the sounds.json entries already exist, this is the Java side so the
// events resolve at runtime. registered from ShuruisUtilities like HoverbikeSounds.
public final class ShadowDragonSounds
{
    private ShadowDragonSounds() {}

    public static final DeferredRegister<SoundEvent> REGISTER =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ShuruisUtilities.MODID);

    // fixed-range (not variable-range) events. they are played client side as flat UI sounds (see
    // PacketGlobalSound / GlobalSoundClient), which are non-positional and never attenuate, so this range is not
    // actually used for distance falloff; a large value just guarantees no volume-derived radius can clip them.
    private static final float GLOBAL_CUE_RANGE = 256.0f;

    public static final RegistryObject<SoundEvent> INTRO = REGISTER.register("shadow_dragon_intro",
            () -> SoundEvent.createFixedRangeEvent(
                    new ResourceLocation(ShuruisUtilities.MODID, "shadow_dragon_intro"), GLOBAL_CUE_RANGE));

    public static final RegistryObject<SoundEvent> BEHOLD = REGISTER.register("shadow_dragon_behold",
            () -> SoundEvent.createFixedRangeEvent(
                    new ResourceLocation(ShuruisUtilities.MODID, "shadow_dragon_behold"), GLOBAL_CUE_RANGE));
}
