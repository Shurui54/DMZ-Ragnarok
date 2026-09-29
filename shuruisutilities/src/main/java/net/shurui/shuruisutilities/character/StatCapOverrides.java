package net.shurui.shuruisutilities.character;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.ResourceSyncS2C;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.server.events.players.StatsEvents;

import net.shurui.shuruisutilities.stats.StatCapBypass;
import net.shurui.shuruisutilities.prestige.PrestigeCaps;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Persistence for above-cap /dmzstats set|add values. DMZ re-clamps every stat to the global max on every
 * capability load (Stats.load/copyFrom -> setters -> clampStatValue): login, PlayerEvent.Clone (respawn /
 * return-from-End), dim change, and when SU loads a character into a slot. Without help, an above-cap command
 * value reverts to the cap on the first of those.
 *
 * <p>So each above-cap value is remembered per character (in the active slot's own NBT, so it travels with the
 * character and is deleted with it) and re-asserted after each re-clamp point, written back through the DMZ
 * setter with {@link StatCapBypass} active so the clamp stands down. Re-assert + record run on the server
 * thread only, leaving normal in-GUI purchases untouched.
 *
 * <p>Command stats == their DMZ setter keys: STR, SKP, RES, VIT, PWR, ENE. RES is NOT remapped to DEF here
 * (that remap is BonusStats-only); Stats.setStat/getCurrentStatValue use RES directly.
 */
public final class StatCapOverrides
{
    private StatCapOverrides() {}

    // stats /dmzstats can set/add (DMZ's own set minus the ALL alias)
    private static final String[] STATS = { "STR", "SKP", "RES", "VIT", "PWR", "ENE" };

    // normal (non-command) per-stat cap for the active character: global max * prestige multiplier, the ceiling
    // /dmzstats blows past. In max-level-value-instead-of-stats mode there's no per-stat cap -> MAX_VALUE (and
    // nothing is recorded as an override).
    private static long normalCap(ServerPlayer p, StatsData data)
    {
        if (data.isMaxLevelValueInsteadOfStats())
            return Integer.MAX_VALUE;
        int base = ConfigManager.getServerConfig().getGameplay().getMaxValue();
        double mult = PrestigeCaps.getCapMultiplier(p);
        long cap = (long) Math.min((double) base * (mult <= 1.0 ? 1.0 : mult), Integer.MAX_VALUE);
        return cap;
    }

    // after /dmzstats set|add, record (or clear) the per-character override for the affected stat. "ALL" fans
    // out to every stat. Above the normal cap -> stored for re-assert; at/below -> existing override removed
    // (so un-boosting works).
    public static void recordAfterCommand(ServerPlayer target, String stat)
    {
        if (target == null || stat == null)
            return;
        StatsData data = target.getCapability(StatsCapability.INSTANCE).resolve().orElse(null);
        if (data == null)
            return;
        try
        {
            String upper = stat.toUpperCase();
            if ("ALL".equals(upper))
            {
                for (String s : STATS)
                    recordOne(target, data, s);
            }
            else
            {
                recordOne(target, data, upper);
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[StatCap] record failed for {}: {}",
                    target.getGameProfile().getName(), t.toString());
        }
    }

    private static void recordOne(ServerPlayer target, StatsData data, String stat)
    {
        long cap = normalCap(target, data);
        int current = data.getCurrentStatValue(stat);
        if (current > cap)
            CharacterSlots.setActiveOverride(target, stat, current);
        else
            CharacterSlots.removeActiveOverride(target, stat);
    }

    // Re-assert the active character's stored above-cap overrides after DMZ re-clamped. For each stored
    // stat -> value, set through the DMZ setter with StatCapBypass active (clamp skipped), then recompute
    // dependent resources (max health/energy/stamina) like DMZ's command and resync. No-op if no overrides.
    public static void reassert(ServerPlayer p)
    {
        if (p == null)
            return;
        StatsData data = p.getCapability(StatsCapability.INSTANCE).resolve().orElse(null);
        if (data == null)
            return;
        CompoundTag ov = CharacterSlots.activeOverrides(p);
        if (ov.isEmpty())
            return;

        boolean changed = false;
        StatCapBypass.enter();
        try
        {
            for (String stat : ov.getAllKeys())
            {
                int value = ov.getInt(stat);
                if (value <= data.getCurrentStatValue(stat))
                    continue; // already at/above the stored value
                data.getStats().setStat(stat, value);
                changed = true;
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[StatCap] re-assert failed for {}: {}",
                    p.getGameProfile().getName(), t.toString());
        }
        finally
        {
            StatCapBypass.exit();
        }

        if (!changed)
            return;

        // match DMZ's command tail: bring dependent secondary pools up to the now-larger maxima and resync
        try
        {
            StatsEvents.applyHealthBonus(p);
            p.setHealth(p.getMaxHealth());
            data.getResources().setCurrentEnergy(data.getMaxEnergy());
            data.getResources().setCurrentStamina(data.getMaxStamina());
            NetworkHandler.sendToTrackingEntityAndSelf(new StatsSyncS2C(p), p);
            NetworkHandler.sendToPlayer(new ResourceSyncS2C(p), p);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[StatCap] re-assert resync failed for {}: {}",
                    p.getGameProfile().getName(), t.toString());
        }
    }
}
