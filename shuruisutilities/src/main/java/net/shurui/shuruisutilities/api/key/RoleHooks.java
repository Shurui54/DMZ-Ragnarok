package net.shurui.shuruisutilities.api.key;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.god.RoleMove;

/**
 * Core-side hook for the PRIVATE god roles (God of Destruction, Angel, Grand Zeno) and their role energy (logic in the
 * Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps the energy pool and its sync ({@code EnergyManager},
 * {@code EnergyData}, {@code EnergySync}, packets 79 and 80), the role titles, the role techniques' definitions, the
 * staff item and ki weapon, packet 81, the hakai cooldown table and its SavedData; this hook is how the key runs the
 * role abilities and moves, and only when the key is installed.
 *
 * <p><b>MALICE stays public.</b> Shadow dragon signature moves spend the same pool, so core keeps a MALICE-only view of
 * it: {@code EnergyManager.hasAccess} answers the two role kinds only while {@link #available()} is true. Without the
 * key a shadow dragon still regenerates, spends and sees their bar; nobody else has one.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false, the ability key and the staff
 * toggle do nothing, a role technique is refused by the dispatcher, no shot bypasses a clash, and the sphere charge is
 * never shaped.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class RoleHooks
{
    /** The {@link KeyFeatures} id this hook marks on install, and the id the client gates its UI on. */
    public static final String FEATURE_ID = "roles";

    private RoleHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the god roles are live (the key installed them). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** The player pressed the role ability key ({@code PacketAbilityActivate}). Keyless: no-op. */
        default void activateAbility(ServerPlayer player)
        {
        }

        /** The player asked to summon or put away the Angel's staff (packet 81). Keyless: no-op. */
        default void toggleAngelStaff(ServerPlayer player)
        {
        }

        /**
         * A fully charged role technique was released. Returns true when the cast was consumed (always, for a role
         * move, including a refusal). Keyless: false; the dispatcher refuses the cast before asking.
         */
        default boolean handleRoleMove(ServerPlayer caster, RoleMove move)
        {
            return false;
        }

        /** Whether a giant ki shot from this owner bypasses the clash (a god spends the whole bar). Keyless: no. */
        default boolean clashBypass(Entity projectile, Entity owner)
        {
            return false;
        }

        /** Shape the God's sphere charge around a player charging it (percent 0..100+). Keyless: no-op. */
        default void tickKiPrisonCharge(ServerPlayer player, float chargePercent)
        {
        }

        /** Drop any sphere charge orbs around a player who is not charging it. Keyless: no-op. */
        default void clearKiPrisonCharge(ServerPlayer player)
        {
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

    /** Whether the god roles are live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
