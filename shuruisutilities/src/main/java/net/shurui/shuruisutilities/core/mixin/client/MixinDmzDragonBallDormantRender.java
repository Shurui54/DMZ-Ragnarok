package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.object.Color;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

import net.shurui.shuruisutilities.client.dormancy.DormantBallRender;

/**
 * Draws a DORMANT dragon ball dim, greyed and translucent, so a set turned to stone by a wish looks petrified.
 *
 * <h2>The second design, and why the first was wrong</h2>
 * The first attempt wrapped the {@code MultiBufferSource} handed to {@code GeoBlockRenderer.defaultRender} and tried
 * to recolour each vertex through the buffer. That redirect DID bind (it logged a live line), but the recolour never
 * showed: GeckoLib does not read colour from the buffer. Reading {@code GeoRenderer.defaultRender} in 4.8.3 makes it
 * plain. It resolves the tint ONCE, up front, as explicit floats,
 * <pre>
 *   Color renderColor = getRenderColor(animatable, partialTick, packedLight);
 *   float red/green/blue/alpha = renderColor.get*Float();
 *   ...
 *   actuallyRender(..., red, green, blue, alpha);
 * </pre>
 * and threads those floats all the way down to {@code createVerticesOfQuad}, which writes them straight into the
 * vertex. A wrapped buffer is never consulted for colour, so the wrapper was a no op by construction. The render
 * type is likewise taken from {@code getRenderType(...)} when the caller passes {@code null} (the block path does),
 * and an alpha below one does nothing on the default {@code entityCutoutNoCull} type, which only alpha TESTS, it
 * does not blend.
 *
 * <h2>What this overrides instead, and why here</h2>
 * The two seams GeckoLib itself exposes for exactly this: {@code getRenderColor} and {@code getRenderType}. Both are
 * DEFAULT methods on the {@code GeoRenderer} interface, so the natural target is the interface, but the Mixin
 * annotation processor refuses injectors in an interface ("Injector in interface is unsupported"). So instead this
 * mixin merges concrete OVERRIDES of both into {@code GeoBlockRenderer}, the nearest concrete class every block
 * animatable resolves through. DragonMineZ's {@code DragonBallBlockRenderer} extends {@code GeoBlockRenderer} and
 * overrides NEITHER method, and {@code DragonBallBlockModel} does not override {@code getRenderType} either, so a
 * dragon ball resolves both calls to these merged overrides. Because the block render path calls
 * {@code defaultRender(..., null, null, ...)}, {@code getRenderType} IS invoked (it is only skipped when a non null
 * type is passed), so both seams fire every frame.
 *
 * <ul>
 *   <li>{@link #getRenderColor} returns a grey with reduced alpha for a dormant ball, so the tint floats GeckoLib
 *       bakes into every vertex come out dim stone; for anything else it returns {@code Color.WHITE}, byte for byte
 *       the interface default it replaced.</li>
 *   <li>{@link #getRenderType} returns {@code entityTranslucent(texture)} for a dormant ball, a BLENDING type so the
 *       reduced alpha actually shows; for anything else it delegates to {@code getGeoModel().getRenderType(...)},
 *       byte for byte the interface default.</li>
 * </ul>
 * A GeoBlockRenderer subtype that overrides either method itself is unaffected: its own override is more specific
 * and wins, so the only renderers reaching these are the ones (like the dragon ball) that used the default anyway.
 *
 * <h2>The colour is a multiply, and cannot fully desaturate</h2>
 * {@code getRenderColor} is a per vertex tint that MULTIPLIES the texture, it does not replace it, so a mid grey
 * pulls a bright orange ball down to a dim, low key version of itself rather than to a flat neutral stone: the blue
 * channel of the texture is near zero and a multiply cannot raise it. Combined with the translucency that reads as
 * petrified and clearly dead, which is the intent, but it is a darken toward grey, not a true hue removal. Doing
 * better would need to swap the texture, which is out of scope here.
 *
 * <h2>Observability</h2>
 * The dormant decision, the {@code instanceof} guard and the one time proof line all live in
 * {@link DormantBallRender}, which logs the FIRST time a dormant ball is actually tinted (not merely that a hook is
 * live, the mistake that wasted the first round): look for {@code [dormancy] dragon ball tinted grey and
 * translucent}. If a dormant set's balls are on screen and that line never appears, either the mixin did not apply
 * or the client never learned the set is dormant; the packet receipt log in {@code BallDormancyClient}
 * ({@code [dormancy] client received dormant set sync}) distinguishes the two. Every path is wrapped in
 * {@link DormantBallRender}, so a fault leaves the ball rendering normally rather than crashing the render thread.
 */
@Mixin(GeoBlockRenderer.class)
public abstract class MixinDmzDragonBallDormantRender
{
    // The target method, shadowed so the non dormant fallback can reproduce GeckoLib's exact default type.
    @Shadow(remap = false)
    public abstract GeoModel getGeoModel();

    /**
     * Overrides {@code GeoRenderer.getRenderColor}. Grey and semi transparent for a dormant dragon ball, else the
     * {@code Color.WHITE} the default returned.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Color getRenderColor(GeoAnimatable animatable, float partialTick, int packedLight)
    {
        Color grey = DormantBallRender.dormantColour(animatable);
        return grey != null ? grey : Color.WHITE;
    }

    /**
     * Overrides {@code GeoRenderer.getRenderType}. A blending translucent type for a dormant dragon ball, else the
     * model's own type, exactly what the default returned.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public RenderType getRenderType(GeoAnimatable animatable, ResourceLocation texture,
                                    MultiBufferSource bufferSource, float partialTick)
    {
        RenderType translucent = DormantBallRender.dormantType(animatable, texture);
        if (translucent != null)
            return translucent;
        return getGeoModel().getRenderType(animatable, texture);
    }
}
