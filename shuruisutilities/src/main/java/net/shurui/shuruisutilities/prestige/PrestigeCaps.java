package net.shurui.shuruisutilities.prestige;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import net.shurui.shuruisutilities.character.CharacterSlots;
import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.prestige.client.SuCapClient;

// prestige stat-cap boost. every prestige level raises the DMZ per-stat max cap by a configurable percent
// (getCapBonusPerLevel / prestigeCapBonusPerLevel, default 25%/level: prestige 4 doubles the cap).
// multiplier is 1 + level * capBonusPerLevel/100.
// two DMZ mixins read getCapMultiplier() to widen both cap chokepoints in lockstep: Stats.clampStatValue
// (the hard setter clamp) and StatsData.getConfiguredMaxValue (the purchase/level/total-stats budget).
// server-side the multiplier comes from the active slot's prestige; client-side it's the last value synced
// by PacketSuCapMult (cached in SuCapClient), since the DMZ clamp also runs on the client.
public final class PrestigeCaps
{
    private PrestigeCaps() {}

    /** Stat-cap boost percent per prestige level (runtime setting, falling back to the baked config default). */
    public static int capBonusPerLevel(ServerPlayer p)
    {
        return p.getServer() == null ? Math.max(0, SUConfig.prestigeCapBonusPerLevel)
                : PrestigeSettings.get(p.getServer()).getCapBonusPerLevel();
    }

    /** The stat-cap multiplier percent for this player's active character (level x per-level %). */
    public static int capMultPct(ServerPlayer p)
    {
        return Math.max(0, CharacterSlots.getActivePrestige(p)) * Math.max(0, capBonusPerLevel(p));
    }

    /** Convert a stored cap-bonus percent into a multiplicative factor (0% =&gt; 1.0, 100% =&gt; 2.0). */
    public static double multiplierFromPct(int pct)
    {
        return 1.0 + Math.max(0, pct) / 100.0;
    }

    /**
     * The stat-cap multiplier for {@code player}. Server side: computed from the active slot's prestige. Client
     * side (or when the player isn't a ServerPlayer): the last synced value. Null / unknown player =&gt; 1.0.
     */
    public static double getCapMultiplier(Player player)
    {
        if (player instanceof ServerPlayer sp)
            return multiplierFromPct(capMultPct(sp));
        return SuCapClient.multiplier();
    }
}
