package net.shurui.shuruisutilities.client.space;

import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import net.shurui.dev.sdu.client.ClientWaypoints;
import net.shurui.dev.sdu.waypoint.Waypoint;
import net.shurui.shuruisutilities.client.autopilot.SpaceAutopilotClient;
import net.shurui.shuruisutilities.client.space.starmap.StarMapData;
import net.shurui.shuruisutilities.space.FixedBody;
import net.shurui.shuruisutilities.space.GeneratedPlanets;
import net.shurui.shuruisutilities.space.GeneratedSystems;
import net.shurui.shuruisutilities.space.OrbitClock;
import net.shurui.shuruisutilities.space.PacketSpaceCourseSync;
import net.shurui.shuruisutilities.space.PlanetPositions;
import net.shurui.shuruisutilities.space.SpaceDimension;
import net.shurui.shuruisutilities.space.SpaceLayout;

/**
 * The in-world TARGET PIP in space (owner, 2026-10-22: "an in world pip like quests to follow that is the planet, not just
 * the compass, but along side it"). A marker drawn ON the target planet itself, with its name and distance, on top of
 * everything (it is part of the HUD, so no body, sun or block hides it), and pinned to the screen edge with an arrow when
 * the planet is off screen or behind. The compass bar is untouched and still shows the course.
 *
 * <p>WHAT IT POINTS AT, in order: the player's space COURSE (the star map / pod menu destination, which is also the
 * autopilot's target), followed at its LIVE orbital position from the same synced layout the renderer draws, so the pip
 * sits on the drawn planet as it orbits (the compass marker holds the position from the moment the course was set); else
 * an active autopilot's synced target; else the waypoint the compass is showing (the nearest one in space).
 *
 * <p>CHEAP: one target per frame. The level pass copies the view and projection matrices (two 4x4 copies), the HUD pass
 * projects one point and draws a few quads and two strings. The course key is resolved to its system planet once per key
 * change, never per frame. Private like the compass it sits beside (OWNER-SPECS section 4): drawn only when the connected
 * server holds the key.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SpaceTargetPip
{
    private SpaceTargetPip()
    {
    }

    // the compass's course colour (SpaceCompassBridge), so the pip and the compass marker read as one thing.
    private static final int COLOR = 0xFF66E0FF;
    private static final int TEXT_SHADOW_BG = 0x90000000;
    // how far in from the screen edge a clamped pip sits, gui pixels.
    private static final int EDGE_MARGIN = 26;
    // the diamond's half size, gui pixels.
    private static final int DIAMOND = 5;

    // view and projection of the frame being drawn, captured in the level pass for the HUD pass. Render thread only.
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static Vec3 camera = Vec3.ZERO;
    private static boolean captured;
    private static final Vector4f SCRATCH = new Vector4f();

    // the resolved course target, cached per key so the per-frame cost is one orbit evaluation.
    private static String resolvedKey = null;
    private static GeneratedSystems.System resolvedSystem;
    private static GeneratedSystems.SystemPlanet resolvedPlanet;
    private static Component resolvedName;
    private static float resolvedRadius;
    private static boolean resolvedFixed;
    private static Vec3 legacyPos;
    private static long legacyLookupAt;
    private static long retryAt;
    private static final double LEGACY_LOOKUP_RANGE = 8000.0;

    // one frame's target, filled by resolveTarget. Render thread only.
    private static double targetX;
    private static double targetY;
    private static double targetZ;
    private static float targetRadius;
    private static Component targetName;

    // test hook for the visual harness: force the pip onto a body key regardless of the synced course.
    private static volatile String forcedKey = null;

    /** Visual test only: point the pip at {@code key} (null restores the normal course lookup). */
    public static void forceTarget(String key)
    {
        forcedKey = key;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event)
    {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY)
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !SpaceDimension.isSpace(mc.level))
        {
            captured = false;
            return;
        }
        VIEW.set(event.getPoseStack().last().pose());
        PROJECTION.set(event.getProjectionMatrix());
        camera = event.getCamera().getPosition();
        captured = true;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event)
    {
        PacketSpaceCourseSync.clearClient();
        resolvedKey = null;
        captured = false;
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event)
    {
        Minecraft mc = Minecraft.getInstance();
        if (!captured || mc.level == null || mc.player == null || mc.options.hideGui
                || !SpaceDimension.isSpace(mc.level))
        {
            return;
        }
        // private like the waypoint compass it sits beside (OWNER-SPECS section 4).
        if (!net.shurui.dev.sdu.api.ClientGate.key())
        {
            return;
        }
        if (!resolveTarget(mc))
        {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth();
        int h = g.guiHeight();

        double dx = targetX - camera.x;
        double dy = targetY - camera.y;
        double dz = targetZ - camera.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0E-3)
        {
            return;
        }
        // project the DIRECTION (64 blocks along it), not the far point: the bodies are drawn through a depth shell and
        // only the bearing matters, and a short vector keeps the float maths exact at any distance.
        double s = 64.0 / dist;
        Vector4f v = SCRATCH.set((float) (dx * s), (float) (dy * s), (float) (dz * s), 1.0F);
        VIEW.transform(v);
        PROJECTION.transform(v);
        boolean behind = v.w <= 1.0E-4F;
        double sx;
        double sy;
        boolean onScreen = false;
        if (!behind)
        {
            double nx = v.x / v.w;
            double ny = v.y / v.w;
            sx = (nx * 0.5 + 0.5) * w;
            sy = (0.5 - ny * 0.5) * h;
            onScreen = sx >= EDGE_MARGIN && sx <= w - EDGE_MARGIN && sy >= EDGE_MARGIN && sy <= h - EDGE_MARGIN;
        }
        else
        {
            sx = 0.0;
            sy = 0.0;
        }

        Font font = mc.font;
        double surface = Math.max(0.0, dist - targetRadius);
        Component distText = Component.translatable("hud.dmz_ragnarok.core.space_pip.distance", formatDistance(surface));
        // how far above or below the player the target sits, so the steep 3D layout reads at a glance.
        double rise = targetY - mc.player.getY();
        Component riseText = Math.abs(rise) < 64.0 ? null : Component.translatable(rise > 0
                ? "hud.dmz_ragnarok.core.space_pip.above" : "hud.dmz_ragnarok.core.space_pip.below",
                formatDistance(Math.abs(rise)));

        if (onScreen)
        {
            int px = (int) Math.round(sx);
            int py = (int) Math.round(sy);
            drawDiamond(g, px, py, 0.0F);
            drawLabel(g, font, targetName, px, py - DIAMOND - 12);
            drawLabel(g, font, distText, px, py + DIAMOND + 4);
            if (riseText != null)
            {
                drawLabel(g, font, riseText, px, py + DIAMOND + 15);
            }
            return;
        }

        // OFF SCREEN: pin to the edge along the screen-space direction to the target. Behind the camera the projected
        // point mirrors, so steer by the view-space bearing instead (the clip x/y keep their sign behind the eye).
        double ddx;
        double ddy;
        if (behind)
        {
            ddx = v.x;
            ddy = -v.y;
            if (Math.abs(ddx) < 1.0E-6 && Math.abs(ddy) < 1.0E-6)
            {
                ddy = 1.0;   // dead astern: point down.
            }
        }
        else
        {
            ddx = sx - w * 0.5;
            ddy = sy - h * 0.5;
        }
        double halfW = w * 0.5 - EDGE_MARGIN;
        double halfH = h * 0.5 - EDGE_MARGIN;
        double t = Math.min(halfW / Math.max(1.0E-6, Math.abs(ddx)), halfH / Math.max(1.0E-6, Math.abs(ddy)));
        int px = (int) Math.round(w * 0.5 + ddx * t);
        int py = (int) Math.round(h * 0.5 + ddy * t);
        float angle = (float) Math.toDegrees(Math.atan2(ddy, ddx));
        drawDiamond(g, px, py, 0.0F);
        drawArrow(g, px, py, angle);
        // labels sit on the inward side of the pip so they never run off the screen.
        int labelY = py > h / 2 ? py - DIAMOND - 24 : py + DIAMOND + 4;
        int labelX = px;
        drawLabel(g, font, targetName, labelX, labelY);
        drawLabel(g, font, riseText != null ? Component.empty().append(distText).append("  ").append(riseText)
                : distText, labelX, labelY + 11);
    }

    // ===== target resolution =====

    // fill targetX/Y/Z, targetRadius, targetName for this frame; false when there is nothing to point at.
    private static boolean resolveTarget(Minecraft mc)
    {
        String key = forcedKey != null ? forcedKey : PacketSpaceCourseSync.clientKey();
        if (key != null && !key.isEmpty() && resolveCourse(key))
        {
            return true;
        }
        if (SpaceAutopilotClient.matches(mc.level.dimension().location()))
        {
            Vec3 t = SpaceAutopilotClient.target();
            targetX = t.x;
            targetY = t.y;
            targetZ = t.z;
            targetRadius = 0.0F;
            targetName = Component.translatable("hud.dmz_ragnarok.core.space_pip.autopilot");
            return true;
        }
        // the compass's own target: the nearest waypoint it shows in this dimension.
        String dim = mc.level.dimension().location().toString();
        Waypoint best = null;
        double bestSq = Double.MAX_VALUE;
        for (Waypoint wp : ClientWaypoints.all())
        {
            if (wp.isNotice() || wp.travel || !dim.equals(wp.dim))
            {
                continue;
            }
            double d = mc.player.distanceToSqr(wp.x, wp.y, wp.z);
            if (d < bestSq)
            {
                bestSq = d;
                best = wp;
            }
        }
        if (best == null)
        {
            return false;
        }
        targetX = best.x;
        targetY = best.y;
        targetZ = best.z;
        targetRadius = 0.0F;
        targetName = Component.literal(best.name);
        return true;
    }

    // a course key (a fixed main planet or a sugen: planet) to its live position. Resolved to its body once per key.
    private static boolean resolveCourse(String key)
    {
        if (!key.equals(resolvedKey))
        {
            resolvedKey = key;
            resolvedSystem = null;
            resolvedPlanet = null;
            resolvedFixed = false;
            resolvedName = null;
            legacyPos = null;
            legacyLookupAt = 0L;
            for (FixedBody fb : SpaceLayout.fixedBodies(null))
            {
                if (fb.key.equals(key))
                {
                    resolvedFixed = true;
                    resolvedRadius = fb.radius;
                    resolvedName = StarMapData.fixedBodyName(key);
                    break;
                }
            }
            if (!resolvedFixed && key.startsWith(GeneratedPlanets.ID_PREFIX))
            {
                GeneratedSystems.System sys = GeneratedSystems.systemForPlanetId(null, key);
                if (sys != null)
                {
                    for (GeneratedSystems.SystemPlanet p : sys.planets)
                    {
                        if (p.id.equals(key))
                        {
                            resolvedSystem = sys;
                            resolvedPlanet = p;
                            resolvedRadius = p.radius;
                            break;
                        }
                    }
                }
                resolvedName = Component.literal(GeneratedPlanets.nameFor(key));
            }
        }
        long epoch = OrbitClock.epochMillis();
        if (resolvedFixed)
        {
            Vec3 p = PlanetPositions.orbitPositionAt(key, epoch);
            setTarget(p, resolvedRadius, resolvedName);
            return true;
        }
        if (resolvedPlanet != null)
        {
            setTarget(resolvedSystem.planetPositionAt(resolvedPlanet, epoch), resolvedRadius, resolvedName);
            return true;
        }
        if (key.startsWith(GeneratedPlanets.ID_PREFIX))
        {
            // a legacy (old-scheme, unmoving) planet: looked up near the player through the synced claims, at most once a
            // second (a cell walk), and kept once found since it never moves.
            long now = System.currentTimeMillis();
            if (legacyPos == null && now - legacyLookupAt > 1000L)
            {
                legacyLookupAt = now;
                for (GeneratedPlanets.Generated g : GeneratedPlanets.generatedNear(null, camera, LEGACY_LOOKUP_RANGE))
                {
                    if (g.id.equals(key))
                    {
                        legacyPos = g.position;
                        resolvedRadius = g.radius;
                        break;
                    }
                }
            }
            if (legacyPos != null)
            {
                setTarget(legacyPos, resolvedRadius, resolvedName);
                return true;
            }
        }
        // unresolved (the layout sync may not have arrived yet): try the whole lookup again in a second.
        long now = System.currentTimeMillis();
        if (now - retryAt > 1000L)
        {
            retryAt = now;
            if (legacyPos == null)
            {
                resolvedKey = null;
            }
        }
        return false;
    }

    private static void setTarget(Vec3 p, float radius, Component name)
    {
        targetX = p.x;
        targetY = p.y;
        targetZ = p.z;
        targetRadius = radius;
        targetName = name;
    }

    // ===== drawing =====

    private static void drawDiamond(GuiGraphics g, int x, int y, float extraDeg)
    {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().mulPose(Axis.ZP.rotationDegrees(45.0F + extraDeg));
        g.fill(-DIAMOND - 1, -DIAMOND - 1, DIAMOND + 1, DIAMOND + 1, 0xC0000000);
        g.fill(-DIAMOND, -DIAMOND, DIAMOND, DIAMOND, COLOR);
        g.fill(-DIAMOND + 2, -DIAMOND + 2, DIAMOND - 2, DIAMOND - 2, 0xFF1C3A44);
        g.pose().popPose();
    }

    // a short chevron just outside the diamond, pointing toward the off-screen target.
    private static void drawArrow(GuiGraphics g, int x, int y, float angleDeg)
    {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().mulPose(Axis.ZP.rotationDegrees(angleDeg));
        for (int i = 0; i < 5; ++i)
        {
            int ax = DIAMOND + 4 + i;
            g.fill(ax, -(4 - i), ax + 1, (4 - i) + 1, COLOR);
        }
        g.pose().popPose();
    }

    private static void drawLabel(GuiGraphics g, Font font, Component text, int cx, int y)
    {
        if (text == null)
        {
            return;
        }
        int tw = font.width(text);
        // kept wholly on screen, so a pip pinned to an edge never cuts its own name off.
        int x = Math.max(3, Math.min(g.guiWidth() - tw - 3, cx - tw / 2));
        g.fill(x - 2, y - 1, x + tw + 2, y + 9, TEXT_SHADOW_BG);
        g.drawString(font, text, x, y, COLOR, false);
    }

    // 950 -> "950", 12345 -> "12.3k".
    private static String formatDistance(double blocks)
    {
        if (blocks < 10000.0)
        {
            return String.valueOf((int) Math.round(blocks));
        }
        return String.format(java.util.Locale.ROOT, "%.1fk", blocks / 1000.0);
    }
}
