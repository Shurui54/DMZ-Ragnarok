package net.shurui.shuruisutilities.hologram;

import java.util.ArrayList;
import java.util.List;

/**
 * A named floating hologram: an anchor in a dimension, the text lines, and how they are drawn.
 *
 * <p>Rendered as a stack of vanilla TextDisplay entities by {@link HologramManager}, so no client mod is needed
 * and vanilla players on a multiplayer server see everything.
 *
 * <p>Every style field below defaults to what holograms looked like before there were any, so an existing
 * holograms.json loads and renders exactly as it did: missing fields simply keep these values.
 */
public class Hologram
{
    public String name;

    public String dim; // dimension registry id, e.g. minecraft:overworld

    public double x;

    public double y;

    public double z;

    /** The text, one entry per line. May carry {@code &} codes, {@code <rainbow>}, gradients and placeholders. */
    public List<String> lines = new ArrayList<>();

    /** Uniform size multiplier. Applies to the TEXT lines; a picture has its own size below. */
    public float scale = 1.0f;

    /**
     * How wide a {@code <gif:name>} line is drawn, in blocks.
     *
     * <p>Width rather than a multiplier because it is the number an author can actually picture: 4 means four
     * blocks across, whatever the file happens to be. The height follows from the image's own shape, so a
     * billboard can never come out stretched.
     */
    public float gifSize = 4.0f;

    /** How the text turns to face a viewer: center, vertical, horizontal or fixed. */
    public String billboard = "center";

    /** Background colour as ARGB. Zero is fully transparent, which is the default look. */
    public int background = 0;

    /** Text alpha, 0 to 255. -1 leaves it fully opaque. */
    public int textOpacity = -1;

    /** Drop shadow behind the glyphs. */
    public boolean shadow = false;

    /** Draw through walls. */
    public boolean seeThrough = false;

    /**
     * Light the text itself so it stays readable at night.
     *
     * <p>This is a brightness OVERRIDE on the display, not a light source: it makes the hologram full bright
     * without touching the world. See {@link #backlight} for lighting the surroundings.
     */
    public boolean glow = false;

    /** Outline colour as RGB when the display is given a glow colour, or -1 for none. */
    public int glowColor = -1;

    /** center, left or right. */
    public String align = "center";

    /** Wrap width in pixels. */
    public int lineWidth = 200;

    /** Facing, in degrees, used when {@link #billboard} is fixed. */
    public float yaw = 0.0f;

    public float pitch = 0.0f;

    /** How far away the hologram is still drawn, as a multiplier on the vanilla range. */
    public float viewRange = 1.0f;

    /** A floating item above the top line, by registry id. Empty for none. */
    public String item = "";

    /**
     * Place a real light block at the anchor so the AREA around the hologram is lit.
     *
     * <p>Unlike {@link #glow} this does modify the world, so the block is placed only into air and is taken back
     * out whenever the hologram moves or is deleted.
     */
    public boolean backlight = false;

    /**
     * Nudge for the TEXT lines, in blocks, away from the hologram's anchor.
     *
     * <p>Separate from moving the hologram itself, which takes the pictures with it. This shifts only the text, so
     * a picture can sit behind or beside its caption instead of the two being stuck in one column.
     *
     * <p>Zero on every axis is the old behaviour, so an existing holograms.json is unaffected.
     */
    public float textOffsetX = 0.0f;

    public float textOffsetY = 0.0f;

    public float textOffsetZ = 0.0f;

    /** The same nudge for the {@code <gif:name>} pictures. See {@link #textOffsetX}. */
    public float gifOffsetX = 0.0f;

    public float gifOffsetY = 0.0f;

    public float gifOffsetZ = 0.0f;

    public Hologram()
    {
    }

    public Hologram(String name, String dim, double x, double y, double z)
    {
        this.name = name;
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Copy every style field onto another hologram. Used when an edit rebuilds position and text only. */
    public void copyStyleTo(Hologram other)
    {
        other.scale = scale;
        other.gifSize = gifSize;
        other.billboard = billboard;
        other.background = background;
        other.textOpacity = textOpacity;
        other.shadow = shadow;
        other.seeThrough = seeThrough;
        other.glow = glow;
        other.glowColor = glowColor;
        other.align = align;
        other.lineWidth = lineWidth;
        other.yaw = yaw;
        other.pitch = pitch;
        other.viewRange = viewRange;
        other.item = item;
        other.backlight = backlight;
        other.textOffsetX = textOffsetX;
        other.textOffsetY = textOffsetY;
        other.textOffsetZ = textOffsetZ;
        other.gifOffsetX = gifOffsetX;
        other.gifOffsetY = gifOffsetY;
        other.gifOffsetZ = gifOffsetZ;
    }

    /** True if any line changes over time, so the manager knows whether this one has to be redrawn each tick. */
    public boolean isAnimated()
    {
        for (String line : lines)
            if (HologramText.isAnimated(line))
                return true;
        return false;
    }
}
