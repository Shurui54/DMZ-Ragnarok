package net.shurui.shuruisutilities.core.mixin.stats;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for the private {@code raceName} field of DMZ's {@link com.dragonminez.common.network.C2S.StatsSyncC2S}
 * so {@link MixinDmzRacePrestigeGate} can read the requested race without reflection. {@code remap = false}:
 * the field resolves against DMZ's own (non-Mojmap) name.
 */
@Mixin(targets = "com.dragonminez.common.network.C2S.StatsSyncC2S", remap = false)
public interface AccessorStatsSyncC2S
{
    @Accessor("raceName")
    String su$getRaceName();
}
