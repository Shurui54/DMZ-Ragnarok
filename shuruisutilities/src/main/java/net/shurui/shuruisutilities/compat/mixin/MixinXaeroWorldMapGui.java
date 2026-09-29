package net.shurui.shuruisutilities.compat.mixin;

import java.util.ArrayList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.shurui.shuruisutilities.guilds.client.GuildClaimRightClickOption;
import net.shurui.shuruisutilities.guilds.client.MapCursorChunk;

import xaero.map.gui.GuiMap;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

/**
 * Guild claiming on Xaero's World Map: a "Claim chunk" entry in the right-click menu, and a shift plus right-click
 * DRAG that claims every chunk in the rectangle it covers. In the non-required compat config, so without Xaero none
 * of this loads.
 *
 * <h2>Why the drag is read from {@code mouseBlockPosX/Z}</h2>
 * Those are the world coordinates Xaero already keeps for whatever the cursor is over, updated every frame as the
 * map is panned and zoomed. Reading them means the drag follows the cursor across zoom changes for free, where
 * converting screen pixels back to world coordinates ourselves would need the camera position, both scales and the
 * dimension divider, and would be wrong the moment any of those moved.
 *
 * <h2>Why the mouse itself is handled elsewhere</h2>
 * This class only EXPOSES the cursor, through {@link MapCursorChunk}; the press and release live in
 * {@code GuildClaimDragEvents} on Forge's screen events. Injecting into the map's own {@code mouseClicked} is not
 * possible cleanly: those are a mod class's overrides of vanilla methods, named plainly in a deobfuscated dev run
 * and by their SRG name in production, with no mapping the annotation processor can resolve because the owner is a
 * mod class. The fields below are Xaero's OWN, so they shadow without any of that trouble.
 */
@Mixin(value = GuiMap.class, remap = false)
public abstract class MixinXaeroWorldMapGui implements MapCursorChunk
{
    @Shadow
    private int rightClickX;

    @Shadow
    private int rightClickZ;

    /** World coordinates under the cursor, kept by Xaero and refreshed as the map moves. */
    @Shadow
    private int mouseBlockPosX;

    @Shadow
    private int mouseBlockPosZ;

    @Inject(method = "getRightClickOptions", at = @At("RETURN"))
    private void su$addClaimOption(CallbackInfoReturnable<ArrayList<RightClickOption>> cir)
    {
        ArrayList<RightClickOption> options = cir.getReturnValue();
        if (options == null)
            return;
        // Guild claiming is private (Ragnarok Key): only offer it when the connected server reported guilds installed.
        if (!net.shurui.dev.sdu.api.ClientGate.feature(net.shurui.shuruisutilities.api.key.GuildHooks.FEATURE_ID))
            return;
        for (RightClickOption o : options)
            if (o instanceof GuildClaimRightClickOption)
                return; // already added
        options.add(new GuildClaimRightClickOption(rightClickX >> 4, rightClickZ >> 4,
                (IRightClickableElement) (Object) this));
    }

    @Override
    public int su$cursorChunkX()
    {
        return mouseBlockPosX >> 4;
    }

    @Override
    public int su$cursorChunkZ()
    {
        return mouseBlockPosZ >> 4;
    }
}
