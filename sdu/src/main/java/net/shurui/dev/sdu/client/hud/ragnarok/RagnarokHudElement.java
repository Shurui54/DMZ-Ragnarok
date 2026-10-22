package net.shurui.dev.sdu.client.hud.ragnarok;

import net.minecraft.client.gui.GuiGraphics;

/**
 * One suite HUD element, drawn through DragonMineZ's HUD layout (position, scale, visibility and the Ragnarok style
 * all come from DMZ's editor and {@code hud_layout.json}).
 *
 * <p>An element draws in its own LOCAL space: (0, 0) is the top-left of its box and {@link #width()} x
 * {@link #height()} is its size at scale 1. {@link RagnarokHud} applies the box position, the scale and the appear
 * animation around {@link #render}, so an element never needs to know where on the screen it is.
 *
 * <p>Content is split in two calls so an element can fade OUT: {@link #update} snapshots the live state each frame and
 * says whether there is anything to show, and {@link #render} draws the last snapshot. When update starts returning
 * false the old snapshot keeps drawing while the element fades away.
 */
public abstract class RagnarokHudElement
{
    /** How the element comes and goes, matching the two kinds of DMZ HUD animation. */
    public enum Appear
    {
        /** DMZ's notification panels: slide in from the nearest screen edge while fading (tau 0.16 s). */
        SLIDE,
        /** DMZ's side meters: fade in place (tau 0.18 s). */
        FADE
    }

    private final RagnarokHudSlot slot;

    protected RagnarokHudElement(RagnarokHudSlot slot)
    {
        this.slot = slot;
    }

    public final RagnarokHudSlot slot()
    {
        return slot;
    }

    /** Base width in GUI pixels at scale 1 (the editor box). */
    public abstract float width();

    /** Base height in GUI pixels at scale 1 (the editor box). */
    public abstract float height();

    /**
     * Where the element sits until the player moves it: {anchorX, anchorY, offsetX, offsetY, scale}, in DMZ's layout
     * units (anchors are 0, 0.5 or 1; offsets are GUI pixels at a 360 pixel tall screen). Called every frame while no
     * placement is stored, so it may follow the window or another mod's HUD.
     */
    public abstract float[] defaultPlacement();

    /** False when the element does not exist for this player (private feature, module data absent). */
    public boolean listed()
    {
        return true;
    }

    /**
     * Snapshot what to draw. With {@code preview} (DMZ's HUD editor) fill in sample content and return true.
     *
     * @return whether there is live content to show right now
     */
    public abstract boolean update(boolean preview, float partialTick);

    /** Draw the last snapshot in local space. */
    public abstract void render(GuiGraphics g, float partialTick, RagnarokHudFrame frame);

    public Appear appear()
    {
        return Appear.SLIDE;
    }

    /**
     * Legacy placement for when DMZ's layout system is unavailable: {x, y, scale} in GUI pixels, or null to use
     * {@link #defaultPlacement()} at a layout unit of 1.
     */
    public float[] fallbackBox(int screenWidth, int screenHeight)
    {
        return null;
    }
}
