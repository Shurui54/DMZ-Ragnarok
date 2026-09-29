package net.shurui.shuruisutilities.racing.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.dev.sdu.api.ClientGate;
import net.shurui.shuruisutilities.racing.item.TrackWandItem;
import net.shurui.shuruisutilities.racing.track.TrackDef;
import net.shurui.shuruisutilities.racing.track.TrackGeometry;
import net.shurui.shuruisutilities.racing.track.TrackNode;

/**
 * Draws the live track-editor preview in the world: centreline white, road edges yellow, gates cyan (the start gate
 * white, to read as a start line), branches magenta, node boxes with their index labels, boost pads and item
 * spawners. Client-only, and drawn ONLY when {@link ClientGate#feature "racing"} is set AND the local player holds a
 * BOUND track wand, so it never appears for a plain player or on a keyless server. The preview track is pushed by the
 * server (packet 118) into {@link RaceClientState}; geometry is derived here with {@link TrackGeometry} (common), so
 * it matches the server exactly.
 *
 * <p>Explicit {@code modid = "dmz_ragnarok"} as the split requires; Forge bus (a world-render event), client dist.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrackPreviewRenderer
{
    private TrackPreviewRenderer() {}

    /** Lift all preview geometry slightly above the surface so it does not z-fight the built road. */
    private static final double LIFT = 0.2;

    /** Ground-ribbon half-widths (blocks): the centreline reads boldest, edges thinner, a gate widest across. */
    private static final double CENTRE_HALF = 0.18;
    private static final double EDGE_HALF = 0.12;
    private static final double GATE_HALF = 0.30;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event)
    {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;
        if (!ClientGate.feature("racing"))
            return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !holdingBoundWand(player))
            return;

        TrackDef def = RaceClientState.preview();
        if (def == null || def.nodes.isEmpty())
            return;

        TrackGeometry geo = TrackGeometry.of(def);

        Camera cam = event.getCamera();
        Vec3 camPos = cam.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        pose.pushPose();
        pose.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f m = pose.last().pose();
        Matrix3f nm = pose.last().normal();

        // R11: draw the centreline, edges, branches and gates as FLAT GROUND RIBBONS (a fixed-width horizontal strip),
        // not 1px GL lines. A hairline read as almost nothing from any height; a ribbon holds its on-ground width at any
        // distance, so the track shape is clear when the editor flies above it. Node / pad / spawner markers stay boxes.
        var ribbons = buffer.getBuffer(RenderType.debugQuads());

        // Centreline (white) and edges (yellow) for the main route.
        ribbon(ribbons, m, samplePositions(geo.mainSamples()), CENTRE_HALF, 1f, 1f, 1f, 0.85f);
        ribbon(ribbons, m, edgePositions(geo.mainSamples(), true), EDGE_HALF, 1f, 1f, 0.15f, 0.8f);
        ribbon(ribbons, m, edgePositions(geo.mainSamples(), false), EDGE_HALF, 1f, 1f, 0.15f, 0.8f);

        // Branches (magenta), centreline and both edges.
        for (List<TrackGeometry.Sample> branch : geo.branchSamples())
        {
            ribbon(ribbons, m, samplePositions(branch), CENTRE_HALF, 1f, 0.2f, 1f, 0.85f);
            ribbon(ribbons, m, edgePositions(branch, true), EDGE_HALF, 0.8f, 0.2f, 0.8f, 0.8f);
            ribbon(ribbons, m, edgePositions(branch, false), EDGE_HALF, 0.8f, 0.2f, 0.8f, 0.8f);
        }

        // Gates: cyan, the start gate white. A single wide strip from left edge to right edge.
        for (TrackGeometry.Gate g : geo.gates())
        {
            List<Vec3> gate = List.of(lift(g.left), lift(g.right));
            if (g.start)
                ribbon(ribbons, m, gate, GATE_HALF, 1f, 1f, 1f, 0.9f);
            else
                ribbon(ribbons, m, gate, GATE_HALF, 0.1f, 1f, 1f, 0.85f);
        }

        buffer.endBatch(RenderType.debugQuads());

        var lines = buffer.getBuffer(RenderType.lines());

        // Node boxes: main light-gray, branch magenta.
        for (TrackNode n : def.nodes)
        {
            AABB box = new AABB(n.x - 0.4, n.y + LIFT - 0.4, n.z - 0.4, n.x + 0.4, n.y + LIFT + 0.4, n.z + 0.4);
            if (n.main)
                LevelRenderer.renderLineBox(pose, lines, box, 0.85f, 0.85f, 0.9f, 1f);
            else
                LevelRenderer.renderLineBox(pose, lines, box, 1f, 0.2f, 1f, 1f);
        }

        // Boost pads (orange) and item spawners (gold), as small boxes.
        for (TrackDef.BoostPad p : def.boostPads)
        {
            AABB box = new AABB(p.x - 0.45, p.y + LIFT, p.z - 0.45, p.x + 0.45, p.y + LIFT + 0.1, p.z + 0.45);
            LevelRenderer.renderLineBox(pose, lines, box, 1f, 0.55f, 0.1f, 1f);
        }
        for (TrackDef.ItemPoint p : def.itemPoints)
        {
            AABB box = new AABB(p.x - 0.4, p.y + LIFT, p.z - 0.4, p.x + 0.4, p.y + LIFT + 0.9, p.z + 0.4);
            LevelRenderer.renderLineBox(pose, lines, box, 1f, 0.85f, 0.2f, 1f);
        }

        buffer.endBatch(RenderType.lines());

        // Node index labels, billboarded to the camera.
        drawNodeLabels(mc, pose, buffer, cam, def);

        pose.popPose();
    }

    private static boolean holdingBoundWand(LocalPlayer player)
    {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        return TrackWandItem.isBoundWand(main) || TrackWandItem.isBoundWand(off);
    }

    private static Vec3 lift(Vec3 v)
    {
        return new Vec3(v.x, v.y + LIFT, v.z);
    }

    private static List<Vec3> samplePositions(List<TrackGeometry.Sample> samples)
    {
        return samples.stream().map(s -> lift(s.pos)).toList();
    }

    private static List<Vec3> edgePositions(List<TrackGeometry.Sample> samples, boolean left)
    {
        return samples.stream().map(s -> lift(left ? s.leftEdge : s.rightEdge)).toList();
    }

    // Draw a polyline as a flat horizontal ribbon: for each segment, a quad of the given half-width centred on the
    // line, lying in the world XZ plane at the points' own y. Camera-independent, so it holds its on-ground width from
    // any height. RenderType.debugQuads is POSITION_COLOR translucent, depth-tested, so a ribbon is occluded by real
    // terrain (correct) but reads boldly over open road.
    private static void ribbon(com.mojang.blaze3d.vertex.VertexConsumer c, Matrix4f m, List<Vec3> pts, double half,
                               float r, float g, float b, float a)
    {
        for (int i = 1; i < pts.size(); i++)
        {
            Vec3 p0 = pts.get(i - 1);
            Vec3 p1 = pts.get(i);
            double dx = p1.x - p0.x;
            double dz = p1.z - p0.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-6)
                continue;
            // horizontal perpendicular to the segment
            double px = -dz / len * half;
            double pz = dx / len * half;
            vtx(c, m, p0.x - px, p0.y, p0.z - pz, r, g, b, a);
            vtx(c, m, p0.x + px, p0.y, p0.z + pz, r, g, b, a);
            vtx(c, m, p1.x + px, p1.y, p1.z + pz, r, g, b, a);
            vtx(c, m, p1.x - px, p1.y, p1.z - pz, r, g, b, a);
        }
    }

    private static void vtx(com.mojang.blaze3d.vertex.VertexConsumer c, Matrix4f m, double x, double y, double z,
                            float r, float g, float b, float a)
    {
        c.vertex(m, (float) x, (float) y, (float) z).color(r, g, b, a).endVertex();
    }

    private static void drawNodeLabels(Minecraft mc, PoseStack pose, MultiBufferSource.BufferSource buffer,
                                       Camera cam, TrackDef def)
    {
        Font font = mc.font;
        for (TrackNode n : def.nodes)
        {
            pose.pushPose();
            pose.translate(n.x, n.y + LIFT + 0.6, n.z);
            pose.mulPose(cam.rotation());
            pose.scale(-0.025f, -0.025f, 0.025f);
            String text = String.valueOf(n.id);
            float w = -font.width(text) / 2f;
            font.drawInBatch(Component.literal(text), w, 0, 0xFFFFFFFF, false, pose.last().pose(), buffer,
                    Font.DisplayMode.NORMAL, 0, 0xF000F0);
            pose.popPose();
        }
        buffer.endBatch();
    }
}
