package net.shurui.shuruisutilities.client.space;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.client.gui.DmzTextures;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.space.GeneratedPlanets;
import net.shurui.shuruisutilities.space.PacketPlanetInfoRequest;
import net.shurui.shuruisutilities.space.PlanetInfoLabels;
import net.shurui.shuruisutilities.space.PlanetInfoTarget;
import net.shurui.shuruisutilities.space.PlanetInfoView;
import net.shurui.shuruisutilities.space.SpaceDimension;
import net.shurui.shuruisutilities.space.SpaceLayout;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The FLOATING planet-info panel. It replaces the old {@code PlanetInfoScreen}: instead of a window the player opens, a
 * small HUD panel appears BY ITSELF, in the top-right corner, while the player is looking at a planet in space, and
 * vanishes when they look away. It never captures the mouse or blocks movement, so the player keeps flying and aiming
 * while it is up. This matches the feel of NoeaMod's Deep Space target readout, which draws its panel from a
 * {@code RenderGuiOverlayEvent} in plain HUD space (not a billboard in the world) and shows it automatically when a
 * planet is aimed at.
 *
 * <h2>Why a HUD panel and not a world-space billboard</h2>
 * A world-space plate would have to stay legible at the hundreds-of-blocks range planets are actually viewed from (see
 * {@link SpaceLayout#LABEL_DISTANCE}, 300), which means either it shrinks to nothing far away or it is scaled up so far
 * that it swallows the planet, and either way it fights the body for depth and can z-fight the cube it sits on. The small
 * floating NAME tag the renderer already draws next to each body covers the "which one is that" job in the world; the
 * FULL readout (toughness, garrison, guild) belongs in fixed HUD space where the font is a constant, always-readable size
 * and nothing can occlude it. This is exactly the split NoeaMod uses, so it also matches the reference's feel.
 *
 * <h2>Server stays authoritative; the client never computes the info</h2>
 * The panel only ever DRAWS a {@link PlanetInfoView} the server built and pushed. The client's own work is limited to
 * deciding WHICH planet is aimed at (so it knows when to ask and when to hide), using the exact same
 * {@link GeneratedPlanets#bodyAlongRay} geometry and {@link SpaceLayout#LABEL_DISTANCE} gate the server authority and the
 * planet-buster use, so the two can never disagree on which body is in reach.
 *
 * <h2>Refresh strategy (the main risk of a continuous overlay)</h2>
 * Turning an on-demand screen into a continuous overlay must NOT turn one keypress into a request every frame. So:
 * <ul>
 *   <li>Target detection runs once per client TICK (20 Hz), not per frame, and only over the small
 *       {@link SpaceLayout#LABEL_DISTANCE} reach, so it is cheap.</li>
 *   <li>A request is sent ONLY when the aimed-at planet CHANGES, or on a {@link #REFRESH_INTERVAL_MS} throttle while the
 *       same planet is held (so live values like defenders-remaining and a fresh claim still update, about once a
 *       second).</li>
 *   <li>The last received view is CACHED and drawn every frame in between. When the target changes we clear the cache at
 *       once, so the panel briefly shows nothing rather than the PREVIOUS planet's data while the new view is in flight.
 *       When the player looks away the cache is dropped and the panel hides immediately.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlanetInfoOverlay
{
    public static final String OVERLAY_ID = "planet_info";

    // the gui lang-key root, shared with the old screen's entries so no line had to be re-translated.
    private static final String KEY = "gui.dmz_ragnarok.core.planetinfo.";

    // how often, at most, we re-ask the server for the SAME held planet, so live values (defenders left, a new claim)
    // refresh without a per-frame or per-tick flood. One second matches the renderer's own near-set refresh cadence.
    private static final long REFRESH_INTERVAL_MS = 1000L;

    // pixels of empty space inside the panel border, and the distance the panel sits in from the top-right screen edge.
    private static final int PAD = 8;
    private static final int MARGIN = 6;
    // vertical pitch between lines, and the extra gap before a section heading, matching the old screen's rhythm.
    private static final int LINE = 11;
    private static final int SECTION_GAP = 4;
    // clamp the panel width so a very long guild name cannot stretch it across the screen.
    private static final int MAX_W = 190;

    private static final int COL_HEADER = 0xFFF6E27A;   // gold, the planet name, matching the region HUD's title
    private static final int COL_HEADING = 0xFFFFE04A;  // yellow section headings
    private static final int COL_VALUE = 0xFFE6E6E6;    // plain white value lines
    private static final int COL_MUTED = 0xFF9AA0A6;    // grey for "not explored"/"unclaimed" fallbacks
    private static final int COL_DESTROYED = 0xFFFF5555; // red rubble banner

    // the id of the planet currently aimed at, or null when nothing is aimed at (panel hidden). volatile so the render
    // thread sees the tick thread's latest value without a lock.
    private static volatile String targetId = null;
    // the last view the server pushed for the current target, or null while none has arrived yet (or the target just
    // changed). Drawing a null view draws nothing, which is the correct in-flight behaviour.
    private static volatile PlanetInfoView cachedView = null;
    // when we last sent a request, for the throttle above.
    private static long lastRequestMs = 0L;

    // latched one-shot warnings so a persistent client-side failure (target math or a draw) logs ONCE for the run, never
    // once per tick or per frame. Separate latches so a failure in one path does not mask the other's first warning.
    private static boolean detectWarned = false;
    private static boolean drawWarned = false;

    private PlanetInfoOverlay()
    {
    }

    /**
     * Flip the persistent ON/OFF preference and tell the player, off the P keybind. When switched off we drop the cached
     * state at once so the panel disappears the same frame rather than lingering until the next look-away.
     */
    public static void toggle()
    {
        PlanetInfoConfig cfg = PlanetInfoConfig.get();
        cfg.overlayEnabled = !cfg.overlayEnabled;
        cfg.save();
        if (!cfg.overlayEnabled)
        {
            targetId = null;
            cachedView = null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
        {
            // action-bar feedback in the viewer's language, matching the space-travel notice style.
            mc.player.displayClientMessage(
                    Component.translatable("message.dmz_ragnarok.core"
                            + (cfg.overlayEnabled ? ".planet_info_on" : ".planet_info_off")),
                    true);
        }
    }

    /**
     * Deposit a server-pushed view. Called from {@link PlanetInfoClient} (the packet handler). Ignored when nothing is
     * aimed at any more, so a view that arrives just after the player looked away never flashes onto a blank crosshair.
     */
    public static void acceptView(PlanetInfoView view)
    {
        if (targetId != null)
        {
            cachedView = view;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null)
        {
            // disconnected or between worlds: drop everything so nothing carries into a fresh session.
            targetId = null;
            cachedView = null;
            return;
        }
        // switched off, or not in space: no detection, no requests, no panel.
        if (!PlanetInfoConfig.get().overlayEnabled || !SpaceDimension.isSpace(mc.level))
        {
            targetId = null;
            cachedView = null;
            return;
        }

        String current = computeTargetId(mc);
        if (current == null)
        {
            // looked away from every planet: hide immediately.
            targetId = null;
            cachedView = null;
            return;
        }

        long now = System.currentTimeMillis();
        if (!current.equals(targetId))
        {
            // a DIFFERENT planet is now aimed at: adopt it, clear the previous planet's cached view so it cannot show
            // under the new one, and ask straight away.
            targetId = current;
            cachedView = null;
            NetworkUtils.INSTANCE.sendToServer(new PacketPlanetInfoRequest());
            lastRequestMs = now;
        }
        else if (now - lastRequestMs >= REFRESH_INTERVAL_MS)
        {
            // same planet still aimed at: refresh its live values on the throttle.
            NetworkUtils.INSTANCE.sendToServer(new PacketPlanetInfoRequest());
            lastRequestMs = now;
        }
    }

    // which body (generated planet OR orbiting moon) the player is aiming at right now, or null. Mirrors
    // PlanetInfoServer's targeting EXACTLY by calling the SAME PlanetInfoTarget.resolve rule (same ray, same cap, same
    // moon step, same surface-distance gate), only with a null server so it reads the synced body set, so the client's
    // "am I looking at a body" and the server's authoritative pick agree. Wrapped: a fault here only means the panel
    // stops updating, never a broken client tick, and it is logged once.
    private static String computeTargetId(Minecraft mc)
    {
        try
        {
            Vec3 eye = mc.player.getEyePosition();
            Vec3 look = mc.player.getLookAngle();
            double maxRay = SpaceLayout.LABEL_DISTANCE + GeneratedPlanets.maxBodyRadius();
            // the same whole game time the server places moons at, so the client's clickable moon matches the server's;
            // the renderer alone draws with a sub-tick glide, far inside the cube, so the drawn moon still lines up.
            long gameTime = mc.level.getGameTime();
            PlanetInfoTarget.Hit target = PlanetInfoTarget.resolve(null, eye, look, maxRay, gameTime);
            if (target == null)
            {
                return null;
            }
            // the same surface-distance rule the label gate and the server use: info shows only for a body whose name is
            // showing, so the panel and the nameplate appear together.
            double surfaceDistance = eye.distanceTo(target.position) - target.radius;
            if (surfaceDistance > SpaceLayout.LABEL_DISTANCE)
            {
                return null;
            }
            return target.id;
        }
        catch (Throwable t)
        {
            if (!detectWarned)
            {
                detectWarned = true;
                LoggingHandler.sulog.warn("[PlanetInfo] Target detection failed; overlay will not update.", t);
            }
            return null;
        }
    }

    /**
     * Overlay render hook, registered as a Forge GUI overlay in {@code SUClientMenus}. Draws the cached view when one is
     * held for the current target. All of it is wrapped so a draw fault can never take down the HUD render pass; it is
     * logged once.
     */
    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui || mc.options.renderDebug)
        {
            return;
        }
        if (!PlanetInfoConfig.get().overlayEnabled || !SpaceDimension.isSpace(mc.level))
        {
            return;
        }
        // read the volatiles once so the tick thread cannot swap them out mid-draw.
        String id = targetId;
        PlanetInfoView v = cachedView;
        if (id == null || v == null)
        {
            return;
        }
        try
        {
            drawPanel(g, mc.font, v, screenWidth, screenHeight);
        }
        catch (Throwable t)
        {
            if (!drawWarned)
            {
                drawWarned = true;
                LoggingHandler.sulog.warn("[PlanetInfo] Overlay draw failed; hiding the panel this run.", t);
            }
        }
    }

    // build the lines, size the panel to them, and draw it flush to the top-right corner. Content is a 1:1 copy of the
    // old screen's sections (name + destroyed banner, environment, strength, defenders, guild), with every one of its
    // "unknown/none/not explored/unclaimed" fallbacks preserved.
    private static void drawPanel(GuiGraphics g, Font font, PlanetInfoView v, int screenWidth, int screenHeight)
    {
        List<Line> lines = buildLines(v);

        int inner = 0;
        for (Line line : lines)
        {
            inner = Math.max(inner, font.width(line.text()));
        }
        int w = Math.min(MAX_W, inner) + 2 * PAD;

        // height: PAD top and bottom, plus each line's pitch, plus a gap before every section heading after the first.
        int contentH = 0;
        for (int i = 0; i < lines.size(); ++i)
        {
            if (i > 0 && lines.get(i).heading())
            {
                contentH += SECTION_GAP;
            }
            contentH += LINE;
        }
        int h = contentH + 2 * PAD;

        int x = screenWidth - w - MARGIN;
        int y = MARGIN;

        // same nine-sliced DMZ menu texture every other SU panel uses, so the overlay matches the suite look.
        DmzTextures.panel(g, x, y, w, h);

        int ty = y + PAD;
        for (int i = 0; i < lines.size(); ++i)
        {
            Line line = lines.get(i);
            if (i > 0 && line.heading())
            {
                ty += SECTION_GAP;
            }
            g.drawString(font, line.text(), x + PAD, ty, line.colour(), true);
            ty += LINE;
        }
    }

    // one drawable line: its text, colour and whether it is a section heading (which earns the leading gap).
    private record Line(Component text, int colour, boolean heading)
    {
    }

    private static List<Line> buildLines(PlanetInfoView v)
    {
        List<Line> out = new ArrayList<>();

        // header: the planet name, or the shared "unknown planet" phrase, per the suite "named thing reads first" rule.
        Component name = v.planetName.isEmpty()
                ? Component.translatable(KEY + "unknown_planet")
                : Component.literal(v.planetName);
        out.add(new Line(name, COL_HEADER, true));

        // a destroyed planet has no live garrison, owner or recommended power (the destroy clears all of it), so lead with
        // the rubble banner; the environment and plain toughness below are still pure from the id, so they still show.
        if (v.destroyed)
        {
            out.add(new Line(Component.translatable(KEY + "destroyed"), COL_DESTROYED, false));
        }

        // ENVIRONMENT
        out.add(new Line(Component.translatable(KEY + "section.environment"), COL_HEADING, true));
        out.add(new Line(Component.translatable(KEY + "environment",
                Component.translatable(PlanetInfoLabels.envKey(v.environmentTheme))), COL_VALUE, false));
        out.add(new Line(Component.translatable(KEY + "surface", String.valueOf(v.surfaceSize)), COL_VALUE, false));

        // STRENGTH
        out.add(new Line(Component.translatable(KEY + "section.strength"), COL_HEADING, true));
        out.add(new Line(Component.translatable(KEY + "toughness", num(v.toughness)), COL_VALUE, false));
        // recommended power is only meaningful once defenders exist; otherwise say "unknown" rather than a misleading 0.
        Component power = v.populated
                ? Component.literal(num(v.recommendedBattlePower))
                : Component.translatable(PlanetInfoLabels.UNKNOWN_KEY);
        out.add(new Line(Component.translatable(KEY + "recommended", power), COL_VALUE, false));

        // DEFENDERS
        out.add(new Line(Component.translatable(KEY + "section.defenders"), COL_HEADING, true));
        buildDefenders(v, out);

        // GUILD
        out.add(new Line(Component.translatable(KEY + "section.guild"), COL_HEADING, true));
        buildGuild(v, out);

        return out;
    }

    // "not yet explored" for an unvisited planet, "undefended" for a populated-but-empty one, "<family>" + "X of Y" for a
    // defended one, exactly as the old screen decided.
    private static void buildDefenders(PlanetInfoView v, List<Line> out)
    {
        if (!v.populated)
        {
            out.add(new Line(Component.translatable(KEY + "defenders_unexplored"), COL_MUTED, false));
            return;
        }
        if (v.defendersTotal <= 0 || v.garrisonFamily.isEmpty())
        {
            out.add(new Line(Component.translatable(KEY + "defenders_none"), COL_VALUE, false));
            return;
        }
        out.add(new Line(Component.translatable(KEY + "defenders_family",
                Component.translatable(PlanetInfoLabels.familyKey(v.garrisonFamily))), COL_VALUE, false));
        out.add(new Line(Component.translatable(KEY + "defenders_count",
                String.valueOf(v.defendersRemaining), String.valueOf(v.defendersTotal)), COL_VALUE, false));
    }

    // "unclaimed", or the owning guild's name plus its member count and battle power, as the old screen decided.
    private static void buildGuild(PlanetInfoView v, List<Line> out)
    {
        if (!v.claimed)
        {
            out.add(new Line(Component.translatable(KEY + "guild_unclaimed"), COL_MUTED, false));
            return;
        }
        out.add(new Line(Component.translatable(KEY + "guild_owner", v.ownerGuildName), COL_VALUE, false));
        out.add(new Line(Component.translatable(KEY + "guild_members", String.valueOf(v.ownerMemberCount)),
                COL_VALUE, false));
        out.add(new Line(Component.translatable(KEY + "guild_power", num(v.ownerBattlePower)), COL_VALUE, false));
    }

    // whole-number formatting with thousands separators, matching the old screen and GuildScreen's power/bank lines.
    private static String num(double value)
    {
        return String.format("%,.0f", value);
    }
}
