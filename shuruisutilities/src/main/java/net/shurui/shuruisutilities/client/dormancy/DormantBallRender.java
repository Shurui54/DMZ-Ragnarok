package net.shurui.shuruisutilities.client.dormancy;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.object.Color;

import com.dragonminez.common.init.block.entity.DragonBallBlockEntity;

/**
 * Decides how a DORMANT dragon ball is drawn: solid stone, as if the ball had turned to rock. Every ball block is drawn
 * by the suite's {@link net.shurui.shuruisutilities.client.dball.SuDragonBallBlockRenderer} (it replaces DMZ's
 * GeckoLib renderer), which draws a dormant ball in its active shape (the same sphere, or the ball geo in cube style)
 * with {@link #STONE_TEXTURE}; any other GeckoLib path reaches
 * the same look through {@link net.shurui.shuruisutilities.core.mixin.client.MixinDmzDragonBallDormantRender}, which
 * calls the two methods below from GeckoLib's {@code getRenderColor} and {@code getRenderType} seams.
 *
 * <h2>What it returns</h2>
 * <ul>
 *   <li>{@link #dormantType(GeoAnimatable, ResourceLocation)}: the ball's own model drawn with VANILLA'S STONE TEXTURE
 *       in an opaque cutout type, so it reads as grey rock with stone grain and no shine; otherwise {@code null},
 *       meaning GeckoLib's default (the ball's own texture). The ball model's UVs simply sample the tiling stone
 *       texture, so no new asset is needed and nothing extra is drawn: the cost is a texture binding.</li>
 *   <li>{@link #dormantColour(GeoAnimatable)}: plain white (no tint, fully opaque) for a dormant ball, so the stone
 *       texture shows true; otherwise {@code null} for GeckoLib's default. (Until 1.5.x this was a translucent grey
 *       multiply over the ball's own texture, which read as a dim, see-through ball rather than stone.)</li>
 * </ul>
 *
 * <h2>Fail safe</h2>
 * The dormant check reads {@link BallDormancyClient}, a client only cache that reports nothing dormant until the
 * server's sync arrives (it is pushed on every mark and wake, so the look flips live with no relog). A server without
 * the sync, or any moment before it, leaves the ball rendering normally. Every path is wrapped, so a fault changes
 * nothing rather than crashing the render thread.
 */
public final class DormantBallRender
{
    private DormantBallRender() {}

    private static final Logger LOG = LogUtils.getLogger();

    /** Vanilla's stone block texture, used as the dormant ball's skin. */
    public static final ResourceLocation STONE_TEXTURE = new ResourceLocation("minecraft", "textures/block/stone.png");

    // No tint: the stone texture carries the colour. Fully opaque.
    private static final Color SOLID = Color.ofRGBA(255, 255, 255, 255);

    // One time proof that a dormant ball was ACTUALLY drawn as stone, not merely that a hook is live.
    private static volatile boolean loggedStone = false;

    /** Untinted, opaque colour for a dormant dragon ball, or null to leave GeckoLib's default colour. */
    public static Color dormantColour(GeoAnimatable animatable)
    {
        return isDormantBall(animatable) ? SOLID : null;
    }

    /** Opaque stone render type for a dormant dragon ball, or null for GeckoLib's default. */
    public static RenderType dormantType(GeoAnimatable animatable, ResourceLocation texture)
    {
        if (!isDormantBall(animatable))
            return null;
        if (!loggedStone)
        {
            loggedStone = true;
            LOG.info("[dormancy] dragon ball drawn as solid stone (client sees this set as dormant)");
        }
        return RenderType.entityCutoutNoCull(STONE_TEXTURE);
    }

    /** True when the client cache marks this ball set dormant. Never throws. */
    public static boolean isDormantSet(String setId)
    {
        try
        {
            return setId != null && BallDormancyClient.isDormant(setId);
        }
        catch (Throwable t)
        {
            return false;
        }
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
}
