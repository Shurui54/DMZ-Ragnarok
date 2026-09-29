package net.shurui.shuruisutilities.gravitychamber;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import net.shurui.shuruisutilities.api.key.GuildRaidHooks;
import net.shurui.shuruisutilities.guilds.GuildManager;
import net.shurui.shuruisutilities.guilds.model.Guild;

/**
 * The guild gravity chamber block. It is a solid full cube with a block entity holding its config and ownership.
 *
 * <ul>
 *   <li><b>Placement</b>: stamps the placer and their guild as the owner (see {@link GuildGravityChamberBlockEntity}).</li>
 *   <li><b>Shift right click</b>: opens the gravity + range GUI (gravity 1x..10x, radius 1..25).</li>
 *   <li><b>Right click</b>: opens the sparring menu (pick a guild or party member, or yourself, to spar a scaled copy).</li>
 * </ul>
 *
 * Both interactions are server-authoritative: the server re-checks {@link GuildGravityChamber#canUse} and pushes an
 * open-screen packet, so the client is never trusted to gate access.
 */
public class GuildGravityChamberBlock extends Block implements EntityBlock
{
    public GuildGravityChamberBlock(Properties properties)
    {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new GuildGravityChamberBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack)
    {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof ServerPlayer sp
                && level.getBlockEntity(pos) instanceof GuildGravityChamberBlockEntity chamber)
        {
            Guild guild = GuildManager.guildOf(sp.getUUID());
            chamber.setOwner(sp.getUUID(), guild == null ? "" : guild.id);
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit)
    {
        if (level.isClientSide)
        {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer sp))
        {
            return InteractionResult.CONSUME;
        }
        open(sp, level, pos, player.isShiftKeyDown());
        return InteractionResult.CONSUME;
    }

    /**
     * Open one of the chamber's two menus for this player: the gravity + range GUI when sneaking, the sparring menu
     * otherwise, after re-checking {@link GuildGravityChamber#canUse}. The menus are private: the decision lives in the
     * Ragnarok Key (its chamber menus, which its own sneak-click handler also calls, because vanilla does not run a
     * block's use() at all when the player sneaks while holding an item), reached through {@link GuildRaidHooks}.
     * Keyless nothing opens.
     *
     * @return true when a menu was opened (or refused with a message), false when this was not a usable chamber.
     */
    static boolean open(ServerPlayer sp, Level level, BlockPos pos, boolean sneaking)
    {
        return GuildRaidHooks.get().openChamber(sp, level, pos, sneaking);
    }
}
