package net.shurui.shuruisutilities.saibaman;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.minecraftforge.registries.ForgeRegistries;

/**
 * The saibaman seed crop: a real ground plant (not a pot) that grows through four stages and, when mature, is
 * harvested into a tamed {@link SaibamanPetEntity}. It grows ONLY on {@code dragonminez:rocky_dirt} and breaks if that
 * support is removed.
 *
 * <p>Growth is driven purely by {@link SaibamanCropBlockEntity}'s server ticker (a tick counter), never by vanilla
 * random ticks and never by bone meal (see {@link SaibamanCropEvents}), which is what makes the ki-charge accelerator
 * THE way to speed it up. It paces to WHEAT ({@link SaibamanCropBlockEntity#GROWTH_TICKS}), not to the senzu pots.
 *
 * <p>Harvesting (right-click at {@link #MAX_AGE}) calls {@link SaibamanPetEntity#spawnTamed} with the harvester as
 * owner and consumes the plant. A seed comes back only {@link #SEED_RETURN_CHANCE} of the time, so a farm slowly runs
 * down instead of sustaining itself forever off one seed.
 */
public class SaibamanCropBlock extends BaseEntityBlock
{
    // 0..3: three stage advances (0->1, 1->2, 2->3). Only age 3 can be harvested.
    public static final IntegerProperty AGE = IntegerProperty.create("age", 0, 3);
    public static final int MAX_AGE = 3;

    // the only block a saibaman crop may sit on. The BLOCK id, not the minecraft:dirt tag: DMZ's dirt tag also holds
    // namek and sacred grass, which we do not want, and this block only occurs naturally in the rocky biome anyway.
    private static final ResourceLocation ROCKY_DIRT = new ResourceLocation("dragonminez", "rocky_dirt");

    // a short, walk-through outline that grows a little taller with age. Purely visual (the block has noCollission).
    private static final VoxelShape[] SHAPE_BY_AGE = new VoxelShape[] {
            Block.box(2.0, 0.0, 2.0, 14.0, 4.0, 14.0),
            Block.box(2.0, 0.0, 2.0, 14.0, 8.0, 14.0),
            Block.box(1.0, 0.0, 1.0, 15.0, 13.0, 15.0),
            Block.box(1.0, 0.0, 1.0, 15.0, 16.0, 15.0)
    };

    public SaibamanCropBlock(Properties properties)
    {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AGE, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder)
    {
        builder.add(AGE);
    }

    // BaseEntityBlock defaults to INVISIBLE (it assumes a block-entity renderer). Ours is a normal JSON model, so we
    // override back to MODEL or the crop would vanish.
    @Override
    public RenderShape getRenderShape(BlockState state)
    {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context)
    {
        return SHAPE_BY_AGE[state.getValue(AGE)];
    }

    // may only sit on rocky_dirt. Gates both the seed's placement (BlockItem consults canSurvive) and continued
    // survival (updateShape below breaks it if the support changes).
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos)
    {
        BlockState below = level.getBlockState(pos.below());
        ResourceLocation belowId = ForgeRegistries.BLOCKS.getKey(below.getBlock());
        return ROCKY_DIRT.equals(belowId);
    }

    // if the block it grows on is removed (or replaced with anything but rocky_dirt), the crop breaks into its drops.
    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level,
            BlockPos pos, BlockPos neighborPos)
    {
        return !state.canSurvive(level, pos)
                ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new SaibamanCropBlockEntity(pos, state);
    }

    // server ticker, and only while there is growing to do: a fully grown (age 3) crop returns null so it does no
    // per-tick work. The ticker is re-evaluated on every blockstate change (stage advance), so mature crops on a
    // server cost nothing.
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> beType)
    {
        if (level.isClientSide || state.getValue(AGE) >= MAX_AGE)
        {
            return null;
        }
        return createTickerHelper(beType, SaibamanCropRegistry.SAIBAMAN_CROP_BE.get(),
                SaibamanCropBlockEntity::serverTick);
    }

    // advance the growth stage by one (capped at MAX_AGE). Called by the block entity's ticker when the per-stage
    // counter fills. UPDATE_ALL so the client model repaints and the ticker gate is re-evaluated.
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
            return state.getValue(AGE) >= MAX_AGE ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (state.getValue(AGE) < MAX_AGE)
        {
            return InteractionResult.PASS;
        }
        harvest(state, (ServerLevel) level, pos, player);
        return InteractionResult.CONSUME;
    }

    // chance a harvest returns a seed. See the note at the popResource call for why this is not 1.0.
    private static final float SEED_RETURN_CHANCE = 0.10f;

    // harvest a mature crop: grow the tamed pet, and only if that succeeds consume the plant and maybe return a seed. If
    // the pet fails to spawn the plant is left standing, so a transient failure never eats the crop.
    private void harvest(BlockState state, ServerLevel level, BlockPos pos, Player player)
    {
        // Per-player live-pet cap. Checked BEFORE anything is grown or consumed, so a refused harvest leaves the crop
        // standing and eats no seed: the same fail-without-consuming discipline used across the suite. The count is the
        // reconciled registry total (includes a pet sitting in an unloaded chunk), per server not per network.
        int live = SaibamanPetRegistry.countLive(level.getServer(), player.getUUID());
        if (live >= ConfigSaibamanPet.maxPetsPerPlayer)
        {
            if (player instanceof ServerPlayer serverPlayer)
            {
                serverPlayer.sendSystemMessage(Component.translatable(
                        "message.dmz_ragnarok.core.saibaman.at_cap", live, ConfigSaibamanPet.maxPetsPerPlayer));
            }
            return;
        }
        // the pet's tier: decided by how long a player charged ki beside this crop over its whole growth. Read before
        // the block is cleared. A missing/other block entity (should not happen for a live crop) degrades to tier 1.
        int tier = 1;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof SaibamanCropBlockEntity crop)
        {
            tier = ConfigSaibamanPet.tierFromChargedRatio(crop.chargedGrowthTicks(), crop.totalGrowthTicks());
        }
        SaibamanPetEntity pet = SaibamanPetEntity.spawnTamed(level, pos, player, tier);
        if (pet == null)
        {
            return;
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // A seed back only ONE HARVEST IN TEN. Returning one every time made a farm self-sustaining forever off a
        // single seed, so seeds had no ongoing source of pressure and a player who got one never needed another. At
        // 10% a farm decays without restocking, which is what makes hut-looted seeds and the huts themselves worth
        // going back to. Breaking an immature crop still returns its seed (see getDrops): that is recovering what you
        // planted, not producing a new one.
        if (level.random.nextFloat() < SEED_RETURN_CHANCE)
        {
            popResource(level, pos, new ItemStack(SaibamanCropRegistry.SAIBAMAN_SEED.get()));
        }
    }

    // break drops: always exactly one seed, at any stage, so breaking the crop by hand recovers the seed. The pet is
    // a harvest-only reward, never dropped by breaking. Overriding getDrops keeps the rule in code (no loot JSON).
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params)
    {
        List<ItemStack> drops = new ArrayList<>();
        drops.add(new ItemStack(SaibamanCropRegistry.SAIBAMAN_SEED.get()));
        return drops;
    }
}
