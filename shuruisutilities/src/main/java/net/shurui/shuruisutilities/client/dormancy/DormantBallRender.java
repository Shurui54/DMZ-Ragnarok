package net.shurui.shuruisutilities.client.dormancy;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.object.Color;

import com.dragonminez.common.init.block.entity.DragonBallBlockEntity;

/**
 * Decides how a DORMANT dragon ball should be tinted and typed, so a petrified set reads as dim, greyed stone.
 * {@link net.shurui.shuruisutilities.core.mixin.client.MixinDmzDragonBallDormantRender} calls into this from
 * GeckoLib's own {@code getRenderColor} and {@code getRenderType} seams; nothing here touches the buffer, because
 * GeckoLib bakes the tint into every vertex as explicit floats and never reads colour back from the buffer (the
 * earlier wrapper approach, now gone, learned that the hard way).
 *
 * <h2>What it returns</h2>
 * <ul>
 *   <li>{@link #dormantColour(GeoAnimatable)}: a mid grey with reduced alpha for a dormant ball, otherwise
 *       {@code null} to mean "leave GeckoLib's default {@code Color.WHITE}". The grey is a MULTIPLY tint, so it dims
 *       the ball toward stone rather than fully desaturating it (a multiply cannot lift the texture's near zero blue
 *       channel back up), which reads as dead and petrified.</li>
 *   <li>{@link #dormantType(GeoAnimatable, ResourceLocation)}: a blending {@code entityTranslucent} type reusing the
 *       ball's own texture for a dormant ball, otherwise {@code null} to mean "leave GeckoLib's default". The
 *       default is {@code entityCutoutNoCull}, which only alpha TESTS, so without this the reduced alpha would do
 *       nothing.</li>
 * </ul>
 *
 * <h2>Fail safe</h2>
 * The dormant check reads {@link BallDormancyClient}, a client only cache that reports nothing dormant until the
 * server's sync arrives, so a single player world or any moment before the first sync leaves the ball rendering
 * normally. Every path is wrapped, so a fault greys nothing rather than crashing the render thread.
 */
public final class DormantBallRender
{
    private DormantBallRender() {}

    private static final Logger LOG = LogUtils.getLogger();

    /** How visible a dormant ball is, 0 (gone) to 1 (solid). Semi transparent on purpose. */
    public static final float STONE_ALPHA = 0.6F;

    /** Overall brightness of the multiply tint, so the ball comes out a dim, low key version of itself. */
    public static final float STONE_BRIGHTNESS = 0.55F;

    private static final int GREY = clampByte(Math.round(STONE_BRIGHTNESS * 255F));
    private static final int ALPHA = clampByte(Math.round(STONE_ALPHA * 255F));

    // GeckoLib multiplies this into every vertex. Equal channels means a uniform darken toward grey.
    private static final Color STONE = Color.ofRGBA(GREY, GREY, GREY, ALPHA);

    // One time proof that a dormant ball was ACTUALLY tinted, not merely that a hook is live.
    private static volatile boolean loggedTinted = false;

    /** Grey tint for a dormant dragon ball, or null to leave GeckoLib's default colour. */
    public static Color dormantColour(GeoAnimatable animatable)
    {
        if (!isDormantBall(animatable))
            return null;
        if (!loggedTinted)
        {
            loggedTinted = true;
            LOG.info("[dormancy] dragon ball tinted grey and translucent (client sees this set as dormant)");
        }
        return STONE;
    }

    /** Translucent render type reusing the ball's texture for a dormant dragon ball, or null for GeckoLib's default. */
    public static RenderType dormantType(GeoAnimatable animatable, ResourceLocation texture)
    {
        if (texture == null || !isDormantBall(animatable))
            return null;
        return RenderType.entityTranslucent(texture);
    }

    // True only for a DragonBallBlockEntity whose set the client cache marks dormant. Wrapped: a fault greys nothing.
    private static boolean isDormantBall(GeoAnimatable animatable)
    {
        try
        {
            return animatable instanceof DragonBallBlockEntity ball
                    && BallDormancyClient.isDormant(ball.getBallSetId());
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static int clampByte(int value)
    {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}
