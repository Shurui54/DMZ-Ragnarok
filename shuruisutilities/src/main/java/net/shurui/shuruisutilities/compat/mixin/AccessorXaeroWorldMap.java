package net.shurui.shuruisutilities.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import xaero.map.gui.GuiMap;

// read-only access to the block coord Xaero's World Map tracks under the cursor (private mouseBlockPosX/Z/Dim),
// so the NPC-region right-drag (via ScreenEvents, not an inject) can turn a drag into world coords. only touches
// Xaero fields so remap=false is correct. in the non-required compat config, silently skipped without Xaero.
@Mixin(value = GuiMap.class, remap = false)
public interface AccessorXaeroWorldMap
{
    @Accessor("mouseBlockPosX")
    int su$mouseBlockPosX();

    @Accessor("mouseBlockPosZ")
    int su$mouseBlockPosZ();

    @Accessor("mouseBlockDim")
    ResourceKey<Level> su$mouseBlockDim();
}
