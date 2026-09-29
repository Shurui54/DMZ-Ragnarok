package net.shurui.dev.shuruis_dmz_tournaments.character;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.entity.player.Player;

/**
 * Fast in-memory registry of who holds a tournament character, consulted by DMZ's
 * {@code StatsData.getFormMultiplier} mixin so a forced transform yields a FLAT tournament multiplier, not the
 * form's own configured one.
 *
 * <p>Two sides, one lookup. The server keeps {@link #SERVER} (authoritative, drives all combat and stat math).
 * Each client keeps {@link #CLIENT}, replaced wholesale from a sync packet, so the fighter's re-computed HUD (the
 * SU scouter reads {@code StatsData.getMaxEnergy()} client-side) shows the same multiplier the server enforces.
 * {@link #isFighter(Player)} picks the set from the entity's side.</p>
 *
 * <p>Separate from {@link TournamentCharacter#isActive} (the durable NBT crash-recovery marker): that NBT read is
 * too heavy for a per-tick stat getter and is server-only.</p>
 */
public final class TournamentFighters {
    private TournamentFighters() {}

    private static final Set<UUID> SERVER = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> CLIENT = ConcurrentHashMap.newKeySet();

    public static void addServer(UUID id) {
        if (id != null) SERVER.add(id);
    }

    public static void removeServer(UUID id) {
        if (id != null) SERVER.remove(id);
    }

    /** Immutable snapshot for the sync packet. */
    public static Set<UUID> serverSnapshot() {
        return Set.copyOf(SERVER);
    }

    public static void setClient(Collection<UUID> ids) {
        CLIENT.clear();
        if (ids != null) CLIENT.addAll(ids);
    }

    /**
     * Whether this entity currently holds a tournament character. Reads the client set on a client entity
     * and the server set otherwise, so it is correct wherever {@code StatsData} is evaluated.
     */
    public static boolean isFighter(Player player) {
        if (player == null) return false;
        Set<UUID> set = player.level().isClientSide() ? CLIENT : SERVER;
        return set.contains(player.getUUID());
    }
}
