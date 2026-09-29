package net.shurui.shuruisutilities.dragons;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Eis Shenron (3 stars): freezes everyone within 10 blocks inside ice and ticks freeze damage.
 *
 * <h2>The screen overlay comes from vanilla</h2>
 * Rather than building a custom overlay and a packet to drive it, this raises each victim's vanilla FROZEN TICKS.
 * That value is synced entity data the client already reads, and vanilla's HUD draws the powder snow outline from it
 * ({@code getTicksFrozen} / {@code getPercentFrozen}), which is exactly the overlay asked for. It also means the
 * overlay is correct for every viewer with no work on our side, and clears itself if the effect ends early.
 *
 * <h2>Encasing</h2>
 * Ice is placed only where there was AIR, and every block placed is recorded and restored when the effect ends, so
 * this can never eat a player's build or leave a permanent shell. Anything that is not air is left alone rather than
 * replaced, which is why a target standing in a doorway is only partly encased: that is the safe trade.
 */
public final class DragonMoveEis
{
    private DragonMoveEis() {}

    /** How far the freeze reaches. */
    public static final double RADIUS = 10.0;

    /** Total duration, in ticks. */
    public static final int DURATION_TICKS = 160;

    /** Ticks between damage applications. With the duration above this is 5 damage ticks in total. */
    public static final int DAMAGE_INTERVAL_TICKS = 20;

    /** Number of damage ticks the whole effect applies; the move's total damage is divided across these. */
    public static final int DAMAGE_TICKS = DURATION_TICKS / DAMAGE_INTERVAL_TICKS;

    /** Eis's blue. Shared by the ki blasts and the area dome so the whole move is one colour. */
    public static final int ICE_RGB = 0x9FE7FF;

    /** Wind-up handed to DMZ's area entity, so the charge belongs to the asset. */
    private static final int CAST_TICKS = 20;

    /** How many small blasts scatter outward on cast. Kept low: each is a real entity. */
    private static final int BLAST_COUNT = 8;

    /** Speed of the scattered blasts. */
    private static final float BLAST_SPEED = 0.8f;

    static boolean cast(LivingEntity caster, ServerLevel level)
    {
        // The move's body: a translucent ice-blue ball swelling out of the caster to the whole freeze radius. It is
        // the CAST, so it lasts about as long as the cast does and then it is gone. It used to hold for the full
        // DURATION_TICKS, which left a ten block orb sitting on the caster for eight seconds after the move had
        // already happened: the ball stopped reading as the blast going off and started reading as a bubble the
        // caster was standing in. What the area is under for those eight seconds is the snow below.
        DragonOrbSwell.start(caster, level, RADIUS, ICE_RGB, 0x2A6FA8, 0x0A3A66, CAST_TICKS);
        // The move ALWAYS shows itself, even with nobody in range. Bailing out early meant casting it alone
        // produced no ball, no snow and no sound, which is indistinguishable from the move being broken.
        List<LivingEntity> victims = DragonMoveEffects.targets(caster, level, RADIUS);

        float perTick = net.shurui.shuruisutilities.compat.dmz.DragonDamage.perTick(caster, DAMAGE_TICKS);
        for (LivingEntity victim : victims)
            DragonEffectTicker.add(new FrozenVictim(caster, victim, level, perTick));

        scatterBlasts(caster, level);
        snow(caster, level);
        // and then it keeps snowing, for exactly as long as anyone caught is frozen.
        DragonEffectTicker.add(new SnowField(level, caster.position()));
        level.playSound(null, caster.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.2f, 0.6f);
        return true;
    }

    /**
     * A ring of small blue ki blasts thrown outward from the caster.
     *
     * <p>DMZ's blast entity rather than particles, so they read as ki. They carry NO damage: the move's whole figure
     * is already accounted for by the freeze ticks, and giving the scatter its own damage would quietly push this
     * move past every other one.
     */
    private static void scatterBlasts(LivingEntity caster, ServerLevel level)
    {
        Vec3 from = caster.position().add(0.0, caster.getBbHeight() * 0.6, 0.0);
        for (int i = 0; i < BLAST_COUNT; i++)
        {
            double angle = i * (Math.PI * 2.0 / BLAST_COUNT);
            Vec3 dir = new Vec3(Math.cos(angle), 0.12, Math.sin(angle));
            net.shurui.shuruisutilities.compat.dmz.DmzVisuals.kiBlast(caster, level, from, dir,
                    0.0f, BLAST_SPEED, ICE_RGB, 0x2A6FA8, 0x0A3A66, 0.6f);
        }
    }

    /** Snow across the area, in one throttled burst rather than per-victim. */
    private static void snow(LivingEntity caster, ServerLevel level)
    {
        DragonAreaFx.burst(level, ParticleTypes.SNOWFLAKE, caster.position().add(0.0, 1.0, 0.0),
                40, RADIUS * 0.5, 1.5, RADIUS * 0.5, 0.02);
        DragonAreaFx.ring(level, ParticleTypes.SNOWFLAKE, caster.position(), RADIUS, 0.3, 8, 2, 0.3, 0.01);
    }

    /** Ticks between snowfalls while the field holds. Four a second is weather, not a strobe. */
    private static final int SNOW_INTERVAL_TICKS = 5;

    /**
     * The field the freeze actually lives in: snow over the area for exactly as long as the ice does.
     *
     * <p>This is what carries the move now that the orb is only the cast. It is anchored to the POSITION the move was
     * cast at rather than to the caster, because the freeze radius was measured once from there: following the caster
     * would drift the snow off the people it froze, and they cannot move anyway.
     *
     * <p>Emitted centrally rather than per victim, so a crowd costs the same as one target, and so the area still
     * snows when the move caught nobody at all.
     */
    private static final class SnowField implements DragonEffectTicker.ActiveEffect
    {
        private final ServerLevel level;
        private final Vec3 centre;
        private int ticksLeft = DURATION_TICKS;

        SnowField(ServerLevel level, Vec3 centre)
        {
            this.level = level;
            this.centre = centre;
        }

        @Override
        public boolean tick()
        {
            if (ticksLeft <= 0)
                return false;
            ticksLeft--;
            if (ticksLeft % SNOW_INTERVAL_TICKS != 0)
                return true;
            // Falling from above the field rather than sitting in it: a downward speed and a spawn height over the
            // area is what makes it read as snowfall instead of a cloud of stationary flecks.
            DragonAreaFx.burst(level, ParticleTypes.SNOWFLAKE, centre.add(0.0, 3.0, 0.0),
                    14, RADIUS * 0.6, 1.2, RADIUS * 0.6, 0.02);
            DragonAreaFx.ring(level, ParticleTypes.SNOWFLAKE, centre, RADIUS, 0.2, 8, 1, 0.3, 0.01);
            return true;
        }
    }

    /**
     * One frozen target: holds it still, ticks its damage, keeps its vanilla freeze meter topped up so the overlay
     * stays on, and restores the ice it displaced when it ends.
     */
    static final class FrozenVictim implements DragonEffectTicker.ActiveEffect
    {
        private final LivingEntity caster;
        private final LivingEntity victim;
        private final ServerLevel level;
        private final float damagePerTick;
        private final List<BlockPos> placedIce = new ArrayList<>();
        private int ticksLeft = DURATION_TICKS;

        FrozenVictim(LivingEntity caster, LivingEntity victim, ServerLevel level, float damagePerTick)
        {
            this.caster = caster;
            this.victim = victim;
            this.level = level;
            this.damagePerTick = damagePerTick;
            encase();
        }

        private void encase()
        {
            BlockPos base = victim.blockPosition();
            int height = Math.max(1, (int) Math.ceil(victim.getBbHeight()));
            for (int dy = 0; dy < height; dy++)
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        // Skip the column the entity itself occupies, so it is walled in rather than suffocated.
                        if (dx == 0 && dz == 0)
                            continue;
                        BlockPos pos = base.offset(dx, dy, dz);
                        BlockState state = level.getBlockState(pos);
                        if (!state.isAir())
                            continue; // never replace anything that was already there
                        level.setBlockAndUpdate(pos, Blocks.ICE.defaultBlockState());
                        placedIce.add(pos);
                    }
        }

        @Override
        public boolean tick()
        {
            if (!victim.isAlive() || ticksLeft <= 0)
            {
                release();
                return false;
            }
            ticksLeft--;

            // Keep the vanilla freeze meter pinned so the powder snow overlay stays up for the whole effect; it
            // decays on its own the moment we stop, which is what ends the overlay cleanly.
            victim.setTicksFrozen(victim.getTicksRequiredToFreeze() + 20);

            // Held in place: the "inside ice" part is movement, not just decoration.
            victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 6, false, false, false));
            victim.addEffect(new MobEffectInstance(MobEffects.JUMP, 40, 128, false, false, false));

            if (ticksLeft % DAMAGE_INTERVAL_TICKS == 0 && damagePerTick > 0.0f)
                net.shurui.shuruisutilities.compat.dmz.DragonHurt.hurt(caster, victim, damagePerTick);

            if (ticksLeft <= 0)
            {
                release();
                return false;
            }
            return true;
        }

        @Override
        public void cancel()
        {
            release();
        }

        /** Put back exactly the blocks we replaced and let the freeze meter decay. */
        private void release()
        {
            for (BlockPos pos : placedIce)
                if (level.getBlockState(pos).is(Blocks.ICE))
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            placedIce.clear();
            victim.setTicksFrozen(0);
        }
    }
}
