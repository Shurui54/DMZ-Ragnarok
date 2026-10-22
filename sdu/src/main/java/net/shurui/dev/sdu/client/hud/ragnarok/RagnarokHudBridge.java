package net.shurui.dev.sdu.client.hud.ragnarok;

import java.util.List;
import java.util.Map;

import com.dragonminez.client.gui.config.HudEditorScreen;
import com.dragonminez.client.gui.hud.HudStyle;
import com.dragonminez.client.gui.hud.layout.HudElement;
import com.dragonminez.client.gui.hud.layout.HudLayout;
import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.HudPlacement;

import net.minecraft.client.Minecraft;

/**
 * Everything that names DragonMineZ 2.2's HUD layout classes, kept in one class so {@link RagnarokHud} can stay
 * loadable (and fall back) on a DMZ whose layout system changed. Also the logic the HUD mixins delegate to.
 *
 * <h2>DMZ 2.2's HUD system, as found</h2>
 * <ul>
 *   <li>{@code client.gui.hud.HudStyle}: enum of styles (XENOVERSE, COMPACT, MODERN, VANILLA). {@code current()}
 *       reads {@code HudLayoutConfig.style}; each main HUD class draws only while its style is current.</li>
 *   <li>{@code client.gui.hud.layout.HudElement}: enum of placeable elements, each with a persisted id. Extras
 *       (notifications, scouter, Baba timer, boss contribution) are shared by every style.</li>
 *   <li>{@code client.gui.hud.layout.HudLayout}: the per-style element lists, base sizes, default placements and the
 *       box math ({@code resolve} = stored or default placement, anchor + offset in units of screenHeight / 360,
 *       clamped, plus the docking shifts that close gaps when a side meter hides).</li>
 *   <li>{@code common.config.HudLayoutConfig} ({@code config/dragonminez/hud_layout.json}): the style plus
 *       {@code layout[group][elementId] = HudPlacement}, group = style name, or "extras".</li>
 *   <li>{@code client.gui.config.HudEditorScreen}: the editor (style button cycles {@code HudStyle.values()}, an
 *       Extras page, drag, resize handles, hide, undo, reset), opened from DMZ's config menu and HUD tutorial.</li>
 * </ul>
 * There is no registry, so the extension point is the mixin set in {@code net.shurui.dev.sdu.mixin.hud}.
 */
public final class RagnarokHudBridge
{
    private static final HudElement[] BY_SLOT = new HudElement[RagnarokHudSlot.values().length];
    private static HudStyle ragnarok;

    private RagnarokHudBridge() {}

    /** True when our style and every slot constant were appended to DMZ's enums. */
    static boolean probe()
    {
        ragnarok = HudStyle.valueOf(RagnarokHudSlot.STYLE_ENUM_NAME);
        for (RagnarokHudSlot slot : RagnarokHudSlot.values())
            BY_SLOT[slot.ordinal()] = HudElement.valueOf(slot.enumName());
        // the config must keep "ragnarok" rather than normalise it to xenoverse
        return RagnarokHudSlot.STYLE_NAME.equals(com.dragonminez.common.config.HudLayoutConfig.normalizeStyle(RagnarokHudSlot.STYLE_NAME));
    }

    static HudElement element(RagnarokHudSlot slot)
    {
        return BY_SLOT[slot.ordinal()];
    }

    static boolean styleIsRagnarok()
    {
        return ragnarok != null && HudStyle.current() == ragnarok;
    }

    static boolean isPreview()
    {
        return HudLayout.isPreview();
    }

    static String styleName()
    {
        return ConfigManager.getHudLayoutConfig().getStyle();
    }

    static void setStyle(String name)
    {
        ConfigManager.getHudLayoutConfig().setStyle(name);
        ConfigManager.saveHudLayoutConfig();
    }

    /** {x, y, width, height, scale, visible, anchorX, anchorY} of the slot's box, DMZ docking shifts included. */
    static float[] resolve(RagnarokHudSlot slot, RagnarokHudElement element, int screenWidth, int screenHeight)
    {
        HudElement el = element(slot);
        HudLayout.Box box = HudLayout.resolve(el, screenWidth, screenHeight);
        HudPlacement placement = HudLayout.placement(HudStyle.current(), el);
        return new float[] {box.x(), box.y(), box.width(), box.height(), box.scale(), box.visible() ? 1.0f : 0.0f,
                placement.getAnchorX(), placement.getAnchorY()};
    }

    /** A slot's resolved box {x, y, width, height, scale} in GUI pixels, or null when unavailable. */
    public static float[] box(RagnarokHudSlot slot, int screenWidth, int screenHeight)
    {
        if (!RagnarokHud.available() || RagnarokHud.element(slot) == null)
            return null;
        HudLayout.Box box = HudLayout.baseBox(styleFor(slot), element(slot), screenWidth, screenHeight);
        return new float[] {box.x(), box.y(), box.width(), box.height(), box.scale()};
    }

    /** Whether the slot is on the editor page being shown (its style's main list, or the extras list). */
    static boolean inEditorPage(RagnarokHudSlot slot, boolean extras)
    {
        HudStyle style = HudStyle.current();
        List<HudElement> page = extras ? HudLayout.extraElements(style) : HudLayout.elements(style);
        return page.contains(element(slot));
    }

    // ---------------------------------------------------------------- placement access (settings rows, migration)

    /** The stored-or-default scale of a slot's placement, in DMZ's units, or NaN when unavailable. */
    public static float placementScale(RagnarokHudSlot slot)
    {
        if (!RagnarokHud.available())
            return Float.NaN;
        return HudLayout.placement(styleFor(slot), element(slot)).getScale();
    }

    /** Set a slot's placement scale, keeping its anchor and offsets, and save DMZ's layout. */
    public static void setPlacementScale(RagnarokHudSlot slot, float scale)
    {
        if (!RagnarokHud.available())
            return;
        HudStyle style = styleFor(slot);
        HudPlacement placement = HudLayout.placement(style, element(slot)).copy();
        placement.setScale(Math.max(HudLayout.MIN_SCALE, Math.min(HudLayout.MAX_SCALE, scale)));
        HudLayout.setPlacement(style, element(slot), placement);
        ConfigManager.saveHudLayoutConfig();
    }

    /** True when the player (or a migration) already stored a placement for the slot. */
    public static boolean hasStoredPlacement(RagnarokHudSlot slot)
    {
        if (!RagnarokHud.available())
            return false;
        Map<String, HudPlacement> group = ConfigManager.getHudLayoutConfig().getLayout()
                .get(slot.extra() ? "extras" : RagnarokHudSlot.STYLE_NAME);
        return group != null && group.containsKey(slot.id());
    }

    /**
     * Store a placement for a box given in GUI pixels (top-left x, y and a scale where 1 is the element's base size),
     * converted with DMZ's own {@code toPlacement} so it round-trips exactly. Does not save.
     */
    public static void storeBox(RagnarokHudSlot slot, float x, float y, float scale, int screenWidth, int screenHeight)
    {
        if (!RagnarokHud.available())
            return;
        HudElement el = element(slot);
        float[] size = HudLayout.baseSize(styleFor(slot), el);
        float unit = HudLayout.unit(el, screenHeight);
        float s = Math.max(HudLayout.MIN_SCALE, Math.min(HudLayout.MAX_SCALE, scale)) * unit;
        HudPlacement placement = HudLayout.toPlacement(el, x, y, size[0] * s, size[1] * s, s, screenWidth, screenHeight, true);
        HudLayout.setPlacement(styleFor(slot), el, placement);
    }

    /** Save DMZ's HUD layout file. */
    public static void save()
    {
        if (RagnarokHud.available())
            ConfigManager.saveHudLayoutConfig();
    }

    private static HudStyle styleFor(RagnarokHudSlot slot)
    {
        // extras share one group whatever the style; main slots belong to the Ragnarok group
        return slot.extra() ? HudStyle.current() : ragnarok;
    }

    static boolean openEditor(boolean extras)
    {
        Minecraft mc = Minecraft.getInstance();
        HudEditorScreen screen = new HudEditorScreen(mc.screen);
        mc.setScreen(screen);
        if (extras && mc.screen == screen)
            ((net.shurui.dev.sdu.mixin.hud.DmzHudEditorScreenInvoker) screen).sdu$toggleExtras();
        return true;
    }

    // ---------------------------------------------------------------- mixin delegates

    /** HudLayout.elements: the Ragnarok style lists our main elements in place of DMZ's MAIN block. */
    public static void adjustElements(HudStyle style, List<HudElement> list)
    {
        if (!RagnarokHud.available() || style != ragnarok)
            return;
        list.remove(HudElement.MAIN);
        int at = 0;
        for (RagnarokHudSlot slot : RagnarokHudSlot.values())
            if (!slot.extra() && RagnarokHud.listed(slot))
                list.add(at++, element(slot));
    }

    /** HudLayout.extraElements: every listed extra slot joins DMZ's extras page, in every style. */
    public static void adjustExtras(List<HudElement> list)
    {
        if (!RagnarokHud.available())
            return;
        for (RagnarokHudSlot slot : RagnarokHudSlot.values())
            if (slot.extra() && RagnarokHud.listed(slot))
                list.add(element(slot));
    }

    /** HudLayout.baseSize for a slot constant, or null for DMZ's own elements. */
    public static float[] baseSize(HudElement el)
    {
        RagnarokHudSlot slot = RagnarokHudSlot.byDmzOrdinal(el.ordinal());
        if (slot == null)
            return null;
        RagnarokHudElement element = RagnarokHud.element(slot);
        return element == null ? new float[] {16.0f, 16.0f} : new float[] {element.width(), element.height()};
    }

    /** HudLayout.defaultPlacement for a slot constant, or null for DMZ's own elements. */
    public static HudPlacement defaultPlacement(HudElement el)
    {
        RagnarokHudSlot slot = RagnarokHudSlot.byDmzOrdinal(el.ordinal());
        if (slot == null)
            return null;
        RagnarokHudElement element = RagnarokHud.element(slot);
        if (element == null)
            return new HudPlacement(0.0f, 0.0f, 0.0f, 0.0f, 1.0f);
        float[] p = element.defaultPlacement();
        return new HudPlacement(p[0], p[1], p[2], p[3], p[4]);
    }
}
