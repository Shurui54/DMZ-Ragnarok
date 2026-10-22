package net.shurui.dev.sdu.mixin.hud;

import java.util.Arrays;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.client.gui.hud.layout.HudElement;

import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudSlot;

/**
 * Appends one DMZ {@code HudElement} per {@link RagnarokHudSlot} so the suite's HUD elements ride DMZ's layout,
 * editor and {@code hud_layout.json} exactly like DMZ's own.
 *
 * <p>Done at the TAIL of the enum's static initialiser, before anything can call {@code values()}: EnumMap,
 * {@code valueOf} and DMZ's own loops all read the extended {@code $VALUES}. DMZ switches over HudElement only in
 * {@code HudLayout.baseSize} and {@code defaultPlacement}, which {@code DmzHudLayoutMixin} answers for our constants
 * before the switch runs.
 */
@Mixin(value = HudElement.class, remap = false)
public abstract class DmzHudElementMixin
{
    @Shadow
    @Final
    @Mutable
    private static HudElement[] $VALUES;

    @Inject(method = "<clinit>", at = @At("TAIL"), require = 0)
    private static void sdu$appendRagnarokElements(CallbackInfo ci)
    {
        RagnarokHudSlot[] slots = RagnarokHudSlot.values();
        int base = $VALUES.length;
        HudElement[] extended = Arrays.copyOf($VALUES, base + slots.length);
        for (int i = 0; i < slots.length; i++)
            extended[base + i] = DmzHudElementInvoker.sdu$create(slots[i].enumName(), base + i, slots[i].id(), false);
        $VALUES = extended;
        RagnarokHudSlot.markFirstOrdinal(base);
    }

    // our extra slots share DMZ's "extras" layout group (and editor page); the main ones belong to the Ragnarok style
    @Inject(method = "isExtra", at = @At("HEAD"), cancellable = true, require = 0)
    private void sdu$ragnarokExtra(CallbackInfoReturnable<Boolean> cir)
    {
        RagnarokHudSlot slot = RagnarokHudSlot.byDmzOrdinal(((Enum<?>) (Object) this).ordinal());
        if (slot != null)
            cir.setReturnValue(slot.extra());
    }
}
