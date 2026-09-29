package net.shurui.shuruisutilities.core.mixin.client;

import java.util.List;
import java.util.Locale;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import net.shurui.shuruisutilities.prestige.client.SuTpClient;

/**
 * Adds Shurui's Utilities' own TP-gain bonus to DragonMineZ's "TP Multiplier" tooltip in the character
 * (X) menu. SU multiplies earned TP on top of DMZ's own components (prestige + {@code su.tpgain}, applied
 * in {@code PrestigeEvents.onTpGain}), so DMZ's tooltip alone under-reports the real multiplier. This:
 * <ul>
 *   <li>appends an <b>SU TP Mult: x{n}</b> line to the tooltip breakdown, and</li>
 *   <li>folds the SU factor into the shown <b>Total</b> (and the on-screen "TP Multiplier: x{n}" label),
 *       both of which derive from DMZ's {@code Math.max(0, 1 + Σ)} total, so the displayed number matches
 *       the TP actually gained.</li>
 * </ul>
 *
 * <p>Both injectors are {@code require = 0}: DMZ is a hard dependency so the target class is always present,
 * but if a future DMZ build reshapes {@code renderTpMultiplierInfo} these degrade to a no-op instead of
 * crashing. The value comes from {@link SuTpClient} (synced by {@code PacketSuTpMult}).</p>
 */
@Mixin(targets = "com.dragonminez.client.gui.character.CharacterStatsScreen", remap = false)
public abstract class MixinDmzTpMultiplierTooltip
{
    /** Append the "SU TP Mult" line to the tooltip body list passed to DMZ's advanced-tooltip renderer. */
    @ModifyArg(
            method = "renderTpMultiplierInfo",
            at = @At(value = "INVOKE",
                    target = "Lcom/dragonminez/client/util/TextUtil;renderAdvancedTooltip(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIILnet/minecraft/network/chat/Component;Ljava/util/List;Ljava/util/List;I)V"),
            index = 7,
            remap = false,
            require = 0)
    private List<Component> su$appendTpLine(List<Component> body)
    {
        double mult = SuTpClient.multiplier();
        // Shown even at x1.00, mirroring DMZ's always-present General / Class / Progression lines.
        body.add(Component.translatable("gui.dmz_ragnarok.core.su_tp_mult",
                String.format(Locale.ROOT, "%.2f", mult)).withStyle(ChatFormatting.AQUA));
        return body;
    }

    /**
     * Fold the SU factor into DMZ's total. DMZ computes {@code total = Math.max(0.0, 1.0 + Σ(componentᵢ-1))};
     * both the tooltip "Total" line and the on-screen label read that value, so multiplying the
     * {@code Math.max} result by the SU factor updates both. The slice pins this to the total's
     * {@code Math.max} (right after the Progression line), not any other max in the method.
     */
    @Redirect(
            method = "renderTpMultiplierInfo",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(DD)D"),
            slice = @Slice(from = @At(value = "CONSTANT",
                    args = "stringValue=gui.dragonminez.character_stats.tp_multiplier.tooltip.progression")),
            remap = false,
            require = 0)
    private double su$foldTotal(double a, double b)
    {
        return Math.max(a, b) * SuTpClient.multiplier();
    }
}
