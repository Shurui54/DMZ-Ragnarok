package net.shurui.dev.shuruis_dmz_dungeons.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import javax.annotation.Nullable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

// the real dungeon crate BARREL, placed at generation in place of the promoted share of vanilla barrels a room produced
// (see CrateConversionTask). Reads as a wooden barrel with rarity-coloured BANDS the client colour handler tints, so one
// model + texture set serves every tier. No FACING (a barrel has no front); it carries the sixteen-step ROTATION every
// crate has, so one can sit at an angle rather than square to the grid.
public class CrateBarrelBlock extends Block implements CrateBlock, net.minecraft.world.level.block.EntityBlock {

    public CrateBarrelBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(TIER, 0).setValue(ROTATION, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TIER, ROTATION);
    }

    // BER drawn, like the chest: the crate is a GeckoLib model now, not a chunk mesh cube.
    @Override
    public net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
        return net.minecraft.world.level.block.RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @javax.annotation.Nullable
    @Override
    public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CrateBarrelBlockEntity(pos, state);
    }

    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        float yaw = context.getPlayer() == null ? 0f : context.getPlayer().getYRot();
        return this.defaultBlockState().setValue(ROTATION, CrateBlock.rotationFromYaw(yaw));
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
}
