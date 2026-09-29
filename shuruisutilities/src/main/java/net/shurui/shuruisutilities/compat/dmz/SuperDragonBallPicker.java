package net.shurui.shuruisutilities.compat.dmz;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.dragonminez.common.init.block.custom.DragonBallBlock;
import com.dragonminez.common.init.block.entity.DragonBallBlockEntity;

/**
 * Finds the Super dragon ball a player is aiming at when vanilla's own block ray walked straight past it, and hands
 * back a {@link BlockHitResult} pointing at the ball's real block position.
 *
 * <h2>Why this is needed at all</h2>
 *
 * <p>{@link SuperDragonBallCollision} already gives the Super set a sphere-shaped {@code getShape}, so the outline and
 * the collision are the ball's true size. That is not enough to make it CLICKABLE from the side, because
 * {@code Level.clip} walks the ray cell by cell and only tests a block's shape while the ray is inside that block's
 * OWN cell. Our sphere is drawn from the cell at its base and bulges roughly one cell out horizontally and nearly two
 * up, so a ray aimed at the ball's equator crosses only air cells and reports a miss; the ball answers a right-click
 * only when the player stands under it and the ray happens to enter the base cell. No amount of shape detail fixes
 * that, and neither do {@code getInteractionShape} or {@code getVisualShape}: it is a property of the traversal, not
 * of the shape.
 *
 * <p>So we do the missing half of the trace ourselves. After vanilla has picked, the client mixin asks this class
 * whether the same ray strikes a nearby Super ball's ellipsoid CLOSER than whatever vanilla found. If it does, the
 * client's {@code hitResult} is replaced with a hit on the ball, and every vanilla path downstream (the outline, the
 * arm swing, block breaking, and the {@code ServerboundUseItemOnPacket} that carries the interaction to the server)
 * follows along on its own. That is why there is no packet and no server-side counterpart in this feature: the
 * server is told about a perfectly ordinary block interaction at the ball's position.
 *
 * <h2>The two rules that keep the server agreeing with us</h2>
 *
 * <p><b>The reported location must be near the block's centre.</b> {@code handleUseItemOn} rejects a packet whose hit
 * location is more than 1.0000001 from the centre of the named block on ANY axis, and our real surface point is up to
 * 1.44 out. We therefore report the position {@link #clampToCell clamped} back into that box while keeping the true
 * block position and face. Nothing is lost: {@code DragonBallBlock.use} reads the position, not the exact point.
 *
 * <p><b>The ball must be inside the server's reach test.</b> That test is eye-to-block-CENTRE, not eye-to-surface, and
 * the centre of a ball whose surface we just clipped can sit over a block further away than the surface point did. We
 * apply the same centre-distance test here with {@link #SERVER_REACH_MARGIN}, chosen to stay inside the server's own
 * padding for a creative player too, so we never hand the client a target the server will silently drop.
 *
 * <h2>Cost</h2>
 *
 * <p>Picking runs every frame, so the candidate sweep is cached and rebuilt at most once per client tick. The sweep
 * itself reads block ENTITIES out of the already loaded chunks around the player rather than scanning block states:
 * every dragon ball carries a {@link DragonBallBlockEntity}, those maps hold only the handful of block entities a
 * chunk actually has, and no chunk is loaded to answer the question.
 *
 * <p>Client side only. DragonMineZ is a mandatory dependency, so {@link DragonBallBlockEntity} needs no presence
 * guard.
 */
public final class SuperDragonBallPicker
{
    private SuperDragonBallPicker()
    {
    }

    /** How far from the player to look for Super balls. Comfortably past any reach a player can be given. */
    private static final double SEARCH_RADIUS = 10.0D;

    /**
     * Added to the player's block reach for the eye-to-block-centre test. The server allows the raw BLOCK_REACH
     * attribute plus 1.5; a creative player's {@code getBlockReach()} already includes 0.5 of that, so 1.0 is the
     * largest margin that stays inside the server's allowance in BOTH cases.
     */
    private static final double SERVER_REACH_MARGIN = 1.0D;

    /** How far a reported hit location may sit from the block centre on one axis (the server's limit is 1.0000001). */
    private static final double CELL_CLAMP = 0.999D;

    // candidate cache: the Super ball positions near the player, rebuilt at most once per client tick because pick()
    // is called once per frame. The level is held weakly so a disconnect cannot pin the client world in memory.
    private static WeakReference<Level> cachedLevel = new WeakReference<>(null);
    private static long cachedTick = Long.MIN_VALUE;
    private static final List<BlockPos> CACHED_BALLS = new ArrayList<>();

    /**
     * The Super ball this ray hits, or null when it hits none worth reporting.
     *
     * @param level        the client level
     * @param from         ray origin (the player's eye)
     * @param direction    unit-length look vector
     * @param blockReach   the player's block reach, the furthest a block hit may be
     * @param currentHitDistance distance to whatever vanilla already picked, or {@link Double#MAX_VALUE} on a miss;
     *                     a ball further away than this is behind that hit and must not steal it
     */
    public static BlockHitResult pick(Level level, Vec3 from, Vec3 direction, double blockReach,
            double currentHitDistance)
    {
        List<BlockPos> candidates = nearbyBalls(level, from);
        if (candidates.isEmpty())
        {
            return null;
        }

        double reachLimit = blockReach + SERVER_REACH_MARGIN;
        double reachLimitSqr = reachLimit * reachLimit;
        double best = Math.min(blockReach, currentHitDistance);
        BlockPos bestPos = null;

        for (BlockPos pos : candidates)
        {
            if (from.distanceToSqr(Vec3.atCenterOf(pos)) > reachLimitSqr)
            {
                // the surface may be in range while the block centre is not, and the centre is what the server tests.
                continue;
            }
            double hit = SuperDragonBallCollision.raycast(from, direction, pos, best);
            if (hit >= 0.0D)
            {
                best = hit;
                bestPos = pos;
            }
        }

        // the candidate list is up to one tick old, so confirm the ball is still there before naming it as a target.
        if (bestPos == null || !(level.getBlockState(bestPos).getBlock() instanceof DragonBallBlock))
        {
            return null;
        }

        Vec3 surface = from.add(direction.scale(best));
        Direction face = SuperDragonBallCollision.faceAt(bestPos, surface);
        return new BlockHitResult(clampToCell(bestPos, surface), face, bestPos, false);
    }

    /** True when the block at {@code pos} is one of our Super balls. */
    public static boolean isSuperBall(Level level, BlockPos pos)
    {
        return level.getBlockState(pos).getBlock() instanceof DragonBallBlock ball
                && SuperDragonBallCollision.isSuper(ball.getBallSetId());
    }

    /**
     * The same hit with its location pulled back inside the box the server measures, or the hit unchanged when it was
     * already inside.
     *
     * <p>This is needed for hits VANILLA found, not just the ones {@link #pick} makes. {@code Level.clip} returns the
     * point where the ray met the SHAPE, and our sphere shape reaches about 1.44 out of its own cell, so a ray that
     * did enter the base cell (the only way a Super ball could ever be clicked before) still comes back with a
     * location the server rejects as "too far away from hit block". Those clicks did nothing at all, silently.
     */
    public static BlockHitResult clampToCell(BlockHitResult hit)
    {
        Vec3 location = hit.getLocation();
        Vec3 clamped = clampToCell(hit.getBlockPos(), location);
        if (clamped.equals(location))
        {
            return hit;
        }
        return new BlockHitResult(clamped, hit.getDirection(), hit.getBlockPos(), hit.isInside());
    }

    // pull the reported point back inside the +-1 box the server measures around the block centre, keeping the true
    // block position. Only the point moves, so the face and the block being clicked are still the real ones.
    private static Vec3 clampToCell(BlockPos pos, Vec3 surface)
    {
        Vec3 centre = Vec3.atCenterOf(pos);
        return new Vec3(
                centre.x + Mth.clamp(surface.x - centre.x, -CELL_CLAMP, CELL_CLAMP),
                centre.y + Mth.clamp(surface.y - centre.y, -CELL_CLAMP, CELL_CLAMP),
                centre.z + Mth.clamp(surface.z - centre.z, -CELL_CLAMP, CELL_CLAMP));
    }

    // every Super ball within SEARCH_RADIUS of the eye, recomputed at most once per client tick.
    private static List<BlockPos> nearbyBalls(Level level, Vec3 from)
    {
        long tick = level.getGameTime();
        if (cachedLevel.get() == level && cachedTick == tick)
        {
            return CACHED_BALLS;
        }
        cachedLevel = new WeakReference<>(level);
        cachedTick = tick;
        CACHED_BALLS.clear();

        int minChunkX = Mth.floor(from.x - SEARCH_RADIUS) >> 4;
        int maxChunkX = Mth.floor(from.x + SEARCH_RADIUS) >> 4;
        int minChunkZ = Mth.floor(from.z - SEARCH_RADIUS) >> 4;
        int maxChunkZ = Mth.floor(from.z + SEARCH_RADIUS) >> 4;
        double radiusSqr = SEARCH_RADIUS * SEARCH_RADIUS;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
        {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++)
            {
                // getChunkNow, so an unloaded chunk is simply skipped rather than loaded to answer a render-thread
                // question.
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null)
                {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet())
                {
                    if (!(entry.getValue() instanceof DragonBallBlockEntity ball)
                            || !SuperDragonBallCollision.isSuper(ball.getBallSetId()))
                    {
                        continue;
                    }
                    BlockPos pos = entry.getKey();
                    if (from.distanceToSqr(Vec3.atCenterOf(pos)) <= radiusSqr)
                    {
                        CACHED_BALLS.add(pos.immutable());
                    }
                }
            }
        }
        return CACHED_BALLS;
    }
}
