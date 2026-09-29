package net.shurui.dev.shuruis_dmz_tournaments.dmz;

import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.api.ZSoulHook;

// Optional bridge to Raid Bosses' Z-Soul system, so a Stat Gem's over-cap overflow can land in a worn
// Z-Soul instead of vanishing. It goes through the core ZSoulHook (registered by the Ragnarok Key's Z-Soul feature
// when Raids is present), so Tournaments never names Raid Bosses directly and no-ops cleanly (0) when Raids or
// the key is absent. Kept as a thin adapter; the stat gem apply in the key calls it.
public final class ZSoulBridge
{
    private ZSoulBridge() {}

    // grant beyond-cap points to the worn Z-Soul for a DMZ stat key, returns how many landed
    public static int grant(ServerPlayer player, String dmzStatKey, int amount)
    {
        return ZSoulHook.grantBeyondCap(player, dmzStatKey, amount);
    }
}
