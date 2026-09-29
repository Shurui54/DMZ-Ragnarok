package net.shurui.shuruisutilities.auction;

import net.shurui.shuruisutilities.api.key.AuctionHooks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A placeable block that opens the auction house on right click. The screen is opened server-authoritatively (like
 * the shadow-dragon editor): the interaction runs on the server, which asks the Ragnarok Key through
 * {@link AuctionHooks} (the key's AuctionServer re-checks eligibility and pushes the view packet), so there is no client
 * trust here. Keyless the block is inert.
 */
public class AuctionBlock extends Block
{
    public AuctionBlock(Properties properties)
    {
        super(properties);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit)
    {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer)
        {
            AuctionHooks.get().open(serverPlayer);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
