package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.dragonminez.client.gui.radial.nodes.KiWeaponNode;

/**
 * Reads the weapon type off one of DragonMineZ's own wheel entries.
 *
 * <p>Needed only to tell DMZ's generated entries apart from ours, so the ones it generates for OUR types can be
 * dropped before the wheel is drawn. The field is private and there is no getter, and matching on the label text
 * instead would break the moment DMZ changes its translation key.
 */
@Mixin(value = KiWeaponNode.class, remap = false)
public interface AccessorKiWeaponNode
{
    @Accessor(value = "type", remap = false)
    String su$getType();
}
