package net.shurui.shuruisutilities.api.key;

import java.util.List;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.staff.StaffRole;
import net.shurui.shuruisutilities.staff.StaffRoster;
import net.shurui.shuruisutilities.staff.StaffTask;

/**
 * Core-side hook for the PRIVATE staff system (shifts, the roster, the staff task board and the weekly goal; logic
 * in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps {@code staff.StaffRoster} as a facade over this hook
 * (the shard layer reads and writes the roster through it until the shard engine moves), plus the {@link StaffRole}
 * and {@link StaffTask} types, {@code StaffWeek}, the {@code StaffGoal.toml} spec ({@code StaffGoalConfig}), the HUD
 * packet ({@code PacketStaffHud}, 88) and the client screens.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false, nobody is staff or on
 * duty, roster writes are dropped, and the staff HUD is always empty. The files {@code staff.json},
 * {@code staff_tasks.json} and {@code staff_goal.json} are never read or written keyless.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class StaffHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "staff";

    private StaffHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the staff system is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** Whether the player is on the roster at all. Keyless: false. */
        default boolean isStaff(UUID id)
        {
            return false;
        }

        /** The player's roster role, or null. Keyless: null. */
        default StaffRole roleOf(UUID id)
        {
            return null;
        }

        /** Put a player on the roster (the /staff add path). Keyless: no-op. */
        default void grant(UUID id, String name, StaffRole role)
        {
        }

        /** Take a player off the roster (the /staff remove path). Keyless: no-op. */
        default void revoke(UUID id)
        {
        }

        /** Apply a roster grant the shard network sent. Keyless: no-op. */
        default void applyRemoteGrant(UUID id, String roleName, String name, long grantedAt, boolean flagged)
        {
        }

        /** Apply a roster removal the shard network sent. Keyless: no-op. */
        default void applyRemoteRevoke(UUID id)
        {
        }

        /** A snapshot of every roster record's shareable fields for the shard layer. Keyless: empty. */
        default List<StaffRoster.ShardSnap> shardSnapshot()
        {
            return List.of();
        }

        /** Whether the player is on duty right now. Keyless: false. */
        default boolean isOnDuty(UUID id)
        {
            return false;
        }

        /** Close the player's shift for a shard hop (no session counted). Keyless: false, nothing was open. */
        default boolean endShiftForHop(UUID id)
        {
            return false;
        }

        /** Resume a shift carried across a shard hop; the role resumed, or null. Keyless: null. */
        default StaffRole resumeShift(ServerPlayer player)
        {
            return null;
        }

        /** The task the staff HUD should show this player (clocked in and holding one), or null. Keyless: null. */
        default StaffTask hudTask(UUID id)
        {
            return null;
        }
    }

    /** The keyless default until the key installs its own. Never null. */
    private static volatile Impl impl = new Impl() {};

    /** Install the key's implementation and mark the feature. Called once from {@code RagnarokKeyMod}. */
    public static void install(Impl i)
    {
        if (i == null)
            return;
        impl = i;
        KeyFeatures.mark(FEATURE_ID);
    }

    /** The live implementation (never null: the keyless default until the key installs). */
    public static Impl get()
    {
        return impl;
    }

    /** Whether the staff system is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
