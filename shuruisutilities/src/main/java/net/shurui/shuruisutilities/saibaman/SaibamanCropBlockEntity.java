package net.shurui.shuruisutilities.saibaman;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.ResourceSyncS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;

/**
 * Backs a planted {@link SaibamanCropBlock}. Holds an int growth counter; the visible stage lives in the block's
 * {@code age} state (0..3). The counter is advanced ONLY by the server ticker here, never by vanilla random ticks,
 * which is what makes the crop immune to AE2-style growth accelerators and leaves ki charging as the only speed-up.
 *
 * <p>Base pacing is WHEAT's, not senzu's: {@link #GROWTH_TICKS} (31200 ticks, about twenty-six minutes) split evenly
 * across the three stage advances. It used to read the senzu pot's four-hour config, which meant retuning senzu
 * silently retuned saibamen; the two no longer pace together.
 *
 * <p>Ki-charge accelerator: once per {@link #CHARGE_INTERVAL} ticks the crop scans a {@link #CHARGE_RADIUS}-block
 * sphere for players who are actively charging ki (DragonMineZ {@code Status.isChargingKi()}, NOT the technique
 * {@code isActionCharging()}). If any are found the crop grows at {@link #CHARGE_MULTIPLIER}x for that interval, and
 * every charging player pays a steady ki trickle. The speed bonus does NOT stack with more players (it is a flat gate,
 * so a crowd cannot trivialise the grow), but each charging player is drained and resynced, so everyone helping pays.
 */
public class SaibamanCropBlockEntity extends BlockEntity
{
    private int counter;

    // Total ticks for a seed to reach harvest, WHEAT'S PACE rather than the senzu pot's four hours.
    //
    // Wheat is random-tick driven and so has no single exact duration: on hydrated farmland it averages somewhere
    // around 26 minutes, varying with light and how the rows are arranged. This crop is a deterministic block-entity
    // counter (which is what makes it immune to AE2-style growth accelerators, see the class note), so it needs one
    // number, and 26 minutes is the honest middle of wheat's range. 26 * 60 * 20 = 31200.
    //
    // Deliberately NOT SenzuModule.growthTicks() any more. Borrowing senzu's number meant retuning senzu silently
    // retuned saibamen, and the two are no longer meant to pace together.
    public static final int GROWTH_TICKS = 31200;

    // lifetime counters (NOT reset per stage) that back the harvested pet's quality. totalGrowthTicks counts every
    // real tick this crop has been growing; chargedGrowthTicks counts the real ticks during which a player was
    // charging ki within range. Their ratio (see qualityRatio) is "what fraction of its growth time a player spent
    // charging near it", which is exactly what scales the saibaman. Both persist in NBT so a chunk unload or server
    // restart never resets the accumulated quality.
    private long totalGrowthTicks;
    private long chargedGrowthTicks;

    // how often the crop scans for charging players and applies the accelerator. One second: cheap (a mature crop is
    // not ticked at all), and it batches the ki drain + client resync so we never resync every tick of a whole grow.
    private static final int CHARGE_INTERVAL = 20;
    // sphere radius, in blocks, a charging player must be within to accelerate the crop.
    private static final double CHARGE_RADIUS = 5.0;
    // growth multiplier while at least one player charges nearby. 3x turns the ~26 minute base grow into under 9
    // minutes of solid charging: meaningful but not trivial. Flat, does not stack with player count, so a crowd
    // cannot trivialise it.
    private static final int CHARGE_MULTIPLIER = 3;
    // ki drained per charging player per interval, as a fraction of that player's MAX energy. It scales with the
    // player's power so it is a real cost at every level rather than trivial for endgame or brutal for a newcomer.
    //
    // Why 3% and not the old 0.5%: DragonMineZ's own charging regen (TickHandler.regenerateEnergy, once per second)
    // is roughly base * 1.5, which works out to about 1.6% to 2.4% of the player's MAX energy per second depending on
    // race/class, and it scales with the SAME energy multiplier as max energy, so that ratio holds in every form. At
    // the old 0.5% the drain was always swamped by the regen: charging near the crop still NET-GAINED ki, so the
    // "cost" was effectively zero. 3% clears the regen for every vanilla race/class, so tending a crop is a genuine
    // net drain (the player pours ki into the plant) rather than free. Tune here if a datapack retunes the race regen.
    private static final float KI_DRAIN_FRACTION = 0.03f;

    // log a DMZ stat-access failure once per server run, not once per interval, so a drifted DMZ build cannot spam.
    private static boolean loggedDmzFailure;

    public SaibamanCropBlockEntity(BlockPos pos, BlockState state)
    {
        super(SaibamanCropRegistry.SAIBAMAN_CROP_BE.get(), pos, state);
    }

    // one growth tick. Adds the base increment every tick, plus, on the charge-check cadence, a bonus if a player is
    // charging ki nearby (and drains their ki). When the counter crosses the per-stage threshold the block's age
    // advances and the counter resets. Only ever called by the ticker while the crop is below age 3.
    public static void serverTick(Level level, BlockPos pos, BlockState state, SaibamanCropBlockEntity be)
    {
        // base growth: one per tick.
        be.counter++;
        // every real growth tick counts toward the quality denominator.
        be.totalGrowthTicks++;

        // on the cadence, look for nearby charging players and, if any, accelerate + drain ki.
        if (level instanceof ServerLevel server && server.getGameTime() % CHARGE_INTERVAL == 0)
        {
            if (be.chargeAndDrain(server, pos))
            {
                // credit the extra growth for this whole interval, so the average over the interval is CHARGE_MULTIPLIER.
                be.counter += (CHARGE_MULTIPLIER - 1) * CHARGE_INTERVAL;
                // this whole interval counted as charged, so credit the quality numerator by the interval length. Over
                // the crop's life chargedGrowthTicks/totalGrowthTicks converges on the fraction of time it was tended.
                be.chargedGrowthTicks += CHARGE_INTERVAL;
                server.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                        pos.getX() + 0.5, pos.getY() + 0.4, pos.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.0);
            }
        }

        int perStage = Math.max(1, GROWTH_TICKS / 3);
        if (be.counter >= perStage)
        {
            be.counter = 0;
            SaibamanCropBlock.advanceAge(level, pos, state);
        }
        be.setChanged();
    }

    // scan for players charging ki within CHARGE_RADIUS, drain a ki trickle from each (resyncing their HUD), and
    // report whether any were found (which gates the growth bonus). Every DMZ stat touch is Throwable-guarded so a
    // drifted DMZ build degrades to "no acceleration" instead of crashing the crop's ticker.
    private boolean chargeAndDrain(ServerLevel level, BlockPos pos)
    {
        double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
        AABB box = new AABB(cx - CHARGE_RADIUS, cy - CHARGE_RADIUS, cz - CHARGE_RADIUS,
                cx + CHARGE_RADIUS, cy + CHARGE_RADIUS, cz + CHARGE_RADIUS);
        List<ServerPlayer> nearby = level.getEntitiesOfClass(ServerPlayer.class, box,
                p -> p.isAlive() && p.distanceToSqr(cx, cy, cz) <= CHARGE_RADIUS * CHARGE_RADIUS);
        if (nearby.isEmpty())
        {
            return false;
        }

        boolean anyCharging = false;
        for (ServerPlayer player : nearby)
        {
            if (drainIfCharging(player))
            {
                anyCharging = true;
            }
        }
        return anyCharging;
    }

    // if this player is charging ki, remove the ki trickle and resync their HUD; return whether they were charging.
    // A player with no DragonMineZ character (no StatsData) is simply skipped, never an NPE.
    private boolean drainIfCharging(ServerPlayer player)
    {
        if (StatsCapability.INSTANCE == null)
        {
            return false;
        }
        try
        {
            StatsData data = player.getCapability(StatsCapability.INSTANCE).resolve().orElse(null);
            if (data == null || !data.getStatus().isChargingKi())
            {
                return false;
            }
            // suppress DragonMineZ's own charging regen for this player this second, so the drain below is a real net
            // loss rather than being offset by regen. This is the fix for the long-standing "charging near a crop was
            // effectively free ki" problem: the drain alone could not reliably beat the regen, so we cancel the regen.
            SaibamanKiRegenSuppression.mark(player.getUUID(), player.level().getGameTime());
            float cost = data.getMaxEnergy() * KI_DRAIN_FRACTION;
            if (cost > 0.0f)
            {
                // removeEnergy clamps at zero, so a low-ki player just bottoms out rather than going negative.
                data.getResources().removeEnergy(cost);
                // MANDATORY resync or the client ki HUD shows stale energy. This is the whole-Resources sync (there is
                // no energy-only packet); batched to the once-per-second cadence, never every tick.
                if (NetworkHandler.INSTANCE != null)
                {
                    NetworkHandler.sendToPlayer(new ResourceSyncS2C(player), player);
                }
            }
            return true;
        }
        catch (Throwable t)
        {
            if (!loggedDmzFailure)
            {
                loggedDmzFailure = true;
                LoggingHandler.sulog.warn(
                        "[Saibaman] DragonMineZ stat access failed; ki-charge crop acceleration disabled this run. Cause: {}",
                        t.toString());
            }
            return false;
        }
    }

    /**
     * The harvested pet's quality: the fraction of this crop's growth time during which a player was charging ki
     * nearby, clamped to 0..1. 0 means it grew untended; 1 means a player charged beside it the whole way. Read once
     * by {@link SaibamanCropBlock} at harvest. Guards a zero denominator (a crop harvested the same tick it was
     * placed, which the age gate makes impossible in practice) to 0.
     */
    public float qualityRatio()
    {
        if (totalGrowthTicks <= 0L)
        {
            return 0.0f;
        }
        double ratio = (double) chargedGrowthTicks / (double) totalGrowthTicks;
        return (float) Math.max(0.0, Math.min(1.0, ratio));
    }

    /**
     * The absolute number of real ticks a player spent charging ki beside this crop over its whole growth. This is the
     * "how long you charged" quantity that decides the harvested pet's tier (see
     * {@link ConfigSaibamanPet#tierFromChargedRatio(long, long)}), read once by {@link SaibamanCropBlock} at harvest.
     */
    public long chargedGrowthTicks()
    {
        return chargedGrowthTicks;
    }

    /**
     * Every real tick this crop has spent growing, the denominator behind the harvested pet's tier. Paired with
     * {@link #chargedGrowthTicks()} so the tier is decided by the FRACTION of the grow that was tended, which holds
     * its meaning whatever {@link #GROWTH_TICKS} is retuned to.
     */
    public long totalGrowthTicks()
    {
        return totalGrowthTicks;
    }

    @Override
    protected void saveAdditional(CompoundTag tag)
    {
        super.saveAdditional(tag);
        tag.putInt("Counter", counter);
        tag.putLong("TotalGrowthTicks", totalGrowthTicks);
        tag.putLong("ChargedGrowthTicks", chargedGrowthTicks);
    }

    @Override
    public void load(CompoundTag tag)
    {
        super.load(tag);
        counter = tag.getInt("Counter");
        totalGrowthTicks = tag.getLong("TotalGrowthTicks");
        chargedGrowthTicks = tag.getLong("ChargedGrowthTicks");
    }
}
