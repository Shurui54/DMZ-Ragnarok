package net.shurui.shuruisutilities.hoverbike;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

// one looping rev sound per variant (hoverbike_rev_1..4); sounds.json comes from the asset pipeline
public final class HoverbikeSounds
{
    private HoverbikeSounds() {}

    public static final DeferredRegister<SoundEvent> REGISTER =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ShuruisUtilities.MODID);

    // index 0 unused; 1-4 = per-variant loops so REV[variant] reads naturally
    @SuppressWarnings("unchecked")
    public static final RegistryObject<SoundEvent>[] REV = new RegistryObject[5];

    static
    {
        for (int v = 1; v <= 4; v++)
        {
            final String id = "hoverbike_rev_" + v;
            REV[v] = REGISTER.register(id,
                    () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(ShuruisUtilities.MODID, id)));
        }
    }
}
