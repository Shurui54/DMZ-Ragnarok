package net.shurui.shuruisutilities.character;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Rescues a player whose saved position is not somewhere a person can be.
 *
 * <h2>Why this exists</h2>
 * A position is written to {@code playerdata/<uuid>.dat} exactly as the server last held it, with no validation
 * on the way in or the way out. So a single tick of broken physics does not cost a player one death, it costs
 * them the server: they are restored to the same impossible place on every login, fall out of the world, get
 * kicked, and log back in to the identical state. Restarting the game or the whole machine changes nothing,
 * because nothing that is wrong is on their machine. The only escape is somebody moving them, which they cannot
 * do because they cannot stay connected long enough to type.
 *
 * <p>The known way in was the hoverbike's buoyancy spring running with an infinite water surface (fixed at source
 * in {@code HoverbikeEntity}), which threw the bike and its RIDER to a non-finite position. This runs anyway and
 * stays: the cost of the check is a few comparisons on login, and the cost of missing one is a player who cannot
 * play. Any future source of a bad position is covered without having to be predicted.
 *
 * <p>HIGHEST priority so it lands before anything else reads the position, and before the handlers that would
 * cheerfully save the broken value back out.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class PlayerPositionSanitizer
{
    private PlayerPositionSanitizer() {}

    /** How far under the world floor still counts as "in the world". Generous: falling is normal, -1e9 is not. */
    private static final double BELOW_WORLD_SLACK = 512.0D;

    /** Vanilla's hard horizontal limit. Beyond this the coordinate cannot be represented in a chunk position. */
    private static final double MAX_HORIZONTAL = 3.0E7D;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        if (!(player.level() instanceof ServerLevel level))
            return;

        String problem = diagnose(player, level);
        if (problem == null)
            return;

        double badX = player.getX();
        double badY = player.getY();
        double badZ = player.getZ();

        // A vehicle is how the position went wrong in the known case, and a broken one would put them straight
        // back. Off it first, then move them.
        if (player.isPassenger())
            player.stopRiding();
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        player.fallDistance = 0.0F;

        ServerLevel target = safeLevel(level);
        BlockPos spawn = target.getSharedSpawnPos();
        // Top of the column rather than the raw spawn Y, so a spawn point that has been built over does not drop
        // them inside a block and start the whole problem again from a different direction.
        BlockPos safe = target.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn);

        player.teleportTo(target, safe.getX() + 0.5D, safe.getY(), safe.getZ() + 0.5D,
                player.getYRot(), player.getXRot());

        LoggingHandler.sulog.warn(
                "[PositionSanitizer] {} logged in at an impossible position ({}, {}, {} in {}): {}. Moved to {} in {}.",
                player.getGameProfile().getName(), badX, badY, badZ, level.dimension().location(), problem,
                safe, target.dimension().location());
        ChatOutputHandler.chatWarning(player,
                "Your saved position was outside the world, so you have been moved to spawn.");
    }

    /**
     * Whether this player's current position is one this sanitizer would reject (outside the world). Public so the
     * shard arrival placement can ask the same question this handler answers, rather than duplicating the range checks
     * and letting the two drift.
     */
    public static boolean wouldReject(ServerPlayer player)
    {
        return player.level() instanceof ServerLevel level && diagnose(player, level) != null;
    }

    /** What is wrong with where this player is, or null when there is nothing wrong. */
    private static String diagnose(ServerPlayer player, ServerLevel level)
    {
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();

        // Checked first and separately: NaN fails every comparison below, so a range test alone would pass it.
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            return "coordinate is not a finite number";
        if (Math.abs(x) > MAX_HORIZONTAL || Math.abs(z) > MAX_HORIZONTAL)
            return "outside the world border limit";
        if (y < level.getMinBuildHeight() - BELOW_WORLD_SLACK)
            return "below the bottom of the world";
        if (y > level.getMaxBuildHeight() + BELOW_WORLD_SLACK)
            return "above the top of the world";
        return null;
    }

    /**
     * The level to put them in.
     *
     * <p>Their own, normally. The overworld only when their own level cannot be used at all, since moving somebody
     * between dimensions is a bigger intervention than moving them within one and should not happen just because
     * they fell too far.
     */
    private static ServerLevel safeLevel(ServerLevel current)
    {
        if (current != null)
            return current;
        var server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.getLevel(Level.OVERWORLD);
    }
}
