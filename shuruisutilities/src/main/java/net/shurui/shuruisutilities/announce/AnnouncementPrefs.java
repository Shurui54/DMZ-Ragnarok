package net.shurui.shuruisutilities.announce;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server-side, in-memory record of which players have asked us NOT to show them SU's own on-screen announcements
 * (the big title/subtitle we put up for rituals, the corrupted-ball event, region entry, and similar). DragonMineZ's
 * own titles and vanilla titles are never consulted here, only OUR announcements gate on this.
 *
 * <p>Why in-memory, per player, with nothing persisted or synced through the shard database: the preference lives in
 * the CLIENT's SUConfig (a COMMON config, so Forge never syncs it), and the client re-announces its current value on
 * every login (and again whenever the toggle is flipped). Because the client speaks on every join, a player hopping
 * between shards carries the preference with them automatically: the destination shard hears it the moment they arrive,
 * so nothing has to be written to disk or copied through the vault. That is the whole reason this is safe as a plain
 * map cleared on logout.
 *
 * <p>Fail-open: when we have heard nothing from a client (an older client that does not send the packet, or the packet
 * has simply not arrived yet), {@link #shown(ServerPlayer)} returns true. We never silently swallow an event
 * announcement because of a missing signal; a player only loses announcements when they deliberately turned them off.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class AnnouncementPrefs
{
    private AnnouncementPrefs() {}

    // UUID -> whether OUR announcements should be shown to that player. Absent = never heard = shown (fail open).
    // ConcurrentHashMap because the packet is handled on the network thread's enqueued work while ticking reads it.
    private static final Map<UUID, Boolean> SHOWN = new ConcurrentHashMap<>();

    /** Record a client's stated preference. Called from the client to server preference packet. */
    public static void set(UUID player, boolean shown)
    {
        if (player == null)
            return;
        SHOWN.put(player, shown);
    }

    /** True if SU's own on-screen announcements should be shown to this player. Fail open when unheard. */
    public static boolean shown(ServerPlayer player)
    {
        return player != null && shown(player.getUUID());
    }

    /** True if SU's own on-screen announcements should be shown to this player id. Fail open when unheard. */
    public static boolean shown(UUID player)
    {
        if (player == null)
            return true;
        return SHOWN.getOrDefault(player, Boolean.TRUE);
    }

    // Drop the record on logout so it cannot bleed into whoever reuses the map next; the client re-announces on its
    // next login anyway, so nothing is lost by forgetting it here. Server side only (default dist).
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event)
    {
        SHOWN.remove(event.getEntity().getUUID());
    }
}
