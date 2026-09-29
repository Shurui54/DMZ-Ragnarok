package net.shurui.dev.sdu.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.shurui.dev.sdu.client.ClientWaypoints;
import net.shurui.dev.sdu.waypoint.Waypoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-centre compass bar HUD: a scrolling ruler with a marker per active waypoint by bearing, and the nearest
 * waypoint's name and distance below.
 *
 * <p>A MANUAL pin only draws in the dimension it was dropped in. A QUEST waypoint follows the player, so when
 * its objective is in another dimension the label names the dimension to travel to rather than a meaningless
 * bearing. The overlay hides only with no waypoint at all.
 */
public final class CompassOverlay {

    public static final IGuiOverlay COMPASS = CompassOverlay::render;

    /** Bar chrome: a 2px outline with a transparent middle. */
    private static final ResourceLocation FRAME =
            new ResourceLocation("dmz_ragnarok", "textures/gui/compass/frame.png");

    /** The direction ruler that scrolls behind the frame: NW N NE E SE S SW W with graduation dots. */
    private static final ResourceLocation STRIP =
            new ResourceLocation("dmz_ragnarok", "textures/gui/compass/strip.png");

    private static final int BAR_WIDTH = 183;
    private static final int BAR_HEIGHT = 13;
    private static final int TOP_MARGIN = 4;

    /** Frame border thickness, so the strip clips to the hole inside it. */
    private static final int FRAME_INSET = 2;

    /**
     * Strip travel per degree of yaw and its repeat period, both from the art. The eight labels sit ~22.375px
     * apart (45 degrees each), so a full turn is 179px, exactly the hole width, and the ruler wraps seamlessly
     * showing 360 degrees at once (no edge-pinned markers). Texture is 183 wide but tiles on 179; the 4px
     * overlap is transparent (ink runs u=6..177), so nothing draws twice.
     */
    private static final float STRIP_PERIOD = BAR_WIDTH - 2f * FRAME_INSET;   // 179
    private static final float PX_PER_DEGREE = STRIP_PERIOD / 360f;

    /**
     * Where the strip's first label sits and its bearing. NW's lettering is centred on u=14.5, and NW is yaw 135
     * (MC yaw: south=0 clockwise, so west=90, north=180, east=270). Anchoring on a measured label keeps the
     * letters honest under the centre mark.
     */
    private static final float ANCHOR_U = 14.5f;
    private static final float ANCHOR_BEARING = 135f;

    /** 50%-opaque black behind the ruler; the frame art is outline only. */
    private static final int BG_COLOR = 0x80000000;

    private CompassOverlay() {
    }

    private static void render(net.minecraftforge.client.gui.overlay.ForgeGui gui, GuiGraphics g,
                               float partialTick, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        // The waypoint compass is private (OWNER-SPECS section 4): not drawn at all unless the connected server
        // reported the key. The quest tracker keeps the objective's location public.
        if (!net.shurui.dev.sdu.api.ClientGate.key()) {
            return;
        }

        String dim = mc.player.level().dimension().location().toString();
        List<Waypoint> here = new ArrayList<>();
        // First quest waypoint in another dimension. Only one kept: the label names a single destination.
        Waypoint away = null;
        for (Waypoint w : ClientWaypoints.all()) {
            // A notice has no position AND no dim (a tracker row only); its blank dim would falsely read as
            // "objective in another world" and send the compass nowhere.
            if (w.isNotice()) {
                continue;
            }
            if (dim.equals(w.dim)) {
                // A travel marker has no position (Waypoint.travel), so nothing to draw once you are in its dim.
                if (!w.travel) {
                    here.add(w);
                }
            } else if (w.quest && away == null) {
                away = w;
            }
        }
        if (here.isEmpty() && away == null) {
            return;
        }

        Font font = mc.font;
        double px = mc.player.getX();
        double py = mc.player.getEyeY();
        double pz = mc.player.getZ();
        float playerYaw = mc.player.getYRot();

        int cx = screenW / 2;
        int barLeft = cx - BAR_WIDTH / 2;
        int barTop = TOP_MARGIN;
        int barBottom = barTop + BAR_HEIGHT;
        int interiorLeft = barLeft + FRAME_INSET;
        int interiorRight = barLeft + BAR_WIDTH - FRAME_INSET;

        // Fill first, frame over it: the frame is an outline with a hole, so a later fill would cover the border.
        g.fill(interiorLeft, barTop + FRAME_INSET, interiorRight, barBottom - FRAME_INSET, BG_COLOR);
        g.blit(FRAME, barLeft, barTop, 0, 0, BAR_WIDTH, BAR_HEIGHT, BAR_WIDTH, BAR_HEIGHT);

        // Clip the ruler to the hole so a label rolling off the end is cut by the border, not spilled.
        g.enableScissor(interiorLeft, barTop + FRAME_INSET, interiorRight, barBottom - FRAME_INSET);

        // Put the faced bearing's u under the centre mark, then lay copies a period apart across the hole.
        float anchor = ANCHOR_U + Mth.wrapDegrees(playerYaw - ANCHOR_BEARING) * PX_PER_DEGREE;
        float first = cx - anchor;
        while (first > interiorLeft) {
            first -= STRIP_PERIOD;
        }
        for (float sx = first; sx < interiorRight; sx += STRIP_PERIOD) {
            g.blit(STRIP, Math.round(sx), barTop, 0, 0, BAR_WIDTH, BAR_HEIGHT, BAR_WIDTH, BAR_HEIGHT);
        }
        g.disableScissor();

        // Centre "facing" marker, drawn BEFORE the markers so a line over them cannot hide the icon at the moment
        // the player lines up with an objective. Outside every scissor, so its overhang survives.
        g.fill(cx, barTop - 2, cx + 1, barBottom + 2, 0xFFFFFFFF);

        // Markers share the ruler's clip: they sit inside the frame, so a marker near the end is cut like a label.
        g.enableScissor(interiorLeft, barTop + FRAME_INSET, interiorRight, barBottom - FRAME_INSET);

        Waypoint nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Waypoint w : here) {
            double dx = w.x - px;
            double dy = w.y - py;
            double dz = w.z - pz;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = w;
            }
            float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            // No clamping or edge arrows: the ruler covers the whole circle, so a target behind sits near an end.
            float delta = Mth.wrapDegrees(targetYaw - playerYaw);
            int mx = cx + Math.round(delta * PX_PER_DEGREE);
            ItemStack iconStack = iconStack(w.icon);
            if (w.mark.hasPin()) {
                drawPinMarker(g, w.mark, mx, barTop);
            } else if (iconStack != null) {
                drawIconMarker(g, iconStack, mx, barTop);
            } else {
                drawMarker(g, mx, barTop, w.color);
            }
        }
        g.disableScissor();

        int labelY = barBottom + 3;
        if (nearest != null) {
            double hdx = nearest.x - px;
            double hdz = nearest.z - pz;
            int dist = (int) Math.round(Math.sqrt(hdx * hdx + hdz * hdz));
            String name = nearest.name.isBlank() ? I18n.get("hud.dmz_ragnarok.npc.compass.waypoint") : nearest.name;
            String label = I18n.get("hud.dmz_ragnarok.npc.compass.distance", name, dist);
            g.drawCenteredString(font, label, cx, labelY, nearest.color);
            labelY += font.lineHeight;
        }

        // Mission elsewhere: name the dimension, UNDER any local line (both are true when pins are here and a
        // quest is elsewhere, and the bearing line answers "what is nearest").
        if (away != null) {
            String objective = away.name.isBlank()
                    ? I18n.get("hud.dmz_ragnarok.npc.compass.waypoint") : away.name;
            g.drawCenteredString(font, I18n.get("hud.dmz_ragnarok.npc.compass.travel", dimensionName(away.dim)),
                    cx, labelY, away.color);
            labelY += font.lineHeight;
            // the objective's own text below the destination, so "go to the Nether" still says WHY.
            g.drawCenteredString(font, objective, cx, labelY, 0xFFBFC7D5);
        }
    }

    /** Cache of resolved dimension display names, keyed by dimension id. */
    private static final java.util.Map<String, String> DIM_NAME_CACHE = new java.util.HashMap<>();

    /**
     * A readable name for a dimension id. No vanilla registry exists, so a lang key
     * ({@code dimension.<namespace>.<path>}) wins if present, else the path is prettified
     * ({@code minecraft:the_nether} to "The Nether").
     */
    private static String dimensionName(String dimId) {
        String cached = DIM_NAME_CACHE.get(dimId);
        if (cached != null) {
            return cached;
        }
        String name = resolveDimensionName(dimId);
        DIM_NAME_CACHE.put(dimId, name);
        return name;
    }

    private static String resolveDimensionName(String dimId) {
        if (dimId == null || dimId.isBlank()) {
            return "?";
        }
        ResourceLocation loc = ResourceLocation.tryParse(dimId);
        String path = loc == null ? dimId : loc.getPath();
        if (loc != null) {
            String key = "dimension." + loc.getNamespace() + "." + path;
            if (I18n.exists(key)) {
                return I18n.get(key);
            }
        }
        StringBuilder sb = new StringBuilder(path.length());
        boolean upper = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_' || c == '/') {
                sb.append(' ');
                upper = true;
                continue;
            }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }

    /** Cache of resolved marker icon stacks (item id -> stack; null value = unknown id, use the triangle). */
    private static final java.util.Map<String, ItemStack> ICON_CACHE = new java.util.HashMap<>();

    /** Resolve a waypoint's optional icon item id to a stack, or null for blank/unknown ids. */
    private static ItemStack iconStack(String icon) {
        if (icon == null || icon.isBlank()) {
            return null;
        }
        if (!ICON_CACHE.containsKey(icon)) {
            ResourceLocation loc = ResourceLocation.tryParse(icon);
            Item item = loc == null ? null : ForgeRegistries.ITEMS.getValue(loc);
            ICON_CACHE.put(icon, item == null || item == net.minecraft.world.item.Items.AIR
                    ? null : new ItemStack(item));
        }
        return ICON_CACHE.get(icon);
    }

    /**
     * Marker size and top edge. Markers ride inside the frame, so size is fixed to the hole height,
     * {@code BAR_HEIGHT - 2 * FRAME_INSET} = 9px (art is square). Larger would just be clipped.
     */
    private static final int MARKER_SIZE = BAR_HEIGHT - 2 * FRAME_INSET;
    private static final int MARKER_TOP_OFFSET = FRAME_INSET;

    /** A pin marker, blitted at {@link #MARKER_SIZE} from the 50x50 source, filling the bar interior. */
    private static void drawPinMarker(GuiGraphics g, net.shurui.dev.sdu.waypoint.WaypointMark mark, int x,
                                      int barTop) {
        g.blit(mark.texture, x - MARKER_SIZE / 2, barTop + MARKER_TOP_OFFSET, 0, 0,
                MARKER_SIZE, MARKER_SIZE, MARKER_SIZE, MARKER_SIZE);
    }

    /**
     * An item icon marker (e.g. a chest for an airdrop). {@code renderFakeItem} always draws 16x16, so scale
     * 16 down to {@link #MARKER_SIZE}. Translate runs before scale, so the offset is in unscaled pixels.
     */
    private static void drawIconMarker(GuiGraphics g, ItemStack stack, int x, int barTop) {
        g.pose().pushPose();
        g.pose().translate(x - MARKER_SIZE / 2f, barTop + MARKER_TOP_OFFSET, 0);
        g.pose().scale(MARKER_SIZE / 16f, MARKER_SIZE / 16f, 1f);
        g.renderFakeItem(stack, 0, 0);
        g.pose().popPose();
    }

    /** A downward triangle marker, for a waypoint with neither a pin nor an item icon; centred in the bar. */
    private static void drawMarker(GuiGraphics g, int x, int barTop, int color) {
        int height = 4;
        int top = barTop + MARKER_TOP_OFFSET + (MARKER_SIZE - height) / 2;
        for (int i = 0; i < height; i++) {
            int half = height - i;
            g.fill(x - half, top + i, x + half + 1, top + i + 1, color);
        }
    }
}
