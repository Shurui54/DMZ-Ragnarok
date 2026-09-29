package net.shurui.dev.shuruis_dmz_dungeons.block;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.shurui.dev.shuruis_dmz_dungeons.Config;
import net.shurui.dev.shuruis_dmz_dungeons.network.SddNet;
import net.shurui.dev.shuruis_dmz_dungeons.registry.ModBlockEntities;

import javax.annotation.Nullable;

// the advanced NPC spawn block. own model is invisible; AdvancedSpawnerRenderer draws the spawner cage (or
// the disguise when set). right-click opens the editor via the network layer so the server owns the config.
public class AdvancedSpawnerBlock extends BaseEntityBlock {

    public AdvancedSpawnerBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AdvancedSpawnerBlockEntity(pos, state);
    }

    // invisible: the BE renderer draws the cage or disguise instead
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            // let the server decide; it authorises and sends the open-editor packet
            return InteractionResult.SUCCESS;
        }
        // creative-only on purpose: survival players (ops too) walk through them like decoration, so a
        // disguised spawner can't be opened mid-dungeon
        if (!player.isCreative()) {
            return InteractionResult.PASS;
        }
        if (!Config.canEdit(player)) {
            player.displayClientMessage(Component.translatable("message.dmz_ragnarok.dungeons.no_permission"), true);
            return InteractionResult.CONSUME;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof AdvancedSpawnerBlockEntity be) {
            // open the editor pre-filled with this block's config
            SddNet.openSpawnerEditor(serverPlayer, pos, be.getConfig());
        }
        return InteractionResult.CONSUME;
    }

    // creative pick-block: hand back a spawner item carrying this block's full config, so re-placing it
    // rebuilds the configured spawner. runs CLIENT-side (client BE has the config, synced in getUpdateTag).
    // stamp it under BlockEntityTag so vanilla BlockItem re-applies it on placement. no config -> plain item.
    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level, BlockPos pos, Player player) {
        ItemStack stack = super.getCloneItemStack(state, target, level, pos, player);
        if (!(level.getBlockEntity(pos) instanceof AdvancedSpawnerBlockEntity be)) {
            return stack;
        }
        CompoundTag config = be.saveConfigTag();
        // wrap under BlockEntityTag so BlockItem restores it into the placed BE's NBT
        CompoundTag beTag = new CompoundTag();
        beTag.put("Config", config);
        stack.addTagElement(BlockItem.BLOCK_ENTITY_TAG, beTag);

        // lore: show what it'll spawn (saved NPC ref, else entity id)
        String ref = config.getString("SavedNpcRef");
        String label = (ref != null && !ref.isBlank()) ? ref : config.getString("EntityType");
        if (label != null && !label.isBlank()) {
            stack.getOrCreateTagElement("display");
            var lore = new net.minecraft.nbt.ListTag();
            lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
                    Component.translatable("item." + net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons.MODID + ".advanced_spawner.configured", label)
                            .withStyle(ChatFormatting.GRAY))));
            stack.getTagElement("display").put("Lore", lore);
        }
        return stack;
    }

    // server-side ticker drives the spawn logic in the BE
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.ADVANCED_SPAWNER.get(), AdvancedSpawnerBlockEntity::serverTick);
    }
}
