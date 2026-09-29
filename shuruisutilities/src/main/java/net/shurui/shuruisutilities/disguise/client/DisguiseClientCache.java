package net.shurui.shuruisutilities.disguise.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.shurui.shuruisutilities.disguise.DisguiseView;
import net.shurui.shuruisutilities.disguise.PacketDisguiseSync;

/**
 * Client-side mirror of {@code DisguiseState}: which players are disguised and as whom, and whether THIS client may
 * see through a disguise (a staff viewer). Every disguise override, the name above the head, the tab entry, the rank
 * badge, the crown, the skin and the DragonMineZ race body, reads from here.
 *
 * <p>Populated only by {@link PacketDisguiseSync}. A missing entry means "not disguised", so all the override call
 * sites are a single map lookup that is null in the overwhelmingly common case.
 */
@OnlyIn(Dist.CLIENT)
public final class DisguiseClientCache
{
    private static final Map<UUID, DisguiseView> ACTIVE = new ConcurrentHashMap<>();
    private static volatile boolean canSeeReal;

    private DisguiseClientCache() {}

    /** Apply an incoming sync packet. */
    public static void accept(PacketDisguiseSync p)
    {
        switch (p.mode)
        {
            case PacketDisguiseSync.MODE_SNAPSHOT:
                ACTIVE.clear();
                DisguiseSkins.reset();
                canSeeReal = p.canSeeReal;
                for (DisguiseView v : p.views)
                    store(v);
                break;
            case PacketDisguiseSync.MODE_SINGLE:
                for (DisguiseView v : p.views)
                    store(v);
                break;
            case PacketDisguiseSync.MODE_CLEAR:
                ACTIVE.remove(p.clearId);
                // Drop the resolved skin too, or the in-world and tab skin would outlive the disguise.
                DisguiseSkins.forget(p.clearId);
                break;
            default:
                break;
        }
    }

    private static void store(DisguiseView v)
    {
        if (v == null || v.realId == null)
            return;
        ACTIVE.put(v.realId, v);
        // Warm the skin now, so the disguised body is not a one-frame flash of the wrong skin when it comes into view.
        DisguiseSkins.prepare(v);
    }

    /** The disguise for this player, or null. */
    public static DisguiseView get(UUID id)
    {
        return id == null ? null : ACTIVE.get(id);
    }

    /** Whether this player is disguised on this client. */
    public static boolean isDisguised(UUID id)
    {
        return id != null && ACTIVE.containsKey(id);
    }

    /** Whether this client may draw the staff-only real-identity marker. */
    public static boolean canSeeReal()
    {
        return canSeeReal;
    }

    /** Clear everything (a disconnect). */
    public static void reset()
    {
        ACTIVE.clear();
        canSeeReal = false;
    }
}
