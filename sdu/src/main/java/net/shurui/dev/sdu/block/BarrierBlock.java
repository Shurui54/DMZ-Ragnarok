package net.shurui.dev.sdu.block;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.shurui.dev.sdu.Config;
import net.shurui.dev.sdu.compat.DmzForms;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.OpenBarrierConfigPacket;
import net.shurui.dev.sdu.registry.ModBlocks;

// Level Barrier: a phase-gate cube only players at a configured DMZ level (and race) can walk through. the
// threshold is per-block in BarrierBlockEntity, shared across a face-adjacent group.
// render: INVISIBLE (no world model, invisible in survival); collision/outline/break gate come from the block
// Properties, not the model. the client BarrierBER draws an animated force-field cube for everyone: the open
// sprite to a viewer who clears this block's gate, the closed sprite to one who does not (per-player).
// op right-click opens the per-block config GUI (S2C OpenBarrierConfigPacket); sneak-right-click reports the gate.
// inherit-on-place: a new barrier adopts the first face-adjacent barrier's level so extending a wall keeps its
// gate; else the Config.barrierDefaultLevel default (or a pick-block value in BLOCK_ENTITY_TAG).
public class BarrierBlock extends Block implements EntityBlock {

    public BarrierBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BarrierBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    // only COLLISION is conditional; outline/occlusion stay a full cube so it's selectable and the BER shows.
    // a player who meets the level+race gets an empty shape (phases through); everyone else (under-level,
    // non-player, raycast/no-entity) hits the full cube. fail-safe: any failure reading DMZ stats = doesn't
    // meet, so the barrier stays solid, never lets everyone through. called often, so cheap checks first.
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (context instanceof EntityCollisionContext ecc && ecc.getEntity() instanceof Player player) {
            int required = Config.barrierDefaultLevel;
            String requiredRace = ""; // no BE -> any race (level-only fallback path)
            if (level.getBlockEntity(pos) instanceof BarrierBlockEntity barrier) {
                required = barrier.getRequiredLevel();
                requiredRace = barrier.getRequiredRace();
            }
            if (passes(player, required, requiredRace)) {
                return Shapes.empty();
            }
        }
        return Shapes.block();
    }

    // does this player clear the gate (level + race). single source of truth for both the server collision
    // gate above and the client BER open/closed visual, so the two can never drift. read failures inside the
    // meets* helpers already return false (fail-safe: stay solid / show closed).
    public static boolean passes(Player player, int requiredLevel, String requiredRace) {
        return meetsLevel(player, requiredLevel) && meetsRace(player, requiredRace);
    }

    // player DMZ level >= required; any read failure returns false (fail-safe: stay solid).
    private static boolean meetsLevel(Player player, int required) {
        try {
            var stats = DmzForms.stats(player);
            return stats != null && stats.getLevel() >= required;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // blank requiredRace = any; else player's DMZ race must match. read failure returns false (fail-safe).
    private static boolean meetsRace(Player player, String requiredRace) {
        if (requiredRace == null || requiredRace.isBlank()) {
            return true; // any race
        }
        try {
            var stats = DmzForms.stats(player);
            if (stats == null) {
                return false;
            }
            String race = stats.getCharacter().getRaceName(); // already lowercase
            return race != null && race.equalsIgnoreCase(requiredRace);
        } catch (Throwable t) {
            return false; // fail-safe: stay solid
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        // creative-only config.
        if (!player.isCreative()) {
            return InteractionResult.PASS;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof BarrierBlockEntity barrier) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.CONSUME;
        }
        if (player.isShiftKeyDown()) {
            String race = barrier.getRequiredRace();
            Object raceLabel = race == null || race.isBlank()
                    ? Component.translatable("message.dmz_ragnarok.npc.barrier.race_any") : race;
            player.sendSystemMessage(Component.translatable(
                    "message.dmz_ragnarok.npc.barrier.requires", barrier.getRequiredLevel(), raceLabel));
            return InteractionResult.CONSUME;
        }
        // open the config GUI, seeded with this barrier's live level + race.
        DmzNet.sendToPlayer(new OpenBarrierConfigPacket(pos, barrier.getRequiredLevel(), barrier.getRequiredRace()), sp);
        return InteractionResult.CONSUME;
    }

    // adopt the first face-adjacent barrier's gate so extending a wall keeps it. server-only.
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof BarrierBlockEntity self)) {
            return;
        }
        for (Direction dir : Direction.values()) {
            BlockPos n = pos.relative(dir);
            if (!level.isLoaded(n) || level.getBlockState(n).getBlock() != ModBlocks.LEVEL_BARRIER.get()) {
                continue;
            }
            if (level.getBlockEntity(n) instanceof BarrierBlockEntity neighbour) {
                self.setRequiredLevel(neighbour.getRequiredLevel());
                self.setRequiredRace(neighbour.getRequiredRace());
                break; // first adjacent barrier wins
            }
        }
    }

    // pick-block clone: stamp the BE NBT onto the item under BLOCK_ENTITY_TAG. vanilla BlockItem placement
    // copies that back into the new BE, so a pick-blocked barrier places pre-configured. client-side; the level
    // is available because the BE syncs it in the update tag.
    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level,
                                       BlockPos pos, Player player) {
        ItemStack stack = super.getCloneItemStack(state, target, level, pos, player);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BarrierBlockEntity barrier) {
            CompoundTag beTag = barrier.saveWithoutMetadata();
            stack.addTagElement(BlockItem.BLOCK_ENTITY_TAG, beTag);
            addConfiguredLore(stack);
        }
        return stack;
    }

    // gray "Configured" lore line on the pick-block item.
    private static void addConfiguredLore(ItemStack stack) {
        CompoundTag display = stack.getOrCreateTagElement("display");
        ListTag lore = display.getList("Lore", 8);
        lore.add(StringTag.valueOf(Component.Serializer.toJson(
                Component.translatable("gui.dmz_ragnarok.npc.barrier.configured_lore")
                        .withStyle(ChatFormatting.GRAY))));
        display.put("Lore", lore);
    }
}
