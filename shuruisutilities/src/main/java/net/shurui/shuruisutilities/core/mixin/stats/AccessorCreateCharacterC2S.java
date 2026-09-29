package net.shurui.shuruisutilities.core.mixin.stats;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for the private {@code raceName} field of DMZ's
 * {@link com.dragonminez.common.network.C2S.CreateCharacterC2S} so {@link MixinDmzCreateCharacterRaceGate} can read the
 * requested race without reflection. {@code remap = false}: the field resolves against DMZ's own (non-Mojmap) name.
 *
 * <p>Verified against {@code dragonminez-2.1.3.jar} with {@code javap -p}: {@code CreateCharacterC2S} declares
 * {@code private final java.lang.String raceName;}. (Its sibling {@code UpdateCharacterC2S} has NO {@code raceName}
 * field, so a character update cannot change race and needs no gate.)
 */
@Mixin(targets = "com.dragonminez.common.network.C2S.CreateCharacterC2S", remap = false)
public interface AccessorCreateCharacterC2S
{
    @Accessor("raceName")
    String su$getRaceName();
}
