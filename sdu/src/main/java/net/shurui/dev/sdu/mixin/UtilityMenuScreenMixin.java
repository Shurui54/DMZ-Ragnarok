package net.shurui.dev.sdu.mixin;

import com.dragonminez.client.gui.radial.RadialNode;
import com.dragonminez.client.gui.radial.nodes.MoreFormsNode;
import net.shurui.dev.sdu.client.gui.radial.SduExtraFormsCategory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// Replaces DMZ's built-in "Extra Forms" radial node (MoreFormsNode, fixed base slot 1) with sdu's
// SduExtraFormsCategory, which adds an intermediate form-TYPE tier the base game lacks.
//
// buildBaseNodes() builds a FIXED 8-slot list and the whole ring/hover math assumes exactly 8, so we REPLACE
// slot 1 in place (never grow/shrink). Inject at TAIL after all eight slots are populated, verify slot 1 is
// really the MoreFormsNode (so a DMZ reshuffle can't make us clobber the wrong slot), then set(1, ...).
// Fail-open: any mismatch leaves DMZ's node untouched.
//
// require=0 + remap=false per the sdu radial-mixin convention. Client-only.
@Mixin(value = com.dragonminez.client.gui.UtilityMenuScreen.class, remap = false)
public abstract class UtilityMenuScreenMixin {

    @Shadow
    @Final
    private List<RadialNode> baseNodes;

    @Inject(method = "buildBaseNodes", at = @At("TAIL"), require = 0, remap = false)
    private void sdu$replaceExtraForms(CallbackInfo ci) {
        try {
            if (this.baseNodes != null
                    && this.baseNodes.size() > 1
                    && this.baseNodes.get(1) instanceof MoreFormsNode) {
                this.baseNodes.set(1, new SduExtraFormsCategory());
            }
        } catch (Throwable ignored) {
            // fail open: leave DMZ's MoreFormsNode in place
        }
    }
}
