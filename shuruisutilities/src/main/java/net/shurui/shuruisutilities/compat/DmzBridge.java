package net.shurui.shuruisutilities.compat;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.quest.Difficulty;
import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.QuestService;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;
import com.dragonminez.common.stats.character.BonusStats;
import com.dragonminez.server.util.FusionLogic;

import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

// bridge to DMZ's stat runtime for shrines + TP boosts. DMZ classes are referenced directly (mandatory dep) but
// every access is try/catch'd so a DMZ internals change or a statless entity degrades to a no-op, not a crash.
public final class DmzBridge
{
    // legacy shared source all shrine bonuses used pre-multi-slot. kept only so login purge can strip orphans
    // from old builds. new bonuses use a per-shrine source (sourceFor) so shrines stack instead of collapsing.
    public static final String BONUS_SOURCE = "su_shrine";

    private static final String SOURCE_PREFIX = "su_shrine:";

    private static final String OP_MUL = "*";

    // unique BonusStats source for one named shrine
    public static String sourceFor(String shrineName)
    {
        return SOURCE_PREFIX + shrineName;
    }

    private DmzBridge()
    {
    }

    // DMZ level, or 0 if statless/unavailable. backs the keepinv tiering (<100 full keep vs >=100 grave).
    public static int level(LivingEntity entity)
    {
        StatsData stats = stats(entity);
        if (stats == null)
            return 0;
        try
        {
            return stats.getLevel();
        }
        catch (Throwable t)
        {
            return 0;
        }
    }

    // DMZ stats, or null if none/unavailable
    public static StatsData stats(LivingEntity entity)
    {
        if (entity == null)
            return null;
        try
        {
            return StatsProvider.<StatsData>get(StatsCapability.INSTANCE, entity).resolve().orElse(null);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    // DMZ fusion state, read through the same status object the rest of the bridge uses. A statless or non-fused
    // entity reads false, so callers can gate on it without a null check.
    public static boolean isFused(LivingEntity entity)
    {
        StatsData stats = stats(entity);
        if (stats == null)
            return false;
        try
        {
            return stats.getStatus().isFused();
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    // The UUID of this entity's DMZ fusion partner, or null if statless or not fused. DMZ stores it on both
    // partners' status, each pointing at the other, so either half resolves the same pair.
    public static UUID fusionPartnerUUID(LivingEntity entity)
    {
        StatsData stats = stats(entity);
        if (stats == null)
            return null;
        try
        {
            return stats.getStatus().getFusionPartnerUUID();
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    // End this player's fusion synchronously, restoring BOTH partners to their own bodies at valid health. DMZ's
    // endFusion looks the partner up from this player's status and undoes the whole pairing, so one call covers
    // both sides whichever half is passed. A no-op for a player who is not fused. Must run on the server thread.
    // Returns true if a fusion was ended.
    public static boolean endFusion(ServerPlayer player)
    {
        StatsData stats = stats(player);
        if (stats == null)
            return false;
        try
        {
            if (!stats.getStatus().isFused() && stats.getStatus().getFusionPartnerUUID() == null)
                return false;
            FusionLogic.endFusion(player, stats, true);
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[Fusion] Could not end the fusion for {}: {}",
                    player.getGameProfile().getName(), t.toString());
            return false;
        }
    }

    // BonusStats key for a user-facing stat. 1:1 except RES: it must be written under DEF or DMZ's defense
    // getters silently ignore it.
    public static String bonusKey(String stat)
    {
        return "RES".equals(stat) ? "DEF" : stat;
    }

    // stat keys a shrine may buff (before the RES->DEF remap)
    public static final String[] STAT_KEYS = { "STR", "SKP", "RES", "STM", "VIT", "PWR", "ENE" };

    public static boolean isValidStat(String stat)
    {
        if (stat == null)
            return false;
        for (String s : STAT_KEYS)
            if (s.equals(stat))
                return true;
        return false;
    }

    // apply/refresh one shrine's stat multiplier under its own source, then resync. unique source means it never
    // disturbs other shrines' bonuses (they stack), and re-adding overwrites just this shrine's entry.
    // percent: 25 = +25% (1.25x); <=0 removes it.
    public static void applyStatBonus(ServerPlayer player, String stat, double percent, String shrineName)
    {
        StatsData stats = stats(player);
        if (stats == null)
            return;
        try
        {
            BonusStats b = stats.getBonusStats();
            String key = bonusKey(stat);
            String source = sourceFor(shrineName);
            double multiplier = 1.0 + percent / 100.0;
            // A non-finite multiplier is saved to NBT and read on every hit, so one NaN here poisons the stat for
            // good. Refuse it rather than storing it.
            if (!Double.isFinite(multiplier))
            {
                LoggingHandler.sulog.warn("[Shrine] Refused a non-finite {} multiplier ({}) for {}", stat,
                        multiplier, player.getGameProfile().getName());
                return;
            }
            if (multiplier <= 1.0)
                b.removeBonus(key, source);
            else
                b.addBonus(key, source, OP_MUL, multiplier);
            resync(player);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[Shrine] Could not apply {} bonus for {}: {}", stat,
                    player.getGameProfile().getName(), t.toString());
        }
    }

    // remove one shrine's bonus (its source, all stats), then resync
    public static void removeStatBonus(ServerPlayer player, String shrineName)
    {
        StatsData stats = stats(player);
        if (stats == null)
            return;
        try
        {
            stats.getBonusStats().removeAllBonuses(sourceFor(shrineName));
            resync(player);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[Shrine] Could not remove shrine bonus {} for {}: {}", shrineName,
                    player.getGameProfile().getName(), t.toString());
        }
    }

    // remove every shrine bonus: each known per-shrine source plus the legacy BONUS_SOURCE (so old-build orphans
    // don't linger), then resync once. run on login before rebuilding stacked state from NBT.
    public static void clearAllShrineBonuses(ServerPlayer player, Iterable<String> activeShrineNames)
    {
        StatsData stats = stats(player);
        if (stats == null)
            return;
        try
        {
            BonusStats b = stats.getBonusStats();
            if (activeShrineNames != null)
                for (String name : activeShrineNames)
                    b.removeAllBonuses(sourceFor(name));
            // strip orphans under the old shared source
            b.removeAllBonuses(BONUS_SOURCE);
            resync(player);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[Shrine] Could not clear shrine bonuses for {}: {}",
                    player.getGameProfile().getName(), t.toString());
        }
    }

    // admin override of DMZ saga difficulty. difficulty is normally a one-time choice gated by
    // isDifficultyChosen(); we bypass that by writing it then force-marking chosen, so it sticks on players who
    // already picked. syncQuestState pushes to the client (handles the in-party case). false if statless.
    public static boolean setSagaDifficulty(ServerPlayer player, Difficulty diff)
    {
        StatsData stats = stats(player);
        if (stats == null)
            return false;
        try
        {
            PlayerQuestData pqd = stats.getPlayerQuestData();
            pqd.setDifficulty(diff);
            pqd.setDifficultyChosen(true);
            QuestService.syncQuestState(player);
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[SagaDifficulty] Could not set difficulty for {}: {}",
                    player.getGameProfile().getName(), t.toString());
            return false;
        }
    }

    // push mutated stats to tracking clients + the player
    /** Public alias so other SU systems (e.g. armour runes) can resync after writing their own bonuses. */
    public static void resyncStats(ServerPlayer player)
    {
        resync(player);
    }

    private static void resync(ServerPlayer player)
    {
        try
        {
            NetworkHandler.sendToTrackingEntityAndSelf(new StatsSyncS2C(player), player);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[Shrine] Stats resync failed for {}: {}",
                    player.getGameProfile().getName(), t.toString());
        }
    }
}
