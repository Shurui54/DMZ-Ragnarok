package net.shurui.shuruisutilities.core.mixin.client;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.client.gui.radial.RadialNode;
import com.dragonminez.client.gui.radial.nodes.ActionsNode;
import com.dragonminez.common.stats.StatsData;

/**
 * Adds the senzu bean bag to DMZ's "Actions" sub-ring of the radial (utility) menu.
 *
 * <p>Appended to whatever DMZ built rather than replacing the list, so every action DMZ offers stays where it was and
 * a future DMZ version that adds more of them needs no change here. The Actions ring is a SUB ring, not the fixed
 * eight-slot base ring, so appending one entry never grows the base wheel.
 *
 * <p>The node hides itself for anyone with no bag equipped ({@code SenzuRadialNode.visible}) and greys itself when the
 * bag is empty, so the ring is unchanged for a player who is not carrying beans. Selecting it only sends a request; the
 * server decides.
 *
 * <p>{@code require = 0}: a DMZ-targeting mixin must never harden the build against a DMZ version that moved this, and
 * losing the menu entry is a cosmetic loss (the bag still opens and pulls from its keybinds), not a broken feature.
 */
@Mixin(value = ActionsNode.class, remap = false)
public abstract class MixinDmzActionsNode
{
    @Inject(method = "buildChildren", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void su$addSenzuBag(StatsData stats, CallbackInfoReturnable<List<RadialNode>> cir)
    {
        try
        {
            List<RadialNode> original = cir.getReturnValue();
            List<RadialNode> rebuilt = new ArrayList<>(original == null ? List.of() : original);
            rebuilt.add(new net.shurui.shuruisutilities.client.radial.SenzuRadialNode());
            cir.setReturnValue(rebuilt);
        }
        catch (Throwable ignored)
        {
            // Never let an extra menu entry break the menu itself; DMZ's own list stands.
        }
    }
}
