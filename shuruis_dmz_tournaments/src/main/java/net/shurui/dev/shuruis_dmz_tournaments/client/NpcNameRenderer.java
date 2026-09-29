package net.shurui.dev.shuruis_dmz_tournaments.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Word-wraps long floating names above non-player entities into several lines instead of one cut-off
 * line (words never split). Short names stay vanilla. Fixes long tournament-NPC names overflowing.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_tournaments", value = Dist.CLIENT)
public final class NpcNameRenderer {
    private static final int MAX_WIDTH = 60; // gui px before wrapping

    private NpcNameRenderer() {}

    @SubscribeEvent
    public static void onRenderName(RenderNameTagEvent event) {
        Entity entity = event.getEntity();
        Component content = event.getContent();
        if (entity instanceof Player || content == null || content.getString().isEmpty()) return;
        // Only entities explicitly set to show a name (our NPCs use setCustomNameVisible). Otherwise we
        // force a floating name onto every non-player entity wider than MAX_WIDTH, which in a modpack is
        // most mobs and dropped items.
        if (!entity.isCustomNameVisible()) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        if (font.width(content) <= MAX_WIDTH) return; // short enough: leave vanilla rendering alone

        List<FormattedCharSequence> lines = font.split(content, MAX_WIDTH); // wraps on word boundaries
        if (lines.size() <= 1) return;

        event.setResult(Event.Result.DENY); // we render it ourselves

        var dispatcher = mc.getEntityRenderDispatcher();
        if (dispatcher.distanceToSqr(entity) > 4096.0) return;

        boolean flag = !entity.isDiscrete();
        var poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(0.0F, entity.getBbHeight() + 0.5F, 0.0F);
        poseStack.mulPose(dispatcher.cameraOrientation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);
        Matrix4f matrix = poseStack.last().pose();
        int bg = (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
        int lineHeight = font.lineHeight + 1;

        for (int i = 0; i < lines.size(); i++) {
            FormattedCharSequence line = lines.get(i);
            float ly = (i - (lines.size() - 1)) * (float) lineHeight; // stack upward; last line at the head baseline
            float lx = -font.width(line) / 2.0F;
            font.drawInBatch(line, lx, ly, 553648127, false, matrix, event.getMultiBufferSource(),
                    flag ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, bg, event.getPackedLight());
            if (flag) {
                font.drawInBatch(line, lx, ly, -1, false, matrix, event.getMultiBufferSource(),
                        Font.DisplayMode.NORMAL, 0, event.getPackedLight());
            }
        }
        poseStack.popPose();
    }
}
