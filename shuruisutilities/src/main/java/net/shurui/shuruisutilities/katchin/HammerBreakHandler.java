package net.shurui.shuruisutilities.katchin;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


/**
 * Area-break behaviour for the katchin hammers. When a hammer breaks a block, this also breaks the eight blocks
 * around it in the plane perpendicular to the mined face (a 3x3 slab, one block deep), skipping the centre (already
 * broken).
 *
 * <p>Standard Forge 1.20.1 approach: a {@code BlockEvent.BreakEvent} listener on the FORGE bus. This fires
 * server-side while the centre block still exists, so the mined face can be recovered by a ray trace. The extra
 * blocks are removed with {@code Level.removeBlock} + {@code Block.dropResources} rather than
 * {@code ServerPlayerGameMode.destroyBlock}: that path never re-fires {@code BlockEvent.BreakEvent}, so it cannot
 * recurse, and it lets the hammer charge one point of durability per extra block instead of a full break each.
 *
 * <p>A {@link #breaking} re-entrancy guard is kept anyway as belt-and-suspenders. Only blocks the hammer can
 * actually harvest ({@link ItemStack#isCorrectToolForDrops}, which enforces both the pickaxe block set and the tier)
 * are taken; unbreakable blocks (negative hardness) and any block with a block entity (chests, spawners and the
 * like) are skipped so the hammer never destroys containers. Creative mode breaks without drops and without
 * durability cost. Holding crouch mines a single block, for precise work.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class HammerBreakHandler
{
    private HammerBreakHandler() {}

    // Block breaking runs on the single server thread, so a plain flag is enough to stop any accidental recursion.
    private static boolean breaking = false;

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event)
    {
        if (breaking)
        {
            return;
        }
        Player player = event.getPlayer();
        if (player == null)
        {
            return;
        }
        Level level = player.level();
        if (level.isClientSide)
        {
            return;
        }
        ItemStack tool = player.getMainHandItem();
        if (!(tool.getItem() instanceof KatchinHammerItem))
        {
            return;
        }
        // Crouch = single-block precision mining.
        if (player.isCrouching())
        {
            return;
        }

        BlockPos centre = event.getPos();
        Direction face = resolveFace(player, centre);

        breaking = true;
        try
        {
            int broken = 0;
            for (BlockPos pos : planeNeighbours(centre, face))
            {
                if (tryBreak(level, player, tool, pos))
                {
                    broken++;
                }
            }
            // One durability point per extra block broken, applied once. Creative pays nothing.
            if (broken > 0 && !player.getAbilities().instabuild)
            {
                tool.hurtAndBreak(broken, player, p -> p.broadcastBreakEvent(InteractionHand.MAIN_HAND));
            }
        }
        finally
        {
            breaking = false;
        }
    }

    // Break a single neighbour if the hammer may harvest it and it is safe to remove. Returns true when something broke.
    private static boolean tryBreak(Level level, Player player, ItemStack tool, BlockPos pos)
    {
        BlockState state = level.getBlockState(pos);
        if (state.isAir())
        {
            return false;
        }
        // Unbreakable (bedrock, barrier, portal frames use negative hardness).
        if (state.getDestroySpeed(level, pos) < 0.0F)
        {
            return false;
        }
        // Never destroy block entities: chests, furnaces, spawners, our own dragon balls, etc.
        if (level.getBlockEntity(pos) != null)
        {
            return false;
        }
        // Only harvest what the hammer is actually the correct tool for (enforces the pickaxe block set AND the tier).
        if (!tool.isCorrectToolForDrops(state))
        {
            return false;
        }

        if (player.getAbilities().instabuild)
        {
            level.removeBlock(pos, false);
        }
        else
        {
            Block.dropResources(state, level, pos, null, player, tool);
            level.removeBlock(pos, false);
        }
        // Break particles + sound, matching a normal block break.
        level.levelEvent(2001, pos, Block.getId(state));
        return true;
    }

    // The mined face, recovered from a server-side ray trace while the centre block still exists. Falls back to the
    // player's nearest look axis if the trace does not land on the centre block.
    private static Direction resolveFace(Player player, BlockPos centre)
    {
        HitResult hit = player.pick(player.getBlockReach() + 1.0D, 0.0F, false);
        if (hit instanceof BlockHitResult bhr && bhr.getBlockPos().equals(centre))
        {
            return bhr.getDirection();
        }
        Vec3 look = player.getViewVector(1.0F);
        return Direction.getNearest(look.x, look.y, look.z);
    }

    // The eight in-plane neighbours of the centre, for the plane perpendicular to the mined face.
    private static List<BlockPos> planeNeighbours(BlockPos centre, Direction face)
    {
        Direction.Axis axis = face.getAxis();
        List<BlockPos> out = new ArrayList<>(8);
        for (int a = -1; a <= 1; a++)
        {
            for (int b = -1; b <= 1; b++)
            {
                if (a == 0 && b == 0)
                {
                    continue;
                }
                BlockPos pos;
                switch (axis)
                {
                    case Y:  pos = centre.offset(a, 0, b); break; // mining up/down -> horizontal XZ slab
                    case X:  pos = centre.offset(0, a, b); break; // mining east/west -> YZ slab
                    case Z:
                    default: pos = centre.offset(a, b, 0); break; // mining north/south -> XY slab
                }
                out.add(pos);
            }
        }
        return out;
    }
}
