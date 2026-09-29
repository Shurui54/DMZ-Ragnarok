package net.shurui.shuruisutilities.client.gui.task;

/**
 * A tiny client-side holder for where Xaero's Minimap drew its coordinate/info block THIS frame, so the quest
 * tracker banner can be anchored directly under it.
 *
 * <p>Xaero exposes no API and no getter for the minimap geometry, so the numbers are captured by a mixin into its
 * public {@code InfoDisplayRenderer.render} (see {@code compat.mixin.MixinXaeroInfoDisplayRenderer}). That method
 * is the one that draws the info lines UNDER the map, so its own inputs tell us exactly where the map sits and
 * where the info lines begin.</p>
 *
 * <h2>Stale-safe by design</h2>
 * The capture is timestamped and {@link #valid()} only trusts it for {@link #EXPIRY_NANOS} (a few frames). If
 * Xaero is absent, toggled off, its minimap hidden, or the hook never bound (an Xaero update reshaping the
 * method), no fresh frame lands and the capture expires, so a reader falls back to a configured default anchor
 * instead of pinning the banner to a position Xaero is no longer drawing at.
 *
 * <p>Only the "under the map" case (the normal top-right minimap) yields a usable below-the-coords anchor; when
 * the minimap sits low enough that Xaero draws its info ABOVE the map, {@link #under()} is false and the reader
 * uses the fallback. Client-only; written on the render thread, read on the render thread.</p>
 */
public final class XaeroMinimapAnchor
{
    private XaeroMinimapAnchor() {}

    /** How long a capture is trusted. ~9 frames at 60fps: long enough to bridge a stutter, short enough that a
     *  minimap that stopped drawing is forgotten almost immediately. */
    public static final long EXPIRY_NANOS = 150_000_000L;

    private static volatile long capturedNanos = 0L;
    private static volatile int mapLeft;
    private static volatile int mapWidth;
    private static volatile int coordTop;
    private static volatile boolean under;
    private static volatile float mapScale = 1.0F;

    /**
     * Record this frame's minimap geometry.
     *
     * <p>Every positional value here is in XAERO'S SCALED space, not ordinary GUI space. Xaero draws its minimap
     * inside a pose scaled by {@code 1 / mapScale} and passes coordinates pre multiplied by {@code mapScale}
     * (verified against {@code MinimapRenderer.renderMinimap}: {@code pose.scale(1/mapScale, ...)},
     * {@code scaledX = x * mapScale}). So the true on screen GUI position of any of these is the captured value
     * DIVIDED by {@code mapScale}. The GUI space getters below ({@link #guiLeft()}, {@link #guiWidth()},
     * {@link #guiCoordBottom(int)}) do that division; the raw getters are kept only for diagnostics. This is the
     * whole point of also capturing {@code mapScale}: without it a reader working in GUI space (the quest tracker)
     * positions the panel with foreign coordinates and it drifts off screen when the GUI scale changes.</p>
     *
     * @param mapLeft  Xaero {@code scaledX}: the minimap's left edge in Xaero scaled space
     * @param mapWidth Xaero {@code size}: the (square) minimap size in Xaero scaled space
     * @param coordTop Xaero scaled Y where the coordinate/info lines BEGIN (map bottom in the under case)
     * @param under    true when Xaero draws the info block under the map (a below-the-coords anchor is meaningful)
     * @param mapScale Xaero's scale factor ({@code guiScale / minimapScale}); GUI space = scaled value / mapScale
     */
    public static void capture(int mapLeft, int mapWidth, int coordTop, boolean under, float mapScale)
    {
        XaeroMinimapAnchor.mapLeft = mapLeft;
        XaeroMinimapAnchor.mapWidth = mapWidth;
        XaeroMinimapAnchor.coordTop = coordTop;
        XaeroMinimapAnchor.under = under;
        XaeroMinimapAnchor.mapScale = mapScale;
        capturedNanos = System.nanoTime();
    }

    /** True when a fresh, usable (under-the-map) capture exists this frame. */
    public static boolean valid()
    {
        return under && capturedNanos != 0L && (System.nanoTime() - capturedNanos) < EXPIRY_NANOS;
    }

    public static boolean under()
    {
        return under;
    }

    /** Raw Xaero scaled left edge. Diagnostics only; readers drawing in GUI space want {@link #guiLeft()}. */
    public static int mapLeft()
    {
        return mapLeft;
    }

    /** Raw Xaero scaled map size. Diagnostics only; readers drawing in GUI space want {@link #guiWidth()}. */
    public static int mapWidth()
    {
        return mapWidth;
    }

    /** Raw Xaero scaled Y where the coordinate/info lines begin. Diagnostics only. */
    public static int coordTop()
    {
        return coordTop;
    }

    /** Xaero's scale factor for the captured frame ({@code guiScale / minimapScale}). */
    public static float mapScale()
    {
        return mapScale;
    }

    /** {@code mapScale}, floored to a small positive value so a zero or negative capture can never divide by zero. */
    private static float safeScale()
    {
        float s = mapScale;
        return s > 0.0001F ? s : 1.0F;
    }

    /** The minimap's left edge in ordinary GUI space (Xaero scaled left divided out by {@code mapScale}). */
    public static int guiLeft()
    {
        return Math.round(mapLeft / safeScale());
    }

    /** The minimap's on screen width in ordinary GUI space (Xaero scaled size divided out by {@code mapScale}). */
    public static int guiWidth()
    {
        return Math.round(mapWidth / safeScale());
    }

    /**
     * GUI space Y just below the last coordinate/info line, for {@code coordLines} single-height lines. Xaero draws
     * each info line 10px tall in its SCALED space, stacked downward from the captured top, so the count is applied
     * in scaled space and the whole thing is then divided out by {@code mapScale} into GUI space. The line count is
     * not knowable from the hook without reaching deep into Xaero internals, so it is a config value
     * ({@code coordLines}) defaulting to the vanilla single coordinates line; bump it if more info displays are
     * enabled under the map.
     */
    public static int guiCoordBottom(int coordLines)
    {
        int scaledBottom = coordTop + Math.max(0, coordLines) * 10;
        return Math.round(scaledBottom / safeScale());
    }
}
