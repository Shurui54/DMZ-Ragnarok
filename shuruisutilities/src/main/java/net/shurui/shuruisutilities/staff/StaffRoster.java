package net.shurui.shuruisutilities.staff;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.api.key.StaffHooks;

/**
 * Facade for the staff roster (the roster, the shifts and the clock live in the Ragnarok Key). Core's shard layer
 * (ShardSync, ShardStaff, ShardPayload, ShardCommandLog, StaffIntentApply) reads and writes the roster through these
 * statics until the shard engine moves; each forwards to {@link StaffHooks}, whose keyless answers are "nobody is
 * staff" and "nothing to do". The two records are the shard layer's wire shapes. No logic here.
 */
public final class StaffRoster
{
    private StaffRoster() {}

    /**
     * A flat snapshot of one staff record's shareable fields, taken on the server thread so the shard thread never
     * reads the live mutable roster record (the key's {@code StaffLedger.Record}). Carries both the roster identity
     * (role, name, grantedAt, flagged) and this server's own accumulated contribution (the time and counts), which
     * the shard layer splits across its two tables.
     */
    public record ShardSnap(String uuid, String role, String name, long grantedAt, boolean flagged,
                            long totalMillis, int sessions, int tasksApproved, int tasksCompleted,
                            long lastClockIn, long lastClockOut, Map<String, Long> weekMillis)
    {
        /** Whether there is any banked time or count worth writing a per-server time row for. */
        public boolean hasContribution()
        {
            return totalMillis > 0 || sessions > 0 || tasksApproved > 0 || tasksCompleted > 0;
        }

        /** The per-week rows worth publishing to {@code staff_week}. Never null. */
        public Map<String, Long> weekMillis()
        {
            return weekMillis == null ? java.util.Map.of() : weekMillis;
        }
    }

    /** One roster row as the network holds it, applied back onto the local roster by the poll. */
    public record RosterChange(String uuid, String role, String name, long grantedAt, boolean flagged,
                               boolean deleted) {}

    public static boolean isStaff(UUID id)
    {
        return StaffHooks.get().isStaff(id);
    }

    public static StaffRole roleOf(UUID id)
    {
        return StaffHooks.get().roleOf(id);
    }

    public static void grant(UUID id, String name, StaffRole role)
    {
        StaffHooks.get().grant(id, name, role);
    }

    public static void revoke(UUID id)
    {
        StaffHooks.get().revoke(id);
    }

    public static void applyRemoteGrant(UUID id, String roleName, String name, long grantedAt, boolean flagged)
    {
        StaffHooks.get().applyRemoteGrant(id, roleName, name, grantedAt, flagged);
    }

    public static void applyRemoteRevoke(UUID id)
    {
        StaffHooks.get().applyRemoteRevoke(id);
    }

    public static List<ShardSnap> shardSnapshot()
    {
        return StaffHooks.get().shardSnapshot();
    }

    public static boolean isOnDuty(UUID id)
    {
        return StaffHooks.get().isOnDuty(id);
    }

    public static boolean endShiftForHop(UUID id)
    {
        return StaffHooks.get().endShiftForHop(id);
    }

    public static StaffRole resumeShift(ServerPlayer player)
    {
        return StaffHooks.get().resumeShift(player);
    }
}
