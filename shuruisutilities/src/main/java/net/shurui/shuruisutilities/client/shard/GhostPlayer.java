package net.shurui.shuruisutilities.client.shard;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;

/**
 * A player on a sister server, drawn here as a translucent stand-in.
 *
 * <h2>How it is translucent</h2>
 * A ghost is a real {@code RemotePlayer}, but in this modpack it is NOT drawn by the vanilla player renderer:
 * DragonMineZ cancels that and draws every player through its own GeckoLib renderer. So the see-through look is
 * applied by {@code MixinGeoEntityRendererGhost}, which for GhostPlayer instances only swaps the buffer source
 * the whole draw pulls from for {@code GhostRender}'s wrapper. That wrapper re-issues each texture on a blending
 * render type and scales the alpha to {@code GhostRender.GHOST_ALPHA}, so the body AND every layer (armour, held
 * item, cape) come out barely visible together. See those two classes for the full path.
 *
 * <p>An earlier version leaned on a vanilla trick instead: claim to be invisible but invisible to nobody, and a
 * mixin on {@code LivingEntityRenderer}. Both were removed: the invisible trick did not survive the live modpack,
 * and the {@code LivingEntityRenderer} hook never ran at all, because DragonMineZ draws players through GeckoLib,
 * not through that renderer. Name tag behaviour is unchanged by the removal: a name tag keys off
 * {@code isInvisibleTo}, not {@code isInvisible}, and neither is overridden any more.
 *
 * <h2>Why it is inert</h2>
 * This exists only to be looked at. It never ticks toward anything, cannot be pushed, has no physics and takes
 * no damage, because there is no player behind it to be affected: they are on another server. Anything that let
 * a ghost act on the world would be a way to reach across servers, which is exactly what must not happen.
 */
public class GhostPlayer extends RemotePlayer
{
    public GhostPlayer(ClientLevel level, GameProfile profile)
    {
        super(level, profile);
        // Off block collision only. noPhysics does NOT stop gravity, does NOT set the on-ground flag, and does
        // NOT stop this entity pushing others, so the falling pose and the shove are handled elsewhere:
        // GhostManager pins the ground flag and gravity per update, and pushEntities() below is a no-op.
        this.noPhysics = true;
    }

    /** Nothing may walk into it. A ghost is a picture of somebody who is not here. */
    @Override
    public boolean isPushable()
    {
        return false;
    }

    /**
     * A ghost must not shove the real player. {@link #isPushable()} only stops OTHERS from pushing this
     * entity, but the ghost still ticks, and {@code LivingEntity.aiStep} calls {@code pushEntities}, which
     * gathers everything {@code EntitySelector.pushableBy(this)} accepts (the local player IS pushable) and
     * pushes THEM away. noPhysics does not touch entity against entity soft collision, so the only reliable
     * stop is to make the ghost's own push step do nothing.
     */
    @Override
    protected void pushEntities()
    {
    }

    @Override
    public boolean isPickable()
    {
        return false;
    }

    /** Drawn at whatever range the ghost radius allows, rather than vanilla's distance culling for players. */
    @Override
    public boolean shouldRenderAtSqrDistance(double distSq)
    {
        return true;
    }

    @Override
    public boolean isSpectator()
    {
        return false;
    }

    @Override
    public boolean isCreative()
    {
        return false;
    }
}
