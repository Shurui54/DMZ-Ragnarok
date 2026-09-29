package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.shurui.shuruisutilities.ranks.RankManager;
import net.shurui.shuruisutilities.ranks.client.RankBadgeMetrics;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Makes chat rows tall enough for rank badges and centres each badge with 1px of bar above and below.
 *
 * A rank badge renders 11px tall in the ranks font, taller than a vanilla 9px chat line, so without help they
 * overlap neighbouring rows. su$fitRankBadges raises getLineHeight() (the bar/background height) to badge+2;
 * su$centerChatLine shifts each row's content so the badge lands dead-centre.
 *
 * geometry (default spacing): vanilla draws the background over [i4 - lineHeight, i4] and text at y = i4 - 8.
 * a ranks-font glyph's top is at drawY + (7 - ascent); the badge font uses ascent 8, so an 11px badge occupies
 * [drawY - 1, drawY + 10]. su$centerChatLine recovers i4 from y and re-seats the baseline so the badge centre
 * hits the bar centre.
 */
@Mixin(ChatComponent.class)
public class MixinChatComponent
{
    // 15px badge in a 17px bar: 1px of bar above and below. Raised from 11 when the badge art was
    // re-cut; the ranks font must stay at ASCENT 8 for the centring below to hold (see the generator).
    private static final int SU_BADGE_HEIGHT = RankBadgeMetrics.HEIGHT;

    // chat bar height: badge + 1px above + 1px below
    private static final int SU_MIN_LINE_HEIGHT = SU_BADGE_HEIGHT + 2;

    // ChatComponent's own vertical text offset (l1) at default line spacing
    private static final int SU_VANILLA_TEXT_Y = -8;

    @Shadow
    private int getLineHeight()
    {
        throw new AssertionError(); // replaced by the shadowed target
    }

    @Inject(method = "getLineHeight", at = @At("RETURN"), cancellable = true)
    private void su$fitRankBadges(CallbackInfoReturnable<Integer> cir)
    {
        if (cir.getReturnValueI() < SU_MIN_LINE_HEIGHT)
            cir.setReturnValue(SU_MIN_LINE_HEIGHT);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"))
    private int su$centerChatLine(GuiGraphics graphics, Font font, FormattedCharSequence text, int x, int y, int color)
    {
        // vanilla draws the bar over [i4 - lineHeight, i4] and passes y = i4 + l1 (l1 == SU_VANILLA_TEXT_Y at
        // default spacing). recover the bar and re-seat so the badge lands dead-centre.
        int lineHeight = getLineHeight();
        int i4 = y - SU_VANILLA_TEXT_Y; // bar bottom
        float barCenter = i4 - lineHeight / 2.0f;
        // badge (ascent 8) top is drawY - 1, centre drawY - 1 + BADGE/2. solve badge-centre == bar-centre for drawY.
        // Badge dead-centre in the bar; the text rides the same anchor and lands 4 above / 5 below,
        // which is as centred as an odd remainder allows.
        int drawY = RankBadgeMetrics.drawYForBarCentre(barCenter);
        return graphics.drawString(font, su$animateBadges(text), x, drawY, color);
    }

    // swap any ranks-font badge glyph for the rank's current animation frame as the line is re-emitted. chat
    // redraws every frame, so this animates a badge otherwise baked statically into the immutable chat text.
    private static FormattedCharSequence su$animateBadges(FormattedCharSequence line)
    {
        return sink -> line.accept((index, style, codepoint) ->
        {
            int cp = RankManager.FONT.equals(style.getFont())
                    ? RankManager.animatedCodepoint(codepoint, System.currentTimeMillis())
                    : codepoint;
            return sink.accept(index, style, cp);
        });
    }
}
