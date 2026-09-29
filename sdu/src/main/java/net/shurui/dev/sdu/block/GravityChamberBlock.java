package net.shurui.dev.sdu.block;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.shurui.dev.sdu.Config;
import net.shurui.dev.sdu.compat.worldedit.WorldEditBridge;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.OpenGravityChamberConfigPacket;

/**
 * Gravity Chamber (Feature 5): a solid full-cube block that boosts TP gain for players training inside its
 * area and shares a fraction of that gain with everyone else inside. The area is a {@link Config#chamberRadius}
 * cube by default, or an op-captured WorldEdit region.
 *
 * <p>Op UX (no GUI, like the shrine's {@code use()}): sneak-right-click with an active WorldEdit selection
 * captures it as this chamber's region; a plain right-click opens the config GUI. The TP multiplier and shared
 * pool are applied by the forge {@code TPGainEvent} handler.
 */
public class GravityChamberBlock extends Block implements EntityBlock {

    public GravityChamberBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GravityChamberBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!player.isCreative()) {
            return InteractionResult.PASS;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof GravityChamberBlockEntity chamber) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.CONSUME;
        }

        if (player.isShiftKeyDown()) {
            captureRegion(sp, chamber);
            return InteractionResult.CONSUME;
        }

        // Plain op right-click: open the per-block config GUI, seeded with this chamber's live values.
        DmzNet.sendToPlayer(new OpenGravityChamberConfigPacket(
                pos, chamber.getMultiplier(), chamber.getShareFraction(), chamber.getRadius(),
                chamber.getGravity(), chamber.hasRegion()), sp);
        return InteractionResult.CONSUME;
    }

    /**
     * Capture the op's WorldEdit selection as this chamber's region override. WorldEdit is optional: guarded.
     *
     * <p>Split out of {@link #use} because a block's use() never runs when the player sneaks with something in hand,
     * and capturing a selection means holding the WorldEdit wand, so this could not fire from use() at all. The
     * sneak path arrives from {@link net.shurui.dev.sdu.event.ChamberInteractEvents} instead.
     */
    public static void captureRegion(ServerPlayer sp, GravityChamberBlockEntity chamber) {
        if (!WorldEditBridge.isPresent()) {
            sp.sendSystemMessage(Component.translatable(
                    "message.dmz_ragnarok.npc.gravity.no_worldedit", Config.chamberRadius));
            return;
        }
        BlockPos[] sel = WorldEditBridge.getSelection(sp);
        if (sel != null && sel.length == 2 && sel[0] != null && sel[1] != null) {
            chamber.setRegion(sel[0], sel[1]);
            sp.sendSystemMessage(Component.translatable(
                    "message.dmz_ragnarok.npc.gravity.region_captured",
                    fmt(chamber.getRegionMin()), fmt(chamber.getRegionMax())));
        } else {
            sp.sendSystemMessage(Component.translatable(
                    "message.dmz_ragnarok.npc.gravity.no_selection"));
        }
    }

    /**
     * Pick-block clone: stamp this chamber's BE config onto the item under {@link BlockItem#BLOCK_ENTITY_TAG}.
     * Vanilla {@code BlockItem} placement copies that tag back into the new block entity, so a pick-blocked
     * chamber places pre-configured. A gray lore line marks it. Runs client-side; the config is available
     * because the BE syncs it in the update tag.
     */
    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level,
                                       BlockPos pos, Player player) {
        ItemStack stack = super.getCloneItemStack(state, target, level, pos, player);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof GravityChamberBlockEntity chamber) {
            CompoundTag beTag = chamber.saveWithoutMetadata();
            stack.addTagElement(BlockItem.BLOCK_ENTITY_TAG, beTag);
            addConfiguredLore(stack);
        }
        return stack;
    }

    /** Add a single gray "Configured" lore line to the pick-block item (display.Lore JSON list). */
    private static void addConfiguredLore(ItemStack stack) {
        CompoundTag display = stack.getOrCreateTagElement("display");
        ListTag lore = display.getList("Lore", 8);
        lore.add(StringTag.valueOf(Component.Serializer.toJson(
                Component.translatable("gui.dmz_ragnarok.npc.gravitychamber.configured_lore")
                        .withStyle(ChatFormatting.GRAY))));
        display.put("Lore", lore);
    }

    private static String fmt(BlockPos p) {
        return p == null ? "?" : "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }
}
