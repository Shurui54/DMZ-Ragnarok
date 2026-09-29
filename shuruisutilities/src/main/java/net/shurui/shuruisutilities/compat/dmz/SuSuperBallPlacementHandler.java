package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.core.SUConfig;

/**
 * Refuses hand placement of a Super dragon ball within {@link SUConfig#superDragonBallMinPlacementDistance} blocks of
 * another Super dragon ball, so the oversized ~2.9-block spheres can never be stacked into each other. Applies ONLY
 * to Shurui's {@code super} set: the placed block and every neighbour are identified through {@link DragonBallSets}
 * (item -> set id), so Earth, Namek and the other sets place freely as before.
 *
 * <p>WHY {@link BlockEvent.EntityPlaceEvent}. It is cancellable and server-authoritative: on the server, block
 * placement runs through Forge's captured-snapshot path, and cancelling reverts the block AND leaves the held stack
 * at its pre-placement count, so the player's item is not consumed and the client resyncs to the server's state
 * with no desync. A mixin was avoided on purpose (a {@code require = 0} DMZ mixin can silently no-op; this event
 * always fires).
 *
 * <p>WHAT the distance means. We reject when another Super ball sits within Chebyshev distance
 * {@code (minDistance - 1)} of the placement, i.e. inside a cube of that radius, so every placed pair ends up at
 * least {@code minDistance} apart on some axis. At the default 3 that clears the 2.875-block model on the separating
 * axis, so two Super balls never visually overlap. A full seven-ball set still gathers easily: a 3x3 grid at
 * 3-spacing spans only 6 blocks, well inside DMZ's 11-block (radius 5) summon scan, and neighbours placed exactly 3
 * apart pass (the rule rejects strictly-nearer balls only).
 *
 * <p>DMZ's random world scatter ({@code DragonBallsHandler.scatterDragonBalls}) uses raw {@code setBlock} and does
 * NOT fire this event, so scattered Super balls are not spacing-checked. That is harmless: only one Super set (seven
 * balls) scatters, at random XZ across the multi-thousand-block overworld bound, so two landing within three blocks
 * is effectively impossible, and a one-off cosmetic overlap there would not affect play.
 *
 * <p>Only ever registered on the bus when DMZ is present (see {@link SuDragonBallPlacementCompat}), so
 * {@link DragonBallSets} (which references DMZ types) is never classloaded when DMZ is absent.
 */
public final class SuSuperBallPlacementHandler
{
    private static final String TOO_CLOSE_KEY = "message.dmz_ragnarok.core.super_dragonball_too_close";

    @SubscribeEvent
    public void onEntityPlace(BlockEvent.EntityPlaceEvent event)
    {
        LevelAccessor level = event.getLevel();
        if (level.isClientSide())
        {
            return;
        }
        Block placed = event.getPlacedBlock().getBlock();
        if (!SuperDragonBallCollision.isSuper(DragonBallSets.setIdOf(placed.asItem())))
        {
            return;
        }

        int minDistance = SUConfig.superDragonBallMinPlacementDistance;
        if (minDistance <= 1)
        {
            // spacing rule disabled by config.
            return;
        }

        BlockPos origin = event.getPos();
        int radius = minDistance - 1;
        for (int dx = -radius; dx <= radius; dx++)
        {
            for (int dy = -radius; dy <= radius; dy++)
            {
                for (int dz = -radius; dz <= radius; dz++)
                {
                    if (dx == 0 && dy == 0 && dz == 0)
                    {
                        // the placement cell already holds the new ball (captured but set); skip it.
                        continue;
                    }
                    BlockPos other = origin.offset(dx, dy, dz);
                    Block block = level.getBlockState(other).getBlock();
                    if (SuperDragonBallCollision.isSuper(DragonBallSets.setIdOf(block.asItem())))
                    {
                        event.setCanceled(true);
                        if (event.getEntity() instanceof ServerPlayer player)
                        {
                            player.sendSystemMessage(Component.translatable(TOO_CLOSE_KEY, minDistance));
                        }
                        return;
                    }
                }
            }
        }
    }
}
