package net.shurui.shuruisutilities.sparring;

import java.util.UUID;

import net.minecraft.world.entity.Entity;

import net.shurui.shuruisutilities.api.key.SparringHooks;

/**
 * Facade by FQN for the PRIVATE player sparring, whose runtime (the live spars, invites, cooldowns, the TP payout and
 * the loaded {@link SparConfig}) lives in the Ragnarok Key since S19a. Kept in core because the PvP layers (the key's
 * protection, regions and guild territory) ask {@link #isSparPair} and the guild gravity chamber reuses the tuning
 * from {@link #config()}. Every answer goes through {@link SparringHooks}; keyless nobody is ever in a spar and the
 * tuning is the code default.
 */
public final class SparManager
{
    private SparManager() {}

    /** The live sparring tuning (keyless: the code defaults, never read from disk). */
    public static SparConfig config()
    {
        return SparringHooks.get().config();
    }

    /** Whether this player is in a live spar (keyless: false). */
    public static boolean isInSpar(UUID id)
    {
        return SparringHooks.get().isInSpar(id);
    }

    /**
     * True only when {@code a} and {@code b} are the two distinct fighters of ONE live spar session, in either order:
     * the predicate the PvP-deny layers consult so an active spar overrides a PvP toggle, a region PVP / invincible
     * flag or a guild claim's safe zone for that pair alone. Keyless: false.
     */
    public static boolean isSparPair(Entity a, Entity b)
    {
        return SparringHooks.get().isSparPair(a, b);
    }
}
