package net.shurui.shuruisutilities.runes.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import net.shurui.shuruisutilities.runes.RuneItem;

/**
 * Coloured enchantment sheens for the runes.
 *
 * <p>Vanilla's glint is one render type bound to one purple texture, so a differently coloured sheen means a
 * differently textured copy of that render type. These are exact clones of {@code RenderType.glint()},
 * {@code glintDirect()} and {@code glintTranslucent()} with only the texture swapped: same shader, same additive
 * blend, same scrolling UV, so a red or green sheen moves and reads exactly like the stock one.
 *
 * <p>The class extends {@link RenderType} purely for ACCESS. The shader, blend, depth and texturing shards the
 * glint needs are {@code protected static} on {@code RenderStateShard}, which {@code RenderType} extends, so a
 * subclass can name them and a plain utility class cannot. It is abstract and its constructor is private: nothing
 * ever instantiates it.
 *
 * <p>Every type is memoised per texture. A render type is compared by identity when the buffer source looks for its
 * builder, so handing out a fresh instance per frame would miss the fixed buffer registered in
 * {@code MixinRenderBuffersRuneGlint} and the sheen would draw in the wrong order.
 */
public abstract class RuneGlintTypes extends RenderType
{
    private RuneGlintTypes()
    {
        super("", DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS, 256, false, false, () -> {}, () -> {});
        throw new AssertionError("access shim, never instantiated");
    }

    private static final Map<ResourceLocation, RenderType> DIRECT = new LinkedHashMap<>();
    private static final Map<ResourceLocation, RenderType> WORLD = new LinkedHashMap<>();
    private static final Map<ResourceLocation, RenderType> TRANSLUCENT = new LinkedHashMap<>();

    /** Clone of {@code RenderType.glintDirect()}: the sheen used for items drawn in a GUI or on the ground. */
    public static RenderType glintDirect(ResourceLocation texture)
    {
        return DIRECT.computeIfAbsent(texture, tex -> create("dmz_ragnarok_rune_glint_direct_" + name(tex),
                DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS, 256, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_GLINT_DIRECT_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(tex, true, false))
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .setDepthTestState(EQUAL_DEPTH_TEST)
                        .setTransparencyState(GLINT_TRANSPARENCY)
                        .setTexturingState(GLINT_TEXTURING)
                        .createCompositeState(false)));
    }

    /** Clone of {@code RenderType.glint()}: the sheen used when the item is drawn as part of the world. */
    public static RenderType glint(ResourceLocation texture)
    {
        return WORLD.computeIfAbsent(texture, tex -> create("dmz_ragnarok_rune_glint_" + name(tex),
                DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS, 256, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_GLINT_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(tex, true, false))
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .setDepthTestState(EQUAL_DEPTH_TEST)
                        .setTransparencyState(GLINT_TRANSPARENCY)
                        .setTexturingState(GLINT_TEXTURING)
                        .createCompositeState(false)));
    }

    /** Clone of {@code RenderType.glintTranslucent()}, used only when fabulous graphics is on. */
    public static RenderType glintTranslucent(ResourceLocation texture)
    {
        return TRANSLUCENT.computeIfAbsent(texture, tex -> create("dmz_ragnarok_rune_glint_translucent_" + name(tex),
                DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS, 256, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_GLINT_TRANSLUCENT_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(tex, true, false))
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .setDepthTestState(EQUAL_DEPTH_TEST)
                        .setTransparencyState(GLINT_TRANSPARENCY)
                        .setTexturingState(GLINT_TEXTURING)
                        .setOutputState(ITEM_ENTITY_TARGET)
                        .createCompositeState(false)));
    }

    /**
     * The sheen texture this stack should use, or null for the stock purple one.
     *
     * <p>Null covers everything that is not a graded rune, so the mixin can fall straight through to vanilla for
     * every other item in the game.
     */
    public static ResourceLocation textureFor(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
            return null;
        if (stack.getItem() instanceof RuneItem rune)
            return rune.isDormant() ? null : rune.grade().glintTexture();
        return isBoostToken(stack) ? GREEN : null;
    }

    /**
     * The green sheen a BUFF TOKEN wears, so a boost is never mistaken for the plain gem that hands over a lump
     * sum. Recognised by registry id rather than by type: the tokens belong to another addon in the suite, and a
     * texture picker is not worth a compile dependency in a direction the workspace does not otherwise use.
     */
    private static final ResourceLocation GREEN =
            new ResourceLocation("dmz_ragnarok", "textures/misc/rune_glint_green.png");

    private static boolean isBoostToken(ItemStack stack)
    {
        String id = idOf(stack);
        return id.startsWith("tp_buff_token_") || id.startsWith("stat_buff_token_");
    }

    private static String idOf(ItemStack stack)
    {
        ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? "" : key.getPath();
    }

    /**
     * Every render type these sheens can use, so the buffer source can be given a fixed builder for each.
     *
     * <p>Built eagerly from the grades' textures rather than lazily as items are drawn: the fixed buffers are set up
     * once when the renderer is constructed, and a type created after that point would never get one.
     */
    public static List<RenderType> all()
    {
        List<RenderType> out = new ArrayList<>();
        // The token sheen shares the green texture with a rune grade, but list it explicitly: which grades exist is
        // not this class's business, and a sheen with no fixed buffer silently draws nothing.
        out.add(glintDirect(GREEN));
        out.add(glint(GREEN));
        out.add(glintTranslucent(GREEN));
        for (net.shurui.shuruisutilities.runes.RuneGrade grade : net.shurui.shuruisutilities.runes.RuneGrade.values())
        {
            ResourceLocation tex = grade.glintTexture();
            if (tex == null)
                continue;
            out.add(glintDirect(tex));
            out.add(glint(tex));
            out.add(glintTranslucent(tex));
        }
        return out;
    }

    /** A render-type name fragment from a texture path, so the debug name says which sheen it is. */
    private static String name(ResourceLocation tex)
    {
        String path = tex.getPath();
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return path.substring(slash + 1, dot < slash ? path.length() : dot);
    }
}
