package net.shurui.shuruisutilities.senzu;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * A typed bean pot: a pot that grows one specific {@link BeanType}. The visible state lives in two blockstate
 * properties, {@code planted} and {@code age} (0..3), read by the multipart blockstate to overlay the right plant
 * stage on the pot model. Growth is driven purely by {@link BeanPotBlockEntity}'s server ticker, so the crop is immune
 * to AE2 growth accelerators (which only issue vanilla random ticks) and to bone meal (see {@link SenzuModule}).
 *
 * <p>Right-click plants the matching seed into an empty pot, or harvests a mature (age 3) pot. Breaking the pot drops
 * the pot itself plus one seed if it was planted, never the grown bean; only a proper harvest yields the bean.
 */
public class TypedBeanPotBlock extends BaseEntityBlock
{
    public static final BooleanProperty PLANTED = BooleanProperty.create("planted");
    // 0..3: three stage advances (0->1, 1->2, 2->3). When planted is false, age is meaningless and the model shows an
    // empty pot; the multipart blockstate only overlays a plant stage on the planted=true parts.
    public static final IntegerProperty AGE = IntegerProperty.create("age", 0, 3);
    public static final int MAX_AGE = 3;

    // matches the pot model, which occupies x/z 4..12 and y 0..8 in model units (one block = 16 units).
    private static final VoxelShape SHAPE = Block.box(4.0, 0.0, 4.0, 12.0, 8.0, 12.0);

    private final BeanType type;

    public TypedBeanPotBlock(BeanType type, Properties properties)
    {
        super(properties);
        this.type = type;
        registerDefaultState(stateDefinition.any().setValue(PLANTED, false).setValue(AGE, 0));
    }

    public BeanType type()
    {
        return type;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder)
    {
        builder.add(PLANTED, AGE);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context)
    {
        // a freshly placed pot is always empty; planting is a separate right-click.
        return defaultBlockState().setValue(PLANTED, false).setValue(AGE, 0);
    }

    // BaseEntityBlock defaults to INVISIBLE (it assumes a block-entity renderer). Ours is a normal JSON model, so we
    // must override back to MODEL or the pot would vanish.
    @Override
    public RenderShape getRenderShape(BlockState state)
    {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new BeanPotBlockEntity(pos, state);
    }

    // server ticker, and only while there is growing to do: an empty pot or a fully grown (age 3) pot returns null so
    // it does no per-tick work at all. The ticker is re-evaluated whenever the blockstate changes (planting / harvest /
    // stage advance), so this cheap gate keeps the many idle pots on a server from costing anything.
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> beType)
    {
        if (level.isClientSide)
        {
            return null;
        }
        if (!state.getValue(PLANTED) || state.getValue(AGE) >= MAX_AGE)
        {
            return null;
        }
        return createTickerHelper(beType, SenzuRegistry.BEAN_POT_BE.get(), BeanPotBlockEntity::serverTick);
    }

    // advance the growth stage by one (capped at MAX_AGE). Called by the block entity's ticker when the per-stage
    // counter fills. Flag 3 = block update + client notify so the multipart model repaints.
    public static void advanceAge(Level level, BlockPos pos, BlockState state)
    {
        int age = state.getValue(AGE);
        if (age >= MAX_AGE)
        {
            return;
        }
        level.setBlock(pos, state.setValue(AGE, age + 1), Block.UPDATE_ALL);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit)
    {
        // let the server own the outcome; the client just reports success so the arm swings.
        if (level.isClientSide)
        {
            return InteractionResult.SUCCESS;
        }

        boolean planted = state.getValue(PLANTED);
        int age = state.getValue(AGE);

        // mature pot: harvest it.
        if (planted && age >= MAX_AGE)
        {
            harvest(state, level, pos, player);
            return InteractionResult.CONSUME;
        }

        // empty pot: try to plant whatever is in hand.
        if (!planted)
        {
            ItemStack held = player.getItemInHand(hand);
            BeanType seedType = SenzuRegistry.seedType(held.getItem());
            if (seedType == type)
            {
                plant(state, level, pos, player, held);
                return InteractionResult.CONSUME;
            }
            if (seedType != null)
            {
                // a seed for a DIFFERENT bean: do nothing but say why, naming the type this pot accepts.
                player.displayClientMessage(Component.translatable(
                        "message.dmz_ragnarok.core.senzu_pot_wrong_seed",
                        Component.translatable("senzu.type." + type.lower())), true);
                return InteractionResult.CONSUME;
            }
        }

        // a planted-but-growing pot, or an empty pot with a non-seed in hand: no interaction.
        return InteractionResult.PASS;
    }

    // plant the matching seed: mark the pot planted at stage 0, restart the growth counter, and consume one seed unless
    // the player is in creative (where held stacks are never spent).
    private void plant(BlockState state, Level level, BlockPos pos, Player player, ItemStack seed)
    {
        level.setBlock(pos, state.setValue(PLANTED, true).setValue(AGE, 0), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof BeanPotBlockEntity be)
        {
            be.resetCounter();
        }
        if (!player.isCreative())
        {
            seed.shrink(1);
        }
    }

    // harvest a mature pot: drop the bean (per the type's drop rule) plus 1 or 2 seeds of that type, then reset the pot
    // to empty so it can be replanted. The counter is zeroed so a replant starts fresh.
    private void harvest(BlockState state, Level level, BlockPos pos, Player player)
    {
        for (ItemStack drop : harvestDrops(level))
        {
            popResource(level, pos, drop);
        }
        level.setBlock(pos, state.setValue(PLANTED, false).setValue(AGE, 0), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof BeanPotBlockEntity be)
        {
            be.resetCounter();
        }
    }

    // the harvest yield: one bean plus 1..2 seeds of this type. Bean rule: BURNT always drops bean_burnt and CRACKED
    // always drops bean_cracked (they have no separate weaker variant); every other type drops its base bean, except a
    // configured chance (default 25%) to roll the weaker cracked variant instead.
    private List<ItemStack> harvestDrops(Level level)
    {
        List<ItemStack> out = new ArrayList<>();
        out.add(new ItemStack(beanDrop(level)));
        int seeds = 1 + level.getRandom().nextInt(2); // 1 or 2
        out.add(new ItemStack(SenzuRegistry.seedItem(type), seeds));
        return out;
    }

    private Item beanDrop(Level level)
    {
        if (type == BeanType.BURNT)
        {
            return SenzuRegistry.beanById(type.beanId());
        }
        if (type == BeanType.CRACKED)
        {
            return SenzuRegistry.beanById("bean_cracked");
        }
        if (level.getRandom().nextFloat() < SenzuModule.crackedChance())
        {
            return SenzuRegistry.beanById(type.crackedVariantId());
        }
        return SenzuRegistry.beanById(type.beanId());
    }

    // break drops: the pot itself always, plus one seed if it was planted. Never the grown bean: that is a harvest-only
    // reward. Overriding getDrops means no loot-table JSON is needed and the rule stays in one place.
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params)
    {
        List<ItemStack> drops = new ArrayList<>();
        drops.add(new ItemStack(this));
        if (state.getValue(PLANTED))
        {
            drops.add(new ItemStack(SenzuRegistry.seedItem(type)));
        }
        return drops;
    }
}
