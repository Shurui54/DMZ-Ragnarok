package net.shurui.dev.shuruis_dmz_dungeons.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// draws the outline boxes for /rg dungeon highlight <radius> so admins can find the disguised spawners.
// blocks can't be given a glow effect, so we just draw the outlines ourselves. client-side, no world change.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_dungeons", value = Dist.CLIENT)
public final class SpawnerHighlightRenderer {

    // pos -> wall-clock expiry in ms
    private static final Map<BlockPos, Long> ACTIVE = new HashMap<>();

    private SpawnerHighlightRenderer() {
    }

    // called from the S2C packet. durationTicks at 20 tps -> ms (x50).
    public static void highlight(List<BlockPos> positions, int durationTicks) {
        long expiry = System.currentTimeMillis() + Math.max(1, durationTicks) * 50L;
        for (BlockPos p : positions) {
            ACTIVE.put(p.immutable(), expiry);
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || ACTIVE.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        ACTIVE.entrySet().removeIf(e -> e.getValue() < now);
        if (ACTIVE.isEmpty()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Camera cam = event.getCamera();
        Vec3 camPos = cam.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        var consumer = buffer.getBuffer(RenderType.lines());

        // gold pulse, reads as a highlight against most builds
        float pulse = 0.6f + 0.4f * (float) Math.sin(now / 200.0);
        float r = 0.96f, g = 0.88f, b = 0.30f, a = Math.max(0.3f, pulse);

        for (BlockPos p : ACTIVE.keySet()) {
            AABB box = new AABB(p).inflate(0.02).move(-camPos.x, -camPos.y, -camPos.z);
            LevelRenderer.renderLineBox(pose, consumer, box, r, g, b, a);
        }
        buffer.endBatch(RenderType.lines());
    }
}
