package net.shurui.shuruisutilities.compat.customnpcs;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Which tab of a Zeni shop each player currently has open, as last reported by their client.
//
// This exists because a single trade slot can carry BOTH a buy price and a sell price, and the trade event the
// server sees is identical either way: same slot, same sold stack. Without the tab the server cannot tell "buy
// this" from "sell this" and has to pick one, which is exactly why the sell branch used to win unconditionally
// and made a buy-priced item with a sell price unbuyable.
//
// In memory only, and deliberately: it is UI state, worth nothing after a disconnect, and the client re-reports it
// every time a trader screen opens. Cleared on logout so a UUID cannot pin an entry forever.
//
// Vanilla-only refs (a UUID and a boolean), so this loads whether or not CustomNPCs is present.
public final class ZeniShopTabs
{
    private ZeniShopTabs() {}

    private static final Map<UUID, Boolean> tabs = new ConcurrentHashMap<>();

    public static void set(UUID player, boolean showBuy)
    {
        if (player != null)
            tabs.put(player, showBuy);
    }

    public static void clear(UUID player)
    {
        if (player != null)
            tabs.remove(player);
    }

    /**
     * True when this player is on the Buy tab.
     *
     * <p>Defaults to TRUE for a player we have heard nothing from: an older client that never sends the packet, or
     * a shop opened before the first report lands. That default is the fail-safe one, because on a slot carrying
     * both prices it means the server CHARGES rather than PAYS. Guessing the other way would hand out Zeni.
     */
    public static boolean isBuy(UUID player)
    {
        Boolean v = player == null ? null : tabs.get(player);
        return v == null || v;
    }
}
