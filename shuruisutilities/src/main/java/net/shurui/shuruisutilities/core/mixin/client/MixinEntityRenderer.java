package net.shurui.shuruisutilities.core.mixin.client;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;


import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.shurui.shuruisutilities.ranks.client.RankBadgeMetrics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Ranked player name tags get the same treatment as chat and the tab list: a 13px background bar (11px badge +
 * 1px above/below) with the badge dead-centre.
 *
 * vanilla's name-tag background is a fixed 10px box baked into Font ([y-1, y+9]), too short for an 11px badge.
 * So for ranked players we take over renderNameTag: draw our own taller quad centred on the badge, then draw
 * the name (with badge) as vanilla but with the font's own background suppressed. Non-players and unranked fall
 * through to vanilla.
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer
{
    // 15px badge in a 17px bar: 1px of bar above and below. Raised from 11 when the badge art was
    // re-cut; the ranks font must stay at ASCENT 8 for the centring below to hold (see the generator).
    private static final int SU_BADGE_HEIGHT = RankBadgeMetrics.HEIGHT;
    // name-tag bar: badge + 1px above/below
    private static final int SU_BG_H = SU_BADGE_HEIGHT + 2;

    @Shadow @Final protected EntityRenderDispatcher entityRenderDispatcher;

    @Shadow public abstract Font getFont();

    @Inject(method = "renderNameTag", at = @At("HEAD"), cancellable = true)
    private void su$centerNameBadge(Entity entity, Component displayName, PoseStack poseStack,
            MultiBufferSource buffer, int packedLight, CallbackInfo ci)
    {
        if (!(entity instanceof Player player))
            return;
        // Same answer the badge handler used, so a disguise that swaps the badge in or out also swaps this layout.
        if (net.shurui.shuruisutilities.disguise.client.DisguiseIdentity.rankOf(player.getUUID()) == null)
            return; // no badge, let vanilla draw it
        // displayName already has the badge prepended: EntityRenderer fires RenderNameTagEvent (handled by
        // RankClientEvents) before calling this.

        double distSqr = this.entityRenderDispatcher.distanceToSqr(entity);
        if (!net.minecraftforge.client.ForgeHooksClient.isNameplateInRenderDistance(entity, distSqr))
        {
            ci.cancel();
            return;
        }

        ci.cancel();

        Font font = getFont();
        boolean solidPass = !entity.isDiscrete();
        float tagOffsetY = entity.getNameTagOffsetY();
        int textY = "deadmau5".equals(displayName.getString()) ? -10 : 0;

        poseStack.pushPose();
        poseStack.translate(0.0F, tagOffsetY, 0.0F);
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);
        Matrix4f matrix = poseStack.last().pose();

        int bgAlpha = (int) (Minecraft.getInstance().options.getBackgroundOpacity(0.25F) * 255.0F);
        float left = (float) (-font.width(displayName) / 2);
        float right = left + font.width(displayName);

        if (bgAlpha > 0)
        {
            // badge (ascent 8) top is textY - 1, 11px tall. centre a 13px bar on it: 1px above and below, so the
            // bar runs [textY - 2, textY + 11]. horizontal extent matches vanilla's name background [left-1, right+1].
            float pad = (SU_BG_H - SU_BADGE_HEIGHT) / 2.0F; // 1px
            float badgeTop = textY + RankBadgeMetrics.GLYPH_TOP_OFFSET;
            float yTop = badgeTop - pad;      // textY - 2
            float yBot = yTop + SU_BG_H;      // textY + 11
            float x0 = left - 1.0F;
            float x1 = right + 1.0F;
            VertexConsumer vc = buffer
                    .getBuffer(solidPass ? RenderType.textBackgroundSeeThrough() : RenderType.textBackground());
            vc.vertex(matrix, x0, yBot, 0.01F).color(0, 0, 0, bgAlpha).uv2(packedLight).endVertex();
            vc.vertex(matrix, x1, yBot, 0.01F).color(0, 0, 0, bgAlpha).uv2(packedLight).endVertex();
            vc.vertex(matrix, x1, yTop, 0.01F).color(0, 0, 0, bgAlpha).uv2(packedLight).endVertex();
            vc.vertex(matrix, x0, yTop, 0.01F).color(0, 0, 0, bgAlpha).uv2(packedLight).endVertex();
        }

        // draw text (with badge) as vanilla but with the font's own background suppressed (0), since we drew the
        // taller one above.
        font.drawInBatch(displayName, left, (float) textY, 553648127, false, matrix, buffer,
                solidPass ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, 0, packedLight);
        if (solidPass)
        {
            font.drawInBatch(displayName, left, (float) textY, -1, false, matrix, buffer, Font.DisplayMode.NORMAL,
                    0, packedLight);
        }

        poseStack.popPose();
    }
}
