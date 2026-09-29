package net.shurui.shuruisutilities.client.cosmetics;

import java.util.OptionalDouble;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * A thin, fixed-width line strip for the FLOAT balloon's string.
 *
 * <p>Vanilla {@code RenderType.lineStrip()} (what {@code FishingHookRenderer} uses) sets its line width to
 * {@code OptionalDouble.empty()}, so the line falls back to a width that scales with the window resolution
 * ({@code max(2.5, width / 1920 * 2.5)}). Up close on a balloon string that reads as a thick bar, which is why the
 * tether looked like a stick rather than a fishing line. This is an exact clone of that render type (same
 * {@code rendertype_lines} shader, translucent blend, view-offset layering and item-entity target) with only the
 * line width changed to a fixed thin value, so the string draws as a fine dark line at every resolution. The lines
 * shader still expands each segment along its per-vertex normal, so callers must keep feeding a normal
 * ({@link DefaultVertexFormat#POSITION_COLOR_NORMAL}), exactly as {@code FishingHookRenderer.stringVertex} does.
 *
 * <p>The class extends {@link RenderType} purely for ACCESS: the shader, blend, layering, target, write-mask and
 * cull shards vanilla's {@code LINES}/{@code LINE_STRIP} are built from are {@code protected static} on
 * {@link RenderStateShard}, which {@link RenderType} extends, so a subclass can name them and a plain utility class
 * cannot. It is abstract and its constructor throws: nothing ever instantiates it.
 *
 * <p>The single render type is a constant, not built per frame, so a buffer source compares it by identity and hands
 * back one builder.
 */
public abstract class CosmeticLineType extends RenderType
{
    private CosmeticLineType()
    {
        super("", DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINE_STRIP, 256, false, false,
                () -> {}, () -> {});
        throw new AssertionError("access shim, never instantiated");
    }

    // A fixed 1.5px line width: thinner than vanilla's 2.5 minimum, so the string reads as a fine line and never
    // scales up into a bar on a high-resolution display.
    private static final RenderType BALLOON_STRING = create("dmz_ragnarok_cosmetic_string",
            DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINE_STRIP, 256, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(1.5D)))
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(ITEM_ENTITY_TARGET)
                    .setWriteMaskState(COLOR_DEPTH_WRITE)
                    .setCullState(NO_CULL)
                    .createCompositeState(false));

    /** The thin, fixed-width dark line the balloon string is drawn with. */
    public static RenderType balloonString()
    {
        return BALLOON_STRING;
    }
}
