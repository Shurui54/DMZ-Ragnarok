package net.shurui.dev.shuruis_dmz_dungeons.block;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

// the real dungeon crate CHEST, placed at generation in place of every vanilla chest a room template produced (see
// CrateConversionTask). Not an inventory: the rarity-coloured LATCH is a greyscale overlay the client colour handler
// tints, so one model + texture set serves every tier.
//
// FACING is copied from the vanilla chest it replaces so the latch faces the way the chest opened. Right-click forwards
// to DungeonCrates, which resolves the floor and opens the opener's per-player instanced reward, gated behind Shurui's Key.
public class CrateChestBlock extends HorizontalDirectionalBlock implements CrateBlock, EntityBlock {

    public CrateChestBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(TIER, 0)
                .setValue(ROTATION, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING, TIER, ROTATION);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // the sixteen-step turn comes from where the placer looks, so a crate can sit at an angle into a corner, not
        // snap to a wall. FACING is kept in step for the generation pass and anything reading a cardinal direction.
        float yaw = context.getPlayer() == null ? 0f : context.getPlayer().getYRot();
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(ROTATION, CrateBlock.rotationFromYaw(yaw));
    }

    // BER-drawn by CrateChestRenderer (same render shape vanilla chests use). The block's own model is only break
    // particles now; the item form stays a self-contained cube.
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CrateChestBlockEntity(pos, state);
    }

    // carry a chosen creative variant from the item onto the placed crate, so a picked look survives placement.
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable net.minecraft.world.entity.LivingEntity placer,
                           net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || stack.getTag() == null || !stack.getTag().contains(CrateBlock.VARIANT_TAG)) {
            return;
        }
        net.minecraft.nbt.CompoundTag tag = stack.getTag().getCompound(CrateBlock.VARIANT_TAG);
        if (level.getBlockEntity(pos) instanceof CrateBlockEntity be) {
            be.setVariant(tag.getInt("Tier"), tag.getInt("Metal"),
                    tag.contains("Skin") ? tag.getString("Skin") : "");
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                 BlockHitResult hit) {
        // CREATIVE + sneaking only: step a hand-placed crate through the tiers, so a world can be laid out by hand
        // without a different item per tier. A survival player sneaking still opens it.
        if (player.isShiftKeyDown() && player.isCreative()) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof CrateBlockEntity be) {
                int next = (Math.max(0, be.variantTier()) + 1) % CrateTier.values().length;
                be.setVariant(next, Math.max(0, be.variantMetal()), be.variantSkin());
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.dmz_ragnarok.dungeons.crate_tier_set",
                        net.minecraft.network.chat.Component.translatable(
                                CrateTier.byOrdinal(next).langKey())));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide && player instanceof ServerPlayer sp && level instanceof ServerLevel sl) {
            net.shurui.dev.shuruis_dmz_dungeons.api.key.DungeonRewardHooks.get().openCrate(sp, sl, pos, tierOrdinal(state));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    // solid full cube; the latch is a cutout overlay, so lighting behaves like a normal opaque block.
    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return false;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return false;
    }
}
