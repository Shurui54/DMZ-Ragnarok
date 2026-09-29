package net.shurui.dev.sdu.shenron;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.OpenShrineGuiPacket;

/**
 * A Shenron-shrine block: a plain non-occluding vanilla-model block (no block entity) that opens the
 * custom {@code ShrineScreen} on right-click. Four variants, one per {@link ShrineColor}; the colour
 * drives which config entry (required items, wishes, display entity) the summon uses. Independent of
 * DMZ's own dragon-ball/wish blocks.
 */
public class ShenronShrineBlock extends Block {

    private final ShrineColor color;

    public ShenronShrineBlock(Properties properties, ShrineColor color) {
        super(properties);
        this.color = color;
    }

    public ShrineColor color() {
        return color;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer sp) {
            ShrineColorConfig cfg = ShrineConfig.color(color);
            boolean hasItems = ShrineSummon.hasRequiredItems(sp, cfg.requiredItems);
            DmzNet.sendToPlayer(new OpenShrineGuiPacket(pos, color, cfg.requiredItems, hasItems), sp);
        }
        return InteractionResult.CONSUME;
    }
}
