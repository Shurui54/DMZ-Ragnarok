package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;
import com.dragonminez.common.init.block.custom.DragonBallBlock;
import com.dragonminez.server.events.DragonBallsHandler;
import com.dragonminez.server.world.data.DragonBallSavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.shard.ShardDimensions;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * A10: at every start, put each scattered dragon ball set back in its proper dimension.
 *
 * <p>Two kinds of misplaced records are found in the levels this server hosts (DMZ's own per-level ball records, the
 * same source the radar reads):
 * <ul>
 *   <li><b>A level whose content another shard owns</b> (on the network every server loads every dimension, so the SMP
 *       has an empty copy of the overworld). DMZ's first spawn used to lay a set there that nobody can reach; the
 *       radar pointed at it. Those records (and any block) are simply dropped: the owning shard has the real set.
 *       {@code MixinDmzDragonBallScatter} now refuses to scatter into such a level at all.</li>
 *   <li><b>A level the set does not scatter in</b> (an Earth ball on Namek, a Namek ball still on the Namek planet
 *       after the set moved to namekow, a ball set down somewhere and left). The ball is removed and the same star
 *       is put back as a pending scatter spot in the set's home level, picked by the normal scatter rules
 *       ({@link ScatterGuard#freshTarget}), so the set stays complete and counts never change.</li>
 * </ul>
 * Sets DMZ itself scatters (copies above zero) are moved home. Black Star, which its space module places on the
 * random generated planets, is only removed from the wrong dimension (see {@link #MODULE_PLACED}); Super and Cerulean
 * are left alone. Balls held by players or in totems are never touched
 * here. Every move is logged. Runs at LOW priority on ServerStarted, after DMZ's first spawn and the Namek move.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class BallDimensionSweep
{
    private BallDimensionSweep() {}

    /**
     * Sets placed by their own space module on the random generated planets, never by DMZ's scatter, and summonable
     * only there: Black Star ({@code ApophisSummonModule} seeds one ball on each of seven random generated planets and
     * re-seeds after every wish; {@code MixinDmzDragonBallBlock} refuses its summon off a planet). A record of it
     * outside its home dimension is removed and NOT re-homed, because the module already holds the full set on its
     * planets. Before 1.0 (a4cd6b92 .. bc1b0237) Black Star was an overworld set that DMZ scattered there, and those
     * overworld balls were never cleaned up; this is what clears them. Super is not listed: its balls may be set down
     * anywhere to summon, and nothing would put a removed one back.
     */
    private static final java.util.Set<String> MODULE_PLACED = java.util.Set.of("blackstar");

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onServerStarted(ServerStartedEvent event)
    {
        try
        {
            sweep(event.getServer());
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[dragonball] wrong-dimension sweep failed: {}", t.toString());
        }
    }

    /** Run the sweep now. Returns how many ball records were moved or dropped. */
    public static int sweep(MinecraftServer server)
    {
        int changed = 0;
        for (DragonBallSetDefinition set : DragonBallDefinitions.getBallSets())
        {
            if (set == null || set.getId() == null)
                continue;
            String setId = set.getId();
            boolean modulePlaced = MODULE_PLACED.contains(setId);
            if (set.getCopies() <= 0 && !modulePlaced)
                continue; // a zero-copy set nothing places (Cerulean: summon / craft only) is left alone
            ServerLevel home = modulePlaced ? null : homeLevel(server, set);
            for (ServerLevel level : server.getAllLevels())
            {
                boolean owned = ShardDimensions.ownsContentIn(level.dimension());
                if (owned && set.supportsDimension(level.dimension()))
                    continue; // the set's own, owned home: nothing to do
                DragonBallSavedData data = DragonBallSavedData.get(level);
                List<Integer> movedStars = new ArrayList<>();
                for (Map.Entry<Integer, List<BlockPos>> e : data.getActiveBalls(setId).entrySet())
                {
                    if (e.getValue() == null || e.getValue().isEmpty())
                        continue;
                    for (BlockPos pos : new ArrayList<>(e.getValue()))
                    {
                        if (owned)
                        {
                            BlockState state = level.getBlockState(pos); // loads this one chunk, a few at most
                            if (state.getBlock() instanceof DragonBallBlock ball && setId.equals(ball.getBallSetId()))
                                level.removeBlock(pos, false);
                            movedStars.add(e.getKey());
                        }
                        LoggingHandler.sulog.warn("[dragonball] {} star {} at {} {} is outside its home; {}", setId,
                                e.getKey(), level.dimension().location(), pos.toShortString(),
                                owned ? (modulePlaced ? "removed" : "removed and re-scattered at home")
                                        : "record dropped (another shard owns it)");
                        changed++;
                    }
                    e.getValue().clear();
                }
                for (Map.Entry<Integer, List<BlockPos>> e : data.getPendingBalls(setId).entrySet())
                {
                    if (e.getValue() == null || e.getValue().isEmpty())
                        continue;
                    for (BlockPos pos : e.getValue())
                    {
                        if (owned)
                            movedStars.add(e.getKey());
                        LoggingHandler.sulog.warn("[dragonball] {} star {} pending at {} {} is outside its home; {}",
                                setId, e.getKey(), level.dimension().location(), pos.toShortString(),
                                owned ? (modulePlaced ? "removed" : "re-scattered at home")
                                        : "record dropped (another shard owns it)");
                        changed++;
                    }
                    e.getValue().clear();
                }
                data.setDirty();
                if (!movedStars.isEmpty() && modulePlaced)
                    LoggingHandler.sulog.warn("[dragonball] {} {} ball(s) removed from {}: this set lives only on the "
                            + "generated planets, where its own module keeps all seven", movedStars.size(), setId,
                            level.dimension().location());
                else if (!movedStars.isEmpty())
                    rehome(home, setId, movedStars);
            }
        }
        if (changed > 0)
            LoggingHandler.sulog.info("[dragonball] wrong-dimension sweep: {} ball record(s) moved or dropped", changed);
        return changed;
    }

    /**
     * Put these stars of {@code setId} back as pending scatter spots in the set's home level on this server (used for
     * a ball that was lost as an item). Only for sets DMZ scatters (copies above zero). Never throws.
     */
    public static void rehome(MinecraftServer server, String setId, List<Integer> stars)
    {
        try
        {
            DragonBallSetDefinition set = DragonBallDefinitions.getBallSet(setId);
            if (set == null || set.getCopies() <= 0 || stars.isEmpty())
                return;
            rehome(homeLevel(server, set), setId, stars);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[dragonball] could not re-scatter lost {} ball(s): {}", setId, t.toString());
        }
    }

    // Put each moved star back as a pending scatter spot in the set's home, where DMZ generates it on chunk load.
    private static void rehome(ServerLevel home, String setId, List<Integer> stars)
    {
        if (home == null)
        {
            LoggingHandler.sulog.warn("[dragonball] {} has no home level on this server; {} star(s) could not be "
                    + "re-scattered here (its owning shard keeps its own set)", setId, stars.size());
            return;
        }
        DragonBallSavedData data = DragonBallSavedData.get(home);
        DragonBallSetDefinition set = DragonBallDefinitions.getBallSet(setId);
        int copies = set == null ? 1 : Math.max(1, set.getCopies());
        for (Integer star : stars)
        {
            // DragonMineZ 2.2 re-scatters a lost ball item itself (DragonBallsHandler.respawnLostItem), in whichever
            // order the two listeners run. Never add a spot past the set's copies, or each loss would leave a second
            // pending copy for the duplicate check to delete.
            if (data.getTrackedCount(setId, star) >= copies)
                continue;
            data.getPendingBalls(setId).computeIfAbsent(star, k -> new ArrayList<>())
                    .add(ScatterGuard.freshTarget(home, setId));
        }
        data.setDirty();
        DragonBallsHandler.syncRadar(home);
    }

    private static ServerLevel homeLevel(MinecraftServer server, DragonBallSetDefinition set)
    {
        for (ResourceLocation dim : set.getValidDimensions())
        {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dim);
            ServerLevel level = server.getLevel(key);
            if (level != null && ShardDimensions.ownsContentIn(key))
                return level;
        }
        return null;
    }
}
