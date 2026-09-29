package net.shurui.shuruisutilities.dragons;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import net.shurui.shuruisutilities.compat.dmz.DragonDamage;
import net.shurui.shuruisutilities.compat.dmz.DragonHurt;

/**
 * Naturon Shenron (7 stars): Dragon Quake. The ground heaves - blocks rise and drop, everything in the radius is
 * damaged and slowed while it lasts.
 *
 * <h2>The ground is faked, not destroyed</h2>
 * Blocks are not actually moved. Each pulse picks surface columns and swaps the top block with the air above it, then
 * puts it back on the next pulse, so the terrain visibly heaves without a single block being lost. Anything it could
 * not restore cleanly is simply never touched: only full, non-entity blocks with air above are eligible, so builds,
 * chests and machines are left alone.
 *
 * <p>This replaced a kamehameha-style beam, which did not suit the seven-star dragon of the devouring earth.
 */
public final class DragonMoveQuake
{
    private DragonMoveQuake() {}

    /** How far the ground shakes. */
    public static final double RADIUS = 12.0;

    /** How long it lasts, in ticks. */
    public static final int DURATION_TICKS = 160;

    /** Ticks between damage applications; with the duration above, 5 damage ticks. */
    public static final int DAMAGE_INTERVAL_TICKS = 20;

    public static final int DAMAGE_TICKS = DURATION_TICKS / DAMAGE_INTERVAL_TICKS;

    /** Ticks between ground pulses. Fast enough to read as a tremor, slow enough not to thrash the world. */
    private static final int HEAVE_INTERVAL_TICKS = 5;

    /** How many columns jump per pulse. Capped: each one is a short-lived display entity. */
    private static final int COLUMNS_PER_PULSE = 14;

    /** How far a block hops, in blocks, and how long the hop takes. Small: it is a tremor, not a launch. */
    private static final double HOP_MIN = 0.15;
    private static final double HOP_MAX = 0.45;
    private static final int HOP_TICKS = 10;

    static boolean cast(LivingEntity caster, ServerLevel level)
    {
        DragonEffectTicker.add(new Quake(caster, level));
        level.playSound(null, caster.blockPosition(), SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.2f, 0.4f);
        return true;
    }

    /** The tremor: heaves the ground in pulses, damages and slows whoever is standing in it. */
    static final class Quake implements DragonEffectTicker.ActiveEffect
    {
        private final LivingEntity caster;
        private final ServerLevel level;
        /** Blocks currently lifted, so every one of them is put back. */
        private final List<BlockPos> raised = new ArrayList<>();
        private int ticksLeft = DURATION_TICKS;

        Quake(LivingEntity caster, ServerLevel level)
        {
            this.caster = caster;
            this.level = level;
        }

        @Override
        public boolean tick()
        {
            if (!caster.isAlive() || ticksLeft <= 0)
            {
                settle();
                return false;
            }
            ticksLeft--;

            if (ticksLeft % HEAVE_INTERVAL_TICKS == 0)
            {
                settle();   // drop what is currently up
                heave();    // then lift a fresh set, so the ground rolls
            }

            if (ticksLeft % DAMAGE_INTERVAL_TICKS == 0)
            {
                float perTick = DragonDamage.perTick(caster, DAMAGE_TICKS);
                for (LivingEntity victim : DragonMoveEffects.targets(caster, level, RADIUS))
                {
                    victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 2, false, true, true));
                    if (perTick > 0.0f)
                        DragonHurt.hurt(caster, victim, perTick);
                }
                level.playSound(null, caster.blockPosition(), SoundEvents.GENERIC_EXPLODE,
                        SoundSource.PLAYERS, 0.7f, 0.4f);
            }

            if (ticksLeft <= 0)
            {
                settle();
                return false;
            }
            return true;
        }

        @Override
        public void cancel()
        {
            settle();
        }

        /**
         * Make a scattering of surface blocks jump.
         *
         * <p>NOTHING IS MOVED. Each pick spawns a block DISPLAY of the same block, hovering a little above where it
         * already is; the real block never leaves its position, so the world it heaves over is byte for byte the
         * world it started as. The display is a client-side render only, which also means none of this sends block
         * updates, drops items, breaks redstone or races with another player editing the same chunk.
         *
         * <p>The previous version genuinely swapped each block with the air above it and swapped it back, which
         * worked but wrote real block changes across a 12 block radius five times a second.
         */
        private void heave()
        {
            var random = level.getRandom();
            for (int i = 0; i < COLUMNS_PER_PULSE; i++)
            {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double dist = random.nextDouble() * RADIUS;
                BlockPos ground = findSurface(
                        caster.blockPosition().offset(
                                (int) Math.round(Math.cos(angle) * dist), 0,
                                (int) Math.round(Math.sin(angle) * dist)));
                if (ground == null)
                    continue;
                BlockState state = level.getBlockState(ground);
                double hop = HOP_MIN + random.nextDouble() * (HOP_MAX - HOP_MIN);
                net.shurui.shuruisutilities.dragons.QuakeHop.spawn(level, ground, state, hop, HOP_TICKS);
                level.playSound(null, ground, state.getSoundType(level, ground, null).getStepSound(),
                        SoundSource.BLOCKS, 0.5f, 0.7f);
            }
        }

        /**
         * Nothing to put back.
         *
         * <p>Kept as a method so every exit still reads as "the ground settles"; the hops expire on their own.
         */
        private void settle()
        {
            raised.clear();
        }

        /**
         * The topmost solid block in a column near the caster's level, or null when there is nothing suitable.
         *
         * <p>Only plain full blocks with air above qualify, and block entities are skipped, so a quake cannot eat a
         * chest, a machine or anything with contents.
         */
        private BlockPos findSurface(BlockPos around)
        {
            for (int dy = 2; dy >= -3; dy--)
            {
                BlockPos pos = around.offset(0, dy, 0);
                BlockState state = level.getBlockState(pos);
                if (state.isAir() || level.getBlockEntity(pos) != null)
                    continue;
                if (!state.isCollisionShapeFullBlock(level, pos))
                    continue;
                if (level.getBlockState(pos.above()).isAir())
                    return pos;
            }
            return null;
        }
    }
}
