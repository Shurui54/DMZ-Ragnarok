package net.shurui.dev.sdu.client.hud.ragnarok;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The suite HUD on top of DragonMineZ 2.2's HUD customization system: the "Ragnarok" style in DMZ's HUD editor, and
 * every suite HUD element positioned, scaled and hidden through DMZ's layout exactly like DMZ's own elements.
 *
 * <h2>How it hooks in</h2>
 * DMZ's styles ({@code HudStyle}) and elements ({@code HudElement}) are enums with no registry, so the sdu mixins
 * append a {@code RAGNAROK} style and one element per {@link RagnarokHudSlot} to them at class init, and teach
 * {@code HudLayout} their sizes, default placements and membership. From there DMZ's own code does the rest: the
 * editor cycles to the Ragnarok style, draws and drags our boxes, and {@code hud_layout.json} stores our placements
 * under the "ragnarok" and "extras" groups. See {@link RagnarokHudBridge}.
 *
 * <h2>Drawing</h2>
 * Each suite overlay stays registered where it always was and calls {@link #draw}, which resolves the element's box
 * through {@code HudLayout.resolve} (so DMZ's docking shifts apply too), runs the appear animation and draws the
 * element in local space. While DMZ's editor is open the overlays stand down and the editor mixin draws previews,
 * exactly as DMZ's own overlays do.
 *
 * <h2>Fallback</h2>
 * If DMZ's layout system is missing or our mixins did not apply, {@link #available()} is false and every element is
 * drawn at its legacy placement ({@link RagnarokHudElement#fallbackBox}) with the old on/off switch for the main
 * panel, so the suite HUD still works, just without DMZ's editor.
 */
public final class RagnarokHud
{
    private static final Logger LOGGER = LogUtils.getLogger();

    // DMZ's own appear constants: NotificationHUD entries ease in at 0.16 s, the side meters at 0.18 s.
    private static final float SLIDE_TAU = 0.16f;
    private static final float FADE_TAU = 0.18f;
    // a hidden element still shows faintly in the editor, as DMZ's HIDDEN_PREVIEW_ALPHA does
    private static final float HIDDEN_PREVIEW_ALPHA = 0.35f;
    // DMZ's notification slide travels this far past the screen edge
    private static final float SLIDE_EXTRA = 8.0f;

    private static final Map<RagnarokHudSlot, RagnarokHudElement> ELEMENTS = new EnumMap<>(RagnarokHudSlot.class);
    private static final Map<RagnarokHudSlot, Ease> APPEAR = new EnumMap<>(RagnarokHudSlot.class);

    private static BooleanSupplier fallbackMainEnabled = () -> true;
    private static Boolean available;
    private static boolean warned;
    private static float alpha = 1.0f;

    private RagnarokHud() {}

    /** Bind an element to its slot. Called from each owner's RegisterGuiOverlaysEvent handler. */
    public static synchronized void register(RagnarokHudElement element)
    {
        ELEMENTS.put(element.slot(), element);
        APPEAR.put(element.slot(), new Ease(element.appear() == RagnarokHudElement.Appear.SLIDE ? SLIDE_TAU : FADE_TAU));
    }

    public static RagnarokHudElement element(RagnarokHudSlot slot)
    {
        return ELEMENTS.get(slot);
    }

    /** Whether the slot has an element bound and it exists for this player. */
    public static boolean listed(RagnarokHudSlot slot)
    {
        RagnarokHudElement element = ELEMENTS.get(slot);
        if (element == null)
            return false;
        try
        {
            return element.listed();
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** The legacy on/off switch for the main panel, used only while DMZ's layout system is unavailable. */
    public static void setFallbackMainEnabled(BooleanSupplier supplier)
    {
        fallbackMainEnabled = supplier;
    }

    /** True when DMZ's HUD layout system is present and carries our style and elements. */
    public static boolean available()
    {
        Boolean known = available;
        if (known == null)
        {
            boolean ok;
            try
            {
                ok = RagnarokHudBridge.probe();
            }
            catch (Throwable t)
            {
                ok = false;
            }
            if (!ok)
                LOGGER.warn("[RagnarokHud] DragonMineZ's HUD layout system is unavailable; the suite HUD uses its legacy placement.");
            available = known = ok;
        }
        return known;
    }

    /** Whether the Ragnarok style (our main panel in place of DMZ's) is the active HUD. */
    public static boolean styleActive()
    {
        if (available())
            return RagnarokHudBridge.styleIsRagnarok();
        return fallbackMainEnabled.getAsBoolean();
    }

    /** Whether DMZ's HUD editor is open (its preview mode). */
    public static boolean editorOpen()
    {
        return available() && RagnarokHudBridge.isPreview();
    }

    /**
     * Select or leave the Ragnarok style and save DMZ's layout. Returns false when DMZ's system is unavailable.
     *
     * @param fallbackStyle the DMZ style to go back to when turning it off
     */
    public static boolean setStyleActive(boolean on, String fallbackStyle)
    {
        if (!available())
            return false;
        RagnarokHudBridge.setStyle(on ? RagnarokHudSlot.STYLE_NAME : fallbackStyle);
        return true;
    }

    /** DMZ's current style name, or null when unavailable. */
    public static String styleName()
    {
        return available() ? RagnarokHudBridge.styleName() : null;
    }

    /** Open DMZ's HUD editor, on its Extras page when asked. Returns false when unavailable. */
    public static boolean openEditor(boolean extras)
    {
        return available() && RagnarokHudBridge.openEditor(extras);
    }

    /**
     * The alpha the current element is being drawn at. An element that sets its own shader colour (a tint) must
     * multiply this in, and must restore {@code (1, 1, 1, alpha())} rather than opaque white.
     */
    public static float alpha()
    {
        return alpha;
    }

    /** Restore the current element's shader colour, after a draw call that reset it to opaque white. */
    public static void restoreColor()
    {
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
    }

    /** The overlay entry point: resolve the slot's box and draw it with the appear animation. */
    public static void draw(RagnarokHudSlot slot, GuiGraphics g, float partialTick, int screenWidth, int screenHeight)
    {
        RagnarokHudElement element = ELEMENTS.get(slot);
        if (element == null)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.options.renderDebug)
            return;
        try
        {
            boolean dmz = available();
            // DMZ's editor draws every element itself while it is open, exactly like DMZ's own overlays stand down
            if (dmz && RagnarokHudBridge.isPreview())
                return;
            if (!slot.extra() && !styleActive())
            {
                APPEAR.get(slot).snap(0.0f);
                return;
            }
            if (!element.listed())
                return;
            boolean live = element.update(false, partialTick);
            float appear = APPEAR.get(slot).update(live ? 1.0f : 0.0f);
            if (appear <= 0.005f)
                return;
            float[] box = dmz ? RagnarokHudBridge.resolve(slot, element, screenWidth, screenHeight)
                    : fallbackBox(element, screenWidth, screenHeight);
            if (box[5] < 0.5f)
                return;
            paint(element, g, partialTick, box, appear, false, screenWidth, screenHeight);
        }
        catch (Throwable t)
        {
            warnOnce(slot, t);
        }
    }

    /** DMZ's editor: draw every listed element of the page being edited at its placed box, with sample content. */
    public static void drawEditorPreview(GuiGraphics g, float partialTick, int screenWidth, int screenHeight, boolean extras)
    {
        if (!available())
            return;
        for (RagnarokHudSlot slot : RagnarokHudSlot.values())
        {
            if (slot.extra() != extras)
                continue;
            RagnarokHudElement element = ELEMENTS.get(slot);
            if (element == null || !RagnarokHudBridge.inEditorPage(slot, extras))
                continue;
            try
            {
                element.update(true, partialTick);
                APPEAR.get(slot).snap(1.0f);
                float[] box = RagnarokHudBridge.resolve(slot, element, screenWidth, screenHeight);
                paint(element, g, partialTick, box, 1.0f, true, screenWidth, screenHeight);
            }
            catch (Throwable t)
            {
                warnOnce(slot, t);
            }
        }
    }

    // box = {x, y, width, height, scale, visible, anchorX, anchorY}
    private static void paint(RagnarokHudElement element, GuiGraphics g, float partialTick, float[] box, float appear,
                              boolean preview, int screenWidth, int screenHeight)
    {
        float x = box[0], y = box[1], w = box[2], h = box[3], scale = box[4];
        boolean visible = box[5] >= 0.5f;
        float anchorX = box[6], anchorY = box[7];
        float drawAlpha = appear * (visible ? 1.0f : HIDDEN_PREVIEW_ALPHA);

        // DMZ's notification slide: eased out of the nearest screen edge, fully off screen at appear 0
        float slideX = 0.0f, slideY = 0.0f;
        if (!preview && appear < 1.0f && element.appear() == RagnarokHudElement.Appear.SLIDE)
        {
            float t = 1.0f - (1.0f - appear) * (1.0f - appear);
            if (anchorX != 0.5f)
            {
                boolean fromRight = x + w / 2.0f >= screenWidth / 2.0f;
                float edge = fromRight ? (screenWidth - x) / scale + SLIDE_EXTRA : (x + w) / scale + SLIDE_EXTRA;
                slideX = (1.0f - t) * edge * (fromRight ? 1.0f : -1.0f);
            }
            else
            {
                boolean fromBottom = y + h / 2.0f >= screenHeight / 2.0f;
                float edge = fromBottom ? (screenHeight - y) / scale + SLIDE_EXTRA : (y + h) / scale + SLIDE_EXTRA;
                slideY = (1.0f - t) * edge * (fromBottom ? 1.0f : -1.0f);
            }
        }

        RagnarokHudFrame frame = new RagnarokHudFrame(element.width(), element.height(), anchorX, anchorY, preview,
                -y / scale, (screenHeight - y) / scale);
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0f);
        g.pose().scale(scale, scale, 1.0f);
        g.pose().translate(slideX, slideY, 0.0f);
        alpha = drawAlpha;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, drawAlpha);
        try
        {
            element.render(g, partialTick, frame);
            g.flush();
        }
        finally
        {
            alpha = 1.0f;
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            g.pose().popPose();
        }
    }

    private static float[] fallbackBox(RagnarokHudElement element, int screenWidth, int screenHeight)
    {
        float[] legacy = element.fallbackBox(screenWidth, screenHeight);
        float[] p = element.defaultPlacement();
        float scale = legacy != null ? legacy[2] : p[4];
        float w = element.width() * scale, h = element.height() * scale;
        float x, y;
        if (legacy != null)
        {
            x = legacy[0];
            y = legacy[1];
        }
        else
        {
            // DMZ's own anchor formula, at a layout unit of 1 (the legacy HUD was in plain GUI pixels)
            x = screenWidth * p[0] + p[2] - w * p[0];
            y = screenHeight * p[1] + p[3] - h * p[1];
        }
        x = Math.max(0.0f, Math.min(x, Math.max(0.0f, screenWidth - w)));
        y = Math.max(0.0f, Math.min(y, Math.max(0.0f, screenHeight - h)));
        return new float[] {x, y, w, h, scale, 1.0f, p[0], p[1]};
    }

    /** A scissor in LOCAL coordinates: GuiGraphics' own scissor ignores the pose, so transform the corners first. */
    public static void enableScissor(GuiGraphics g, float x0, float y0, float x1, float y1)
    {
        Matrix4f pose = g.pose().last().pose();
        Vector3f a = pose.transformPosition(new Vector3f(x0, y0, 0.0f));
        Vector3f b = pose.transformPosition(new Vector3f(x1, y1, 0.0f));
        g.enableScissor(Math.round(Math.min(a.x, b.x)), Math.round(Math.min(a.y, b.y)),
                Math.round(Math.max(a.x, b.x)), Math.round(Math.max(a.y, b.y)));
    }

    private static void warnOnce(RagnarokHudSlot slot, Throwable t)
    {
        if (!warned)
        {
            warned = true;
            LOGGER.warn("[RagnarokHud] Drawing {} failed; continuing without it this frame.", slot.id(), t);
        }
    }

    /** DMZ's HudSmoother, re-stated so the fallback path never needs DMZ's HUD classes: exponential approach, frame-rate independent. */
    static final class Ease
    {
        private static final float MAX_FRAME_SECONDS = 0.25f;
        private final float tau;
        private boolean initialized;
        private float value;
        private long lastNanos;

        Ease(float tau)
        {
            this.tau = tau;
        }

        float update(float target)
        {
            long now = System.nanoTime();
            if (!initialized)
            {
                snap(0.0f);
                lastNanos = now;
            }
            float dt = (now - lastNanos) / 1_000_000_000.0f;
            lastNanos = now;
            if (dt <= 0.0f)
                return value;
            if (dt > MAX_FRAME_SECONDS * 4.0f)
                return value = target;
            dt = Math.min(dt, MAX_FRAME_SECONDS);
            value += (target - value) * (1.0f - (float) Math.exp(-dt / tau));
            if (Math.abs(target - value) <= 0.005f)
                value = target;
            return value;
        }

        void snap(float target)
        {
            initialized = true;
            value = target;
            lastNanos = System.nanoTime();
        }
    }
}
