package net.shurui.dev.sdu.client.hud.ragnarok;

/**
 * What an element knows about the box it is drawn in, in its own local units (scale already applied by the pose).
 *
 * <p>Variable sized panels use {@link #alignX} and {@link #alignY} to hug the side of the box their placement is
 * anchored to, the way DMZ's notification stack grows upward when it is anchored to the bottom of the screen.
 */
public final class RagnarokHudFrame
{
    public final float width;
    public final float height;
    public final float anchorX;
    public final float anchorY;
    public final boolean preview;
    /** Local Y of the top and bottom screen edges, so content can stop before it leaves the screen. */
    public final float screenTop;
    public final float screenBottom;

    RagnarokHudFrame(float width, float height, float anchorX, float anchorY, boolean preview,
                     float screenTop, float screenBottom)
    {
        this.width = width;
        this.height = height;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.preview = preview;
        this.screenTop = screenTop;
        this.screenBottom = screenBottom;
    }

    /** Local X for content {@code contentWidth} wide: flush left, centred or flush right with the anchor. */
    public float alignX(float contentWidth)
    {
        return (width - contentWidth) * anchorX;
    }

    /** Local Y for content {@code contentHeight} tall: grows down from the top, or up from the bottom when bottom anchored. */
    public float alignY(float contentHeight)
    {
        return anchorY >= 1.0f ? height - contentHeight : 0.0f;
    }

    /** How tall content may grow from {@link #alignY} before it would leave the screen. */
    public float room(float margin)
    {
        return anchorY >= 1.0f ? height - screenTop - margin : screenBottom - margin;
    }
}
