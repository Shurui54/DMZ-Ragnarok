package net.shurui.dev.shuruis_raid_bosses.client.gui.theme;


import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only layout sanity check, off by default; enable with {@code -Ddmz_ragnarok.gui.validate=true}. A screen
 * calls {@link #validate} once after building its widgets and this logs, per violation, the screen, the widget
 * and the offending bounds.
 *
 * <p>Two checks:
 * <ul>
 *   <li><b>Overflow</b>: a widget whose bounds stick out past the parent rectangle.</li>
 *   <li><b>Overlap</b>: two visible widgets whose rectangles intersect (floating dropdowns/popups are excluded
 *       by the caller not passing them in).</li>
 * </ul>
 *
 * <p>Never throws, never affects rendering, only logs. Client-only.
 */
public final class LayoutValidator {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Property gate, cached so we do not re-read it every frame. ONE property turns the check on across the
     * whole suite because the five addons ship as one jar, so a per-tree audit would miss most screens; the
     * legacy per-addon name is still honoured.
     */
    private static final String PROPERTY = "dmz_ragnarok.gui.validate";
    private static final String LEGACY_PROPERTY = "srb.gui.validate";
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty(PROPERTY, "false"))
                    || Boolean.parseBoolean(System.getProperty(LEGACY_PROPERTY, "false"));

    private LayoutValidator() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * Check widgets against the panel's INNER rectangle (outer rect shrunk by {@link GuiTheme#PANEL_INSET} each
     * side), so the border art band is EXCLUDED. Catches the "footer button on the panel border" bug: a button
     * whose bottom lands in the bottom {@link GuiTheme#PANEL_INSET} px is flagged though it is inside the outer
     * rect. Callers pass the OUTER rect (usually {@code 0,0,uiWidth,uiHeight}); the inset is applied here.
     *
     * @param outerX panel outer-left (virtual coords)
     * @param outerY panel outer-top
     * @param outerW panel outer-width
     * @param outerH panel outer-height
     */
    public static void validateInner(Class<?> screenClass, Iterable<? extends GuiEventListener> widgets,
                                     int outerX, int outerY, int outerW, int outerH) {
        int inset = GuiTheme.PANEL_INSET;
        validate(screenClass, widgets, outerX + inset, outerY + inset, outerW - 2 * inset, outerH - 2 * inset);
    }

    /**
     * Check widgets against a parent container rectangle (virtual coords) and log any violation.
     *
     * @param contX container inner-left
     * @param contY container inner-top
     * @param contW container inner-width
     * @param contH container inner-height
     */
    public static void validate(Class<?> screenClass, Iterable<? extends GuiEventListener> widgets,
                                int contX, int contY, int contW, int contH) {
        if (!ENABLED) {
            return;
        }
        String screen = screenClass.getSimpleName();
        List<AbstractWidget> visible = new ArrayList<>();
        for (GuiEventListener child : widgets) {
            if (child instanceof AbstractWidget w && w.visible) {
                visible.add(w);
                int wx = w.getX(), wy = w.getY(), ww = w.getWidth(), wh = w.getHeight();
                if (wx < contX || wy < contY || wx + ww > contX + contW || wy + wh > contY + contH) {
                    LOGGER.warn("[gui-validate] {}: {} at ({},{},{}x{}) overflows container ({},{},{}x{})",
                            screen, describe(w), wx, wy, ww, wh, contX, contY, contW, contH);
                }
            }
        }
        for (int i = 0; i < visible.size(); i++) {
            for (int j = i + 1; j < visible.size(); j++) {
                AbstractWidget a = visible.get(i);
                AbstractWidget b = visible.get(j);
                if (intersects(a, b)) {
                    LOGGER.warn("[gui-validate] {}: {} at ({},{},{}x{}) overlaps {} at ({},{},{}x{})",
                            screen, describe(a), a.getX(), a.getY(), a.getWidth(), a.getHeight(),
                            describe(b), b.getX(), b.getY(), b.getWidth(), b.getHeight());
                }
            }
        }
    }

    private static boolean intersects(AbstractWidget a, AbstractWidget b) {
        return a.getX() < b.getX() + b.getWidth()
                && a.getX() + a.getWidth() > b.getX()
                && a.getY() < b.getY() + b.getHeight()
                && a.getY() + a.getHeight() > b.getY();
    }

    private static String describe(AbstractWidget w) {
        String msg = w.getMessage() == null ? "" : w.getMessage().getString();
        String type = w.getClass().getSimpleName();
        return msg.isEmpty() ? type : type + "('" + msg + "')";
    }
}
