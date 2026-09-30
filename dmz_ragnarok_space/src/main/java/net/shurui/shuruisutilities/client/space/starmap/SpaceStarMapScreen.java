package net.shurui.shuruisutilities.client.space.starmap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import org.joml.Matrix4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import net.shurui.dev.sdu.client.gui.DmzTextureButton;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.client.gui.theme.ThemeRender;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.space.GeneratedPlanets;
import net.shurui.shuruisutilities.space.GeneratedSystems;
import net.shurui.shuruisutilities.space.OrbitClock;
import net.shurui.shuruisutilities.space.PacketStarMapCourse;
import net.shurui.shuruisutilities.space.PlanetPositions;
import net.shurui.shuruisutilities.space.PlanetRings;

/**
 * The SPACE STAR MAP. A GuiTheme-styled atlas of the solar system and the generated star systems, shown when a player
 * opens Xaero's World Map while in space (see {@code compat.xaero.XaeroWorldMapInterceptor}) or presses the star map
 * keybind ({@link StarMapKeybind}). Sun at centre, orbit rings, a pannable/zoomable body chart, a destination side panel
 * with an engage-autopilot button, all our own code and theme art.
 *
 * <h3>Performance model (why this was rewritten)</h3>
 * The universe holds hundreds of star systems, each with 5 to 9 planets. The earlier screen materialised EVERY system's
 * planets at once and, past a zoom threshold, drew all of them plus a ring per planet using a per-pixel line/circle
 * routine (each pixel a separate {@code fill} quad). Clicking a system, or zooming past the threshold, then queued tens
 * of thousands of draw calls per frame, which is the lag the owner reported. This screen instead:
 * <ul>
 *   <li>charts only the system SUNS universe-wide (from the cached deterministic grid); a system's PLANETS are expanded
 *       ON DEMAND, only for the selected system and (when zoomed in) the few systems inside the viewport, capped;</li>
 *   <li>draws every disc, ring and line through BATCHED vertex buffers (one draw per primitive class), with rings culled
 *       to the viewport and their segment count scaled to their on-screen radius;</li>
 *   <li>recomputes the expansion at most once per frame (a view/time signature guards the rebuild), so zoom and pan only
 *       change a transform.</li>
 * </ul>
 * Selecting a system also FRAMES it (centres and fits the zoom on its sun), so its planets read as orbiting THAT sun
 * rather than a tiny cluster lost in the whole-universe view.
 *
 * <p>Client-only and read-only: it draws from synced client state and, for the set-course button, sends a single
 * {@link PacketStarMapCourse} the SERVER validates. It changes no world data and needs no migration.
 */
public final class SpaceStarMapScreen extends Screen
{
    // colours: DMZ gold for the title/selection, muted for hints, plus our own map fills (a dark space field and faint
    // grid/ring lines) that read on the panel body.
    private static final int MAP_BG = 0xCC0B1220;
    private static final int MAP_BORDER = 0xFF2A3550;
    private static final int RING_LINE = 0x552E6FB0;
    private static final int GRID_LINE = 0x22335070;
    private static final int SELECT_LINE = 0x88F6E27A;
    private static final int CORONA_LINE = 0x66F6E27A;
    private static final int PLAYER_MARK = 0xFF5FE0E0;
    private static final int LABEL_COLOR = 0xFFD8E4F0;

    // altitude stem: a faint vertical cue showing a body's height above/below the main solar-system plane on the top-down
    // map. ALT_REF_Y is that plane (the sun and fixed planets sit here); ALT_SPAN_BLOCKS is the block spread mapped to a
    // full ALT_MAX_STEM-pixel stem, sized to the generated-system Y band so a high system draws a near-full up-stem.
    private static final int ALT_STEM_LINE = 0x559CC4E8;
    private static final int ALT_MAX_STEM = 10;
    private static final double ALT_REF_Y = 900.0;   // PlanetPositions.PLANET_Y, the main-system plane
    private static final double ALT_SPAN_BLOCKS = 700.0;

    private static final float MIN_ZOOM = 0.00008F;
    private static final float MAX_ZOOM = 0.6F;

    // LEVEL OF DETAIL. Zoomed OUT, a whole system reads as a single sun icon (its planets hidden, so the whole-universe
    // atlas stays legible and cheap); zoomed IN past this threshold, or with that system selected, the system's planets are
    // expanded on their orbit rings at the current time.
    private static final float PLANET_ZOOM_THRESHOLD = 0.02F;
    // Bounds on the on-demand expansion, so a dense field can never expand the whole universe: at most this many systems are
    // expanded at once (the selected one plus the nearest-to-centre inside the viewport) and this many planets total.
    private static final int MAX_EXPANDED_SYSTEMS = 12;
    private static final int MAX_EXPANDED_PLANETS = 130;
    // Bounds on drawing per frame: viewport culling removes most; these bound the rest on an enormous charted universe.
    private static final int MAX_DISCS_PER_FRAME = 1200;
    private static final int MAX_ICONS_PER_FRAME = 420;
    // margin (px) around the viewport within which a system sun still counts as "in view" for expansion and drawing.
    private static final int VIEW_MARGIN = 24;

    // ===== body icon art (the real solar-system pack textures, reused as small map icons) =====
    private static ResourceLocation packTex(String p)
    {
        return new ResourceLocation("dmz_ragnarok", "textures/solar_system_pack/" + p + ".png");
    }

    private static final ResourceLocation ICON_SUN = packTex("sun");
    private static final ResourceLocation ICON_SUN_GRAY = packTex("sun_grayscale");
    private static final ResourceLocation ICON_EARTH = packTex("earth");
    private static final ResourceLocation ICON_VEGETA = packTex("vegeta");
    // grayscale planet sheets, tinted per body, matching the pool the space renderer draws generated planets from.
    private static final ResourceLocation[] ICON_PLANET_POOL = {
            packTex("mercury_grayscale"), packTex("venus_grayscale"), packTex("mars_grayscale"),
            packTex("jupiter_grayscale"), packTex("neptune_grayscale")
    };

    // a face-region crop of each sheet, so an icon shows a clean planet/star surface rather than the cube-net layout. The
    // planet sheets are 64x64 with a box-UV net; a ~13px square near the centre is one face. The sun sheet is 32x64; its
    // north face samples the bottom-right quadrant.
    private static final float PLANET_CROP_U = 19F;
    private static final float PLANET_CROP_V = 19F;
    private static final int PLANET_CROP_W = 13;
    private static final int PLANET_CROP_H = 13;
    private static final int PLANET_TEX = 64;
    private static final float SUN_CROP_U = 16F;
    private static final float SUN_CROP_V = 32F;
    private static final int SUN_CROP_W = 16;
    private static final int SUN_CROP_H = 32;
    private static final int SUN_TEX_W = 32;
    private static final int SUN_TEX_H = 64;
    // average opaque luminance of the grayscale sheets, so a tint multiply is lifted back to its true colour rather than
    // darkened (same reasoning as the space renderer's sheet-mean lift, one representative value for icon size).
    private static final float SHEET_MEAN = 0.62F;

    // layout, filled in init()
    private int panelX, panelY, panelW, panelH;
    private int mapX, mapY, mapW, mapH;
    private int statsX, statsY, statsW, statsH;

    private double centreX;
    private double centreZ;
    private double zoom = 0.01;
    private boolean centred;
    private boolean dragging;
    private String selectedKey = "";
    private int ticks;

    // base bodies (the sun, fixed planets, super bodies, system SUNS, legacy planets); system planets are expanded on demand.
    private List<StarMapBody> bodies = new ArrayList<>();
    private Map<String, GeneratedSystems.System> systemsByKey = new java.util.HashMap<>();
    // the system planets expanded THIS frame (selected system plus in-view systems), rebuilt only when the view/time changes.
    private List<StarMapBody> shownPlanets = new ArrayList<>();
    private String shownSig = "";
    private Vec3 playerPos = Vec3.ZERO;

    private StarMapBody hovered;

    private DmzTextureButton courseButton;

    public SpaceStarMapScreen()
    {
        super(Component.translatable("gui.dmz_ragnarok.core.starmap.title"));
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    @Override
    protected void init()
    {
        int margin = 12;
        panelX = margin;
        panelY = margin;
        panelW = this.width - 2 * margin;
        panelH = this.height - 2 * margin;

        int headerBottom = panelY + GuiTheme.HEADER_HEIGHT + 2;
        int footerY = panelY + panelH - GuiTheme.FOOTER_BORDER_GAP - GuiTheme.BUTTON_HEIGHT;

        statsW = Math.min(160, Math.max(120, panelW / 4));
        mapX = panelX + 12;
        mapY = headerBottom + 4;
        statsX = panelX + panelW - 12 - statsW;
        statsY = mapY;
        mapW = statsX - 8 - mapX;
        mapH = footerY - 6 - mapY;
        statsH = mapH;

        refreshBodies();
        if (!centred)
        {
            recenter();
        }

        // footer buttons: Recenter (left), Set course / Engage autopilot (centre, toggles with the selection), Close (right).
        int bw = Math.min(150, (panelW - 40) / 3);
        int bh = GuiTheme.BUTTON_HEIGHT;
        int gap = 8;
        int totalW = bw * 3 + gap * 2;
        int bx = panelX + (panelW - totalW) / 2;
        int by = footerY;

        addRenderableWidget(new DmzTextureButton(bx, by, bw, bh,
                Component.translatable("gui.dmz_ragnarok.core.starmap.recenter"),
                GuiTheme.BUTTONS, 0, 0, 0, 0, 0, this::recenter));

        courseButton = new DmzTextureButton(bx + bw + gap, by, bw, bh,
                Component.translatable("gui.dmz_ragnarok.core.starmap.set_course"),
                GuiTheme.BUTTONS, 0, 0, 0, 0, 0, this::sendCourse);
        courseButton.commits();
        addRenderableWidget(courseButton);

        addRenderableWidget(new DmzTextureButton(bx + 2 * (bw + gap), by, bw, bh,
                Component.translatable("gui.dmz_ragnarok.core.btn.close"),
                GuiTheme.BUTTONS, 0, 0, 0, 0, 0, this::onClose));
    }

    private void refreshBodies()
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
        {
            playerPos = mc.player.position();
        }
        bodies = StarMapData.collect(playerPos);
        // index the systems by their sun key, so on-demand expansion can fetch a system's planets in O(1). allSystems is
        // cached (one bounded walk per layout version), so this is cheap.
        Map<String, GeneratedSystems.System> map = new java.util.HashMap<>();
        for (GeneratedSystems.System s : GeneratedSystems.allSystems(null))
        {
            map.put(s.starKey, s);
        }
        systemsByKey = map;
        shownSig = "";   // force a re-expand against the fresh set.
    }

    // rebuild the expanded-planet set for this frame, but only when the view (selection, zoom, centre) or the orbit time
    // has actually changed, so panning/zooming only changes a transform and orbits still advance smoothly.
    private void expandIfNeeded(long epoch)
    {
        String sig = selectedKey + "|" + Math.round(zoom * 1.0E7) + "|" + Math.round(centreX) + "|" + Math.round(centreZ)
                + "|" + (epoch / 250L);
        if (sig.equals(shownSig))
        {
            return;
        }
        shownSig = sig;

        Set<String> expand = new LinkedHashSet<>();
        String exp = expandedSystem();
        if (!exp.isEmpty())
        {
            expand.add(exp);   // the selected system is always expanded, even zoomed out, so selection works.
        }
        if (zoom >= PLANET_ZOOM_THRESHOLD)
        {
            // expand the systems whose sun is inside the viewport, nearest to the viewport centre first, capped.
            double vcx = mapX + mapW / 2.0;
            double vcy = mapY + mapH / 2.0;
            List<StarMapBody> suns = new ArrayList<>();
            for (StarMapBody b : bodies)
            {
                if (b.kind != StarMapBody.Kind.STAR)
                {
                    continue;
                }
                double sx = worldToSx(b.position.x);
                double sy = worldToSy(b.position.z);
                if (sx >= mapX - VIEW_MARGIN && sx <= mapX + mapW + VIEW_MARGIN
                        && sy >= mapY - VIEW_MARGIN && sy <= mapY + mapH + VIEW_MARGIN)
                {
                    suns.add(b);
                }
            }
            suns.sort((a, b) ->
            {
                double da = sqr(worldToSx(a.position.x) - vcx) + sqr(worldToSy(a.position.z) - vcy);
                double db = sqr(worldToSx(b.position.x) - vcx) + sqr(worldToSy(b.position.z) - vcy);
                return Double.compare(da, db);
            });
            for (StarMapBody b : suns)
            {
                if (expand.size() >= MAX_EXPANDED_SYSTEMS)
                {
                    break;
                }
                expand.add(b.key);   // a STAR's key IS its system star key.
            }
        }

        List<StarMapBody> out = new ArrayList<>();
        int cap = MAX_EXPANDED_PLANETS;
        for (String starKey : expand)
        {
            GeneratedSystems.System sys = systemsByKey.get(starKey);
            if (sys == null)
            {
                continue;
            }
            for (StarMapBody p : StarMapData.systemPlanets(sys, epoch))
            {
                out.add(p);
                if (--cap <= 0)
                {
                    break;
                }
            }
            if (cap <= 0)
            {
                break;
            }
        }
        shownPlanets = out;
    }

    private static double sqr(double v)
    {
        return v * v;
    }

    private StarMapBody selected()
    {
        if (selectedKey.isEmpty())
        {
            return null;
        }
        for (StarMapBody b : bodies)
        {
            if (b.key.equals(selectedKey))
            {
                return b;
            }
        }
        for (StarMapBody b : shownPlanets)
        {
            if (b.key.equals(selectedKey))
            {
                return b;
            }
        }
        return null;
    }

    // the star key of the currently EXPANDED system (the selected sun, or the sun of a selected system planet), "" if the
    // selection is not part of a system.
    private String expandedSystem()
    {
        StarMapBody sel = selected();
        if (sel == null)
        {
            return "";
        }
        return sel.kind == StarMapBody.Kind.STAR ? sel.key : sel.systemStarKey;
    }

    private void recenter()
    {
        // centre on the sun (the layout origin) and fit the zoom so the whole charted set, plus the player, is visible.
        centreX = PlanetPositions.sunPosition().x;
        centreZ = PlanetPositions.sunPosition().z;
        centred = true;
        double maxDist = 1.0;
        for (StarMapBody b : bodies)
        {
            maxDist = Math.max(maxDist, Math.hypot(b.position.x - centreX, b.position.z - centreZ) + b.radius);
        }
        maxDist = Math.max(maxDist, Math.hypot(playerPos.x - centreX, playerPos.z - centreZ));
        double fit = (Math.min(mapW, mapH) * 0.45) / maxDist;
        zoom = clampZoom(fit);
    }

    // frame a system: centre on its sun and fit the zoom to its outermost ring, so its planets clearly orbit THAT sun. This
    // is the fix for "clicking a system shows its planets orbiting really far": the map flies to the system instead of
    // leaving it a sub-pixel cluster in the whole-universe view.
    private void focusSystem(GeneratedSystems.System sys)
    {
        centreX = sys.starPos.x;
        centreZ = sys.starPos.z;
        centred = true;
        double outer = sys.starRadius + 1.0;
        for (GeneratedSystems.SystemPlanet p : sys.planets)
        {
            outer = Math.max(outer, p.ringRadius + p.radius);
        }
        double fit = (Math.min(mapW, mapH) * 0.42) / (outer + 200.0);
        zoom = clampZoom(Math.max(fit, PLANET_ZOOM_THRESHOLD * 1.2));
    }

    private void sendCourse()
    {
        StarMapBody sel = selected();
        if (sel != null && sel.courseTarget)
        {
            NetworkUtils.INSTANCE.sendToServer(new PacketStarMapCourse(sel.key));
            onClose();
        }
    }

    private static float clampZoom(double z)
    {
        return (float) Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }

    private double worldToSx(double wx)
    {
        return mapX + mapW / 2.0 + (wx - centreX) * zoom;
    }

    private double worldToSy(double wz)
    {
        return mapY + mapH / 2.0 + (wz - centreZ) * zoom;
    }

    private boolean inMap(double mx, double my)
    {
        return mx >= mapX && mx < mapX + mapW && my >= mapY && my < mapY + mapH;
    }

    private boolean onMap(double sx, double sy, int margin)
    {
        return sx >= mapX - margin && sx <= mapX + mapW + margin && sy >= mapY - margin && sy <= mapY + mapH + margin;
    }

    @Override
    public void tick()
    {
        // refresh on a ~1s throttle so a live claim, a moved super body or a newly charted region updates without a reopen,
        // and keep the player marker current. Cheap: one bounded cell walk plus a few list builds.
        if (++ticks % 20 == 0)
        {
            refreshBodies();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial)
    {
        this.renderBackground(g);
        ThemeRender.panel(g, panelX, panelY, panelW, panelH);
        ThemeRender.header(g, panelX, panelY, panelW);
        g.drawString(this.font, this.title, panelX + 12, panelY + GuiTheme.HEADER_HEIGHT - 10,
                GuiTheme.COLOR_TITLE, false);

        // map viewport background and frame
        g.fill(mapX, mapY, mapX + mapW, mapY + mapH, MAP_BG);
        g.renderOutline(mapX, mapY, mapW, mapH, MAP_BORDER);

        // expand the visible/selected systems' planets once for this frame, from the shared clock.
        long epoch = OrbitClock.epochMillis();
        expandIfNeeded(epoch);

        // the bodies to consider for drawing/selection this frame: the base set plus the expanded system planets.
        List<StarMapBody> drawn = new ArrayList<>(bodies.size() + shownPlanets.size());
        drawn.addAll(bodies);
        drawn.addAll(shownPlanets);

        // hover: nearest drawn body to the cursor, within a few pixels, inside the map.
        hovered = null;
        double hoverBest = 10.0;
        for (StarMapBody b : drawn)
        {
            double sx = worldToSx(b.position.x);
            double sy = worldToSy(b.position.z);
            if (!onMap(sx, sy, 0))
            {
                continue;
            }
            double d = Math.hypot(mouseX - sx, mouseY - sy);
            if (d < hoverBest)
            {
                hoverBest = d;
                hovered = b;
            }
        }

        StarMapBody sel = selected();

        g.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);
        drawGrid(g);
        g.flush();   // commit the background and grid under the scissor before the batched vertex draws.

        Matrix4f m = g.pose().last().pose();
        drawRingsBatched(m, sel);
        drawLinesBatched(m, sel);
        drawDiscsBatched(m, drawn, sel);
        drawPlayerMarkerBatched(m);

        // textured icons over the discs (buffered blits), then labels, then commit everything under the scissor.
        drawIcons(g, drawn);
        drawLabels(g, drawn, sel);
        g.flush();
        g.disableScissor();

        drawStatsPanel(g, sel);

        super.render(g, mouseX, mouseY, partial);

        boolean canCourse = sel != null && sel.courseTarget;
        courseButton.active = canCourse;
        courseButton.setMessage(sel != null && sel.courseTarget
                ? Component.translatable("gui.dmz_ragnarok.core.starmap.engage")
                : Component.translatable("gui.dmz_ragnarok.core.starmap.set_course"));

        if (hovered != null && inMap(mouseX, mouseY))
        {
            List<Component> lines = new ArrayList<>();
            lines.add(hovered.name);
            lines.add(StarMapData.typeLabel(hovered).copy().withStyle(net.minecraft.ChatFormatting.GRAY));
            g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
        }
    }

    // ===== batched primitive helpers (one draw per class, vs the old per-pixel fills) =====

    private static void beginTriangles(BufferBuilder bb)
    {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        bb.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
    }

    private static void addDisc(BufferBuilder bb, Matrix4f m, float cx, float cy, float radius, int argb)
    {
        int seg = radius <= 3F ? 8 : radius <= 6F ? 12 : radius <= 12F ? 18 : 26;
        float a = ((argb >>> 24) & 0xFF) / 255F;
        float r = ((argb >> 16) & 0xFF) / 255F;
        float gg = ((argb >> 8) & 0xFF) / 255F;
        float b = (argb & 0xFF) / 255F;
        float px = cx + radius;
        float py = cy;
        for (int i = 1; i <= seg; ++i)
        {
            double ang = (i / (double) seg) * Math.PI * 2.0;
            float x = (float) (cx + Math.cos(ang) * radius);
            float y = (float) (cy + Math.sin(ang) * radius);
            bb.vertex(m, cx, cy, 0F).color(r, gg, b, a).endVertex();
            bb.vertex(m, px, py, 0F).color(r, gg, b, a).endVertex();
            bb.vertex(m, x, y, 0F).color(r, gg, b, a).endVertex();
            px = x;
            py = y;
        }
    }

    private static void beginQuads(BufferBuilder bb)
    {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
    }

    // a line segment as a thin quad, so lines batch into ONE draw and cost O(1) per segment (not O(pixels) as the old
    // per-pixel fill did).
    private static void addLineQuad(BufferBuilder bb, Matrix4f m, double x1, double y1, double x2, double y2,
                                    double w, int argb)
    {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double len = Math.hypot(dx, dy);
        if (len < 1.0E-6)
        {
            return;
        }
        double nx = -dy / len * (w * 0.5);
        double ny = dx / len * (w * 0.5);
        float a = ((argb >>> 24) & 0xFF) / 255F;
        float r = ((argb >> 16) & 0xFF) / 255F;
        float gg = ((argb >> 8) & 0xFF) / 255F;
        float b = (argb & 0xFF) / 255F;
        bb.vertex(m, (float) (x1 + nx), (float) (y1 + ny), 0F).color(r, gg, b, a).endVertex();
        bb.vertex(m, (float) (x2 + nx), (float) (y2 + ny), 0F).color(r, gg, b, a).endVertex();
        bb.vertex(m, (float) (x2 - nx), (float) (y2 - ny), 0F).color(r, gg, b, a).endVertex();
        bb.vertex(m, (float) (x1 - nx), (float) (y1 - ny), 0F).color(r, gg, b, a).endVertex();
    }

    // a ring (or ellipse) as line quads, CULLED to the viewport and with the segment count scaled to the on-screen radius,
    // so a huge or off-screen ring costs little.
    private void addRing(BufferBuilder bb, Matrix4f m, double cx, double cy, double rx, double ry, double w, int argb)
    {
        double rr = Math.max(rx, ry);
        if (rr < 1.5)
        {
            return;
        }
        if (cx + rr < mapX || cx - rr > mapX + mapW || cy + rr < mapY || cy - rr > mapY + mapH)
        {
            return;   // whole ring off the viewport.
        }
        int seg = (int) Math.max(24.0, Math.min(96.0, rr / 4.0));
        double px = cx + rx;
        double py = cy;
        for (int i = 1; i <= seg; ++i)
        {
            double ang = (i / (double) seg) * Math.PI * 2.0;
            double x = cx + Math.cos(ang) * rx;
            double y = cy + Math.sin(ang) * ry;
            addLineQuad(bb, m, px, py, x, y, w, argb);
            px = x;
            py = y;
        }
    }

    private void drawRingsBatched(Matrix4f m, StarMapBody sel)
    {
        Tesselator t = Tesselator.getInstance();
        BufferBuilder bb = t.getBuilder();
        beginQuads(bb);

        // main solar-system rings around the central sun (one per distinct fixed-body radius), culled to the viewport.
        double sunX = PlanetPositions.sunPosition().x;
        double sunZ = PlanetPositions.sunPosition().z;
        double cx = worldToSx(sunX);
        double cy = worldToSy(sunZ);
        List<Double> radii = new ArrayList<>();
        for (StarMapBody b : bodies)
        {
            if (b.kind != StarMapBody.Kind.FIXED)
            {
                continue;
            }
            double rr = Math.hypot(b.position.x - sunX, b.position.z - sunZ);
            boolean dup = false;
            for (double e : radii)
            {
                if (Math.abs(e - rr) < 1.0)
                {
                    dup = true;
                    break;
                }
            }
            if (!dup)
            {
                radii.add(rr);
            }
        }
        for (double rr : radii)
        {
            addRing(bb, m, cx, cy, rr * zoom, rr * zoom, 1.0, RING_LINE);
        }

        // system orbit rings: for each SHOWN system planet, a ring around ITS sun at the planet's current orbit distance.
        Map<String, Vec3> starPos = new java.util.HashMap<>();
        for (StarMapBody b : bodies)
        {
            if (b.kind == StarMapBody.Kind.STAR)
            {
                starPos.put(b.key, b.position);
            }
        }
        for (StarMapBody b : shownPlanets)
        {
            if (b.systemStarKey.isEmpty())
            {
                continue;
            }
            Vec3 star = starPos.get(b.systemStarKey);
            if (star == null)
            {
                continue;
            }
            double rr = Math.hypot(b.position.x - star.x, b.position.z - star.z);
            addRing(bb, m, worldToSx(star.x), worldToSy(star.z), rr * zoom, rr * zoom, 1.0, RING_LINE);
        }

        // ring indicators on ringed bodies (a flattened ellipse around the disc), drawn small on the icon.
        for (StarMapBody b : bodies)
        {
            addRingIndicator(bb, m, b);
        }
        for (StarMapBody b : shownPlanets)
        {
            addRingIndicator(bb, m, b);
        }

        t.end();
    }

    private void addRingIndicator(BufferBuilder bb, Matrix4f m, StarMapBody b)
    {
        if ((b.kind != StarMapBody.Kind.GENERATED && b.kind != StarMapBody.Kind.FIXED) || !PlanetRings.isRinged(b.key))
        {
            return;
        }
        double sx = worldToSx(b.position.x);
        double sy = worldToSy(b.position.z);
        if (!onMap(sx, sy, VIEW_MARGIN))
        {
            return;
        }
        int r = discRadius(b);
        addRing(bb, m, sx, sy, r * 1.9, r * 0.8, 1.0, 0x88C8D4E0);
    }

    private void drawLinesBatched(Matrix4f m, StarMapBody sel)
    {
        if (sel == null)
        {
            return;
        }
        Tesselator t = Tesselator.getInstance();
        BufferBuilder bb = t.getBuilder();
        beginQuads(bb);
        addLineQuad(bb, m, worldToSx(playerPos.x), worldToSy(playerPos.z),
                worldToSx(sel.position.x), worldToSy(sel.position.z), 1.0, SELECT_LINE);
        t.end();
    }

    private void drawDiscsBatched(Matrix4f m, List<StarMapBody> drawn, StarMapBody sel)
    {
        Tesselator t = Tesselator.getInstance();
        BufferBuilder bb = t.getBuilder();
        beginTriangles(bb);
        int count = 0;
        for (StarMapBody b : drawn)
        {
            double sx = worldToSx(b.position.x);
            double sy = worldToSy(b.position.z);
            if (!onMap(sx, sy, VIEW_MARGIN) || count >= MAX_DISCS_PER_FRAME)
            {
                continue;
            }
            count++;
            int r = discRadius(b);
            int color = 0xFF000000 | (b.tint & 0xFFFFFF);
            if (b.kind == StarMapBody.Kind.SUPER)
            {
                // the radar revealed them, so draw the seven super balls in an unmistakable dragon-ball ORANGE (a warm gold
                // when claimed) rather than a grey dot lost among the system suns. Claimed vs unclaimed is spelled out in
                // the info panel and the icon, so the map itself just needs them to POP.
                color = b.superClaimed ? 0xFFFFC24A : 0xFFFF8A2B;
            }
            addDisc(bb, m, (float) sx, (float) sy, r, color);
        }
        t.end();

        // selection highlight + sun coronas + altitude stems, as line quads over the discs.
        BufferBuilder rb = t.getBuilder();
        beginQuads(rb);
        for (StarMapBody b : drawn)
        {
            double sx = worldToSx(b.position.x);
            double sy = worldToSy(b.position.z);
            if (!onMap(sx, sy, VIEW_MARGIN))
            {
                continue;
            }
            int r = discRadius(b);
            // ALTITUDE STEM (item: "show height"). A faint short vertical line whose length and direction encode the body's
            // height above (up) or below (down) the main solar-system plane, so a top-down map still reads which systems
            // sit high or low. Faint and capped, so hundreds of them stay a subtle 3D-ish texture rather than clutter.
            int stem = altStemPx(b);
            if (Math.abs(stem) >= 2)
            {
                addLineQuad(rb, m, sx, sy, sx, sy - stem, 1.0, ALT_STEM_LINE);
            }
            if (b.kind == StarMapBody.Kind.SUN || b.kind == StarMapBody.Kind.STAR)
            {
                addRing(rb, m, sx, sy, r + 2.0, r + 2.0, 1.0, CORONA_LINE);
            }
            if (b.key.equals(selectedKey))
            {
                addRing(rb, m, sx, sy, r + 3.0, r + 3.0, 1.0, SELECT_LINE);
            }
        }
        t.end();
    }

    private void drawPlayerMarkerBatched(Matrix4f m)
    {
        double sx = worldToSx(playerPos.x);
        double sy = worldToSy(playerPos.z);
        if (!onMap(sx, sy, 4))
        {
            return;
        }
        Tesselator t = Tesselator.getInstance();
        BufferBuilder bb = t.getBuilder();
        beginTriangles(bb);
        addDisc(bb, m, (float) sx, (float) sy, 2F, PLAYER_MARK);
        t.end();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
        {
            BufferBuilder lb = t.getBuilder();
            beginQuads(lb);
            double yaw = Math.toRadians(mc.player.getYRot());
            double dx = -Math.sin(yaw);
            double dz = Math.cos(yaw);
            addLineQuad(lb, m, sx, sy, sx + dx * 11.0, sy + dz * 11.0, 1.0, PLAYER_MARK);
            t.end();
        }
    }

    // textured icons, blitted over the disc so a body reads as the real sun/planet art rather than a flat dot. Batched by
    // texture through the gui buffer source, culled and capped.
    private void drawIcons(GuiGraphics g, List<StarMapBody> drawn)
    {
        int count = 0;
        for (StarMapBody b : drawn)
        {
            if (count >= MAX_ICONS_PER_FRAME)
            {
                break;
            }
            double sx = worldToSx(b.position.x);
            double sy = worldToSy(b.position.z);
            if (!onMap(sx, sy, 0))
            {
                continue;
            }
            if (!blitIcon(g, b, sx, sy))
            {
                continue;   // super bodies (and anything with no icon) keep the disc only.
            }
            count++;
        }
        g.setColor(1F, 1F, 1F, 1F);
    }

    // draw one body's face-crop icon; returns false if the body has no icon (draw the disc only).
    private boolean blitIcon(GuiGraphics g, StarMapBody b, double sx, double sy)
    {
        ResourceLocation tex;
        int tint;
        boolean gray;
        boolean sun = false;
        switch (b.kind)
        {
            case SUN:
                tex = ICON_SUN;
                tint = 0xFFFFFF;
                gray = false;
                sun = true;
                break;
            case STAR:
                tex = ICON_SUN_GRAY;
                tint = b.tint;
                gray = true;
                sun = true;
                break;
            case FIXED:
                if ("minecraft:overworld".equals(b.key))
                {
                    tex = ICON_EARTH;
                    tint = 0xFFFFFF;
                    gray = false;
                }
                else if ("dmz_ragnarok:planet_vegeta".equals(b.key))
                {
                    tex = ICON_VEGETA;
                    tint = 0xFFFFFF;
                    gray = false;
                }
                else
                {
                    tex = ICON_PLANET_POOL[poolIndex(b.key)];
                    tint = b.tint;
                    gray = true;
                }
                break;
            case GENERATED:
                tex = ICON_PLANET_POOL[poolIndex(b.key)];
                tint = b.tint;
                gray = true;
                break;
            case SUPER:
                // a glowing orb icon in dragon-ball orange (warm gold when claimed), so a revealed super ball reads as a
                // ball rather than a plain dot. The grayscale sun sheet tinted, exactly as a system star is drawn.
                tex = ICON_SUN_GRAY;
                tint = b.superClaimed ? 0xF6C74A : 0xFF8A2B;
                gray = true;
                sun = true;
                break;
            default:
                return false;
        }

        int r = discRadius(b) + 1;
        int size = r * 2;
        int x = (int) sx - r;
        int y = (int) sy - r;
        if (gray)
        {
            g.setColor(lift(((tint >> 16) & 0xFF) / 255F), lift(((tint >> 8) & 0xFF) / 255F),
                    lift((tint & 0xFF) / 255F), 1F);
        }
        else
        {
            g.setColor(1F, 1F, 1F, 1F);
        }
        if (sun)
        {
            g.blit(tex, x, y, size, size, SUN_CROP_U, SUN_CROP_V, SUN_CROP_W, SUN_CROP_H, SUN_TEX_W, SUN_TEX_H);
        }
        else
        {
            g.blit(tex, x, y, size, size, PLANET_CROP_U, PLANET_CROP_V, PLANET_CROP_W, PLANET_CROP_H, PLANET_TEX,
                    PLANET_TEX);
        }
        g.setColor(1F, 1F, 1F, 1F);
        return true;
    }

    private static float lift(float channel)
    {
        return Math.min(1F, channel / SHEET_MEAN);
    }

    private static int poolIndex(String key)
    {
        return Math.floorMod(key.hashCode(), ICON_PLANET_POOL.length);
    }

    private void drawLabels(GuiGraphics g, List<StarMapBody> drawn, StarMapBody sel)
    {
        for (StarMapBody b : drawn)
        {
            // super bodies are labelled like landmarks so the seven revealed balls are named at a glance, not only on hover.
            boolean landmark = b.kind == StarMapBody.Kind.SUN || b.kind == StarMapBody.Kind.FIXED
                    || b.kind == StarMapBody.Kind.SUPER;
            boolean show = b.key.equals(selectedKey) || (hovered != null && b.key.equals(hovered.key)) || landmark;
            if (!show)
            {
                continue;
            }
            double sx = worldToSx(b.position.x);
            double sy = worldToSy(b.position.z);
            if (!onMap(sx, sy, 0))
            {
                continue;
            }
            int r = discRadius(b);
            g.drawString(this.font, b.name, (int) sx + r + 2, (int) sy - 4, LABEL_COLOR, false);
        }
    }

    private void drawGrid(GuiGraphics g)
    {
        // faint grid whose spacing adapts to the zoom, purely as a distance cue behind the bodies.
        double step = 1000.0;
        while (step * zoom < 28.0)
        {
            step *= 2.0;
        }
        while (step * zoom > 120.0)
        {
            step /= 2.0;
        }
        double startX = centreX - (mapW / 2.0) / zoom;
        double endX = centreX + (mapW / 2.0) / zoom;
        for (double v = Math.floor(startX / step) * step; v < endX; v += step)
        {
            int sx = (int) worldToSx(v);
            g.fill(sx, mapY, sx + 1, mapY + mapH, GRID_LINE);
        }
        double startZ = centreZ - (mapH / 2.0) / zoom;
        double endZ = centreZ + (mapH / 2.0) / zoom;
        for (double v = Math.floor(startZ / step) * step; v < endZ; v += step)
        {
            int sy = (int) worldToSy(v);
            g.fill(mapX, sy, mapX + mapW, sy + 1, GRID_LINE);
        }
    }

    // the altitude stem length in pixels for a body: signed by height above (positive = up on screen) or below the main
    // plane, capped at ALT_MAX_STEM. Screen Y grows downward, so the caller subtracts this from sy for an up-stem.
    private int altStemPx(StarMapBody b)
    {
        double px = (b.position.y - ALT_REF_Y) / ALT_SPAN_BLOCKS * ALT_MAX_STEM;
        return (int) Math.max(-ALT_MAX_STEM, Math.min(ALT_MAX_STEM, px));
    }

    private int discRadius(StarMapBody b)
    {
        switch (b.kind)
        {
            case SUN:
                return 7;
            case STAR:
                return 5;
            case SUPER:
                return 5;   // a touch larger than a main planet so the seven super balls read clearly when revealed.
            case FIXED:
                return 4;
            default:
                return 3;
        }
    }

    private void drawStatsPanel(GuiGraphics g, StarMapBody sel)
    {
        ThemeRender.tooltip(g, statsX, statsY, statsW, statsH);
        int tx = statsX + 8;
        int ty = statsY + 8;
        int innerW = statsW - 16;
        g.drawString(this.font, Component.translatable("gui.dmz_ragnarok.core.starmap.destination"),
                tx, ty, GuiTheme.COLOR_MUTED, false);
        ty += 14;

        if (sel == null)
        {
            for (var line : this.font.split(
                    Component.translatable("gui.dmz_ragnarok.core.starmap.select_hint"), innerW))
            {
                g.drawString(this.font, line, tx, ty, GuiTheme.COLOR_MUTED, false);
                ty += 11;
            }
            ty += 4;
            for (var line : this.font.split(
                    Component.translatable("gui.dmz_ragnarok.core.starmap.controls"), innerW))
            {
                g.drawString(this.font, line, tx, ty, GuiTheme.COLOR_MUTED, false);
                ty += 11;
            }
            return;
        }

        g.drawString(this.font, sel.name, tx, ty, GuiTheme.COLOR_TITLE, false);
        ty += 13;
        g.drawString(this.font, StarMapData.typeLabel(sel), tx, ty, GuiTheme.COLOR_LABEL, false);
        ty += 13;

        if (sel.surfaceSize > 0)
        {
            g.drawString(this.font, Component.translatable("gui.dmz_ragnarok.core.starmap.size",
                    String.valueOf(sel.surfaceSize)), tx, ty, GuiTheme.COLOR_ROW, false);
            ty += 11;
        }

        double dist = playerPos.distanceTo(sel.position);
        g.drawString(this.font, Component.translatable("gui.dmz_ragnarok.core.starmap.distance",
                String.format(Locale.ROOT, "%.2f", dist / 1000.0)), tx, ty, GuiTheme.COLOR_ROW, false);
        ty += 11;

        // altitude relative to the main solar-system plane, so the "show height" cue has a readable number to go with the
        // stem on the map. A signed value: positive is above the plane, negative below.
        int altitude = (int) Math.round(sel.position.y - ALT_REF_Y);
        g.drawString(this.font, Component.translatable("gui.dmz_ragnarok.core.starmap.altitude",
                (altitude >= 0 ? "+" : "") + altitude), tx, ty, GuiTheme.COLOR_ROW, false);
        ty += 11;

        if (sel.kind == StarMapBody.Kind.GENERATED)
        {
            Component owner = sel.owner.isEmpty()
                    ? Component.translatable("message.dmz_ragnarok.core.space_owner_unclaimed")
                    : Component.literal(sel.owner);
            g.drawString(this.font, Component.translatable("gui.dmz_ragnarok.core.starmap.owner", owner),
                    tx, ty, GuiTheme.COLOR_ROW, false);
            ty += 11;
        }
        else if (sel.kind == StarMapBody.Kind.SUPER)
        {
            g.drawString(this.font, Component.translatable(sel.superClaimed
                            ? "gui.dmz_ragnarok.core.starmap.super_claimed"
                            : "gui.dmz_ragnarok.core.starmap.super_unclaimed"),
                    tx, ty, GuiTheme.COLOR_ROW, false);
            ty += 11;
        }

        Component status = !sel.landable
                ? Component.translatable("gui.dmz_ragnarok.core.starmap.observation")
                : (sel.courseTarget
                        ? Component.translatable("gui.dmz_ragnarok.core.starmap.landable")
                        : Component.translatable("gui.dmz_ragnarok.core.starmap.no_autopilot"));
        ty += 3;
        for (var line : this.font.split(status, innerW))
        {
            g.drawString(this.font, line, tx, ty, GuiTheme.COLOR_MUTED, false);
            ty += 11;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button)
    {
        if (button == 0 && inMap(mx, my))
        {
            List<StarMapBody> drawn = new ArrayList<>(bodies.size() + shownPlanets.size());
            drawn.addAll(bodies);
            drawn.addAll(shownPlanets);
            StarMapBody best = null;
            double bestD = 10.0;
            for (StarMapBody b : drawn)
            {
                double sx = worldToSx(b.position.x);
                double sy = worldToSy(b.position.z);
                if (!onMap(sx, sy, 0))
                {
                    continue;
                }
                double d = Math.hypot(sx - mx, sy - my);
                if (d < bestD)
                {
                    bestD = d;
                    best = b;
                }
            }
            if (best != null)
            {
                selectedKey = best.key;
                shownSig = "";   // force a re-expand so the newly selected system shows its planets.
                // FRAME a system when its sun is picked, so its planets read as orbiting THAT sun.
                if (best.kind == StarMapBody.Kind.STAR)
                {
                    GeneratedSystems.System sys = systemsByKey.get(best.key);
                    if (sys != null)
                    {
                        focusSystem(sys);
                    }
                }
            }
            else
            {
                dragging = true;
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy)
    {
        if (dragging && button == 0)
        {
            centreX -= dx / zoom;
            centreZ -= dy / zoom;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button)
    {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount)
    {
        if (inMap(mx, my))
        {
            // zoom toward the cursor so the point under the mouse stays put, like a proper map.
            double worldX = centreX + (mx - (mapX + mapW / 2.0)) / zoom;
            double worldZ = centreZ + (my - (mapY + mapH / 2.0)) / zoom;
            zoom = clampZoom(zoom * Math.pow(1.15, amount));
            centreX = worldX - (mx - (mapX + mapW / 2.0)) / zoom;
            centreZ = worldZ - (my - (mapY + mapH / 2.0)) / zoom;
            return true;
        }
        return super.mouseScrolled(mx, my, amount);
    }
}
