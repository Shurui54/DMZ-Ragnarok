package net.shurui.shuruisutilities.saibaman;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Authoritative, owner-keyed registry of live {@link SaibamanPetEntity} instances, used to enforce the per-player
 * saibaman cap ({@link ConfigSaibamanPet#maxPetsPerPlayer}).
 *
 * <p>The cap is PER SERVER (per shard), not per network. Saibaman pets are ordinary in-world entities saved to the
 * chunk they stand in; they do NOT travel with a player through the shard vault on a cross-server hop (open-1, open-2,
 * smp), so a player who has three pets on open-1 and hops to open-2 arrives with none there and may grow three more.
 * That is intended: the entities genuinely live on the origin shard, and counting them per network would require
 * syncing entity state the vault never carries. If pets were ever made to follow a player across shards, this counter
 * would need to move into the shard sync layer; it does not today.
 *
 * <p>Why a registry and not a loaded-entity scan: a plain scan of loaded entities misses a pet ordered to sit in a
 * chunk that has since unloaded, which would let a player exceed the cap. This map remembers a pet's UUID from the
 * moment it first joins a level (fresh spawn OR chunk load) and only forgets it when the entity is PERMANENTLY removed
 * (killed or discarded), never on a chunk unload, so an unloaded-but-still-existing pet keeps holding its slot.
 *
 * <p>The map is never trusted blindly: {@link #countLive} reconciles it against the currently loaded pets before
 * returning, dropping any tracked UUID that is loaded yet dead or re-owned, and adopting any loaded-and-owned pet that
 * somehow was not tracked. Entries for genuinely unloaded pets (present in the map, not currently loaded) are trusted,
 * because a pet cannot die while it is not ticking.
 *
 * <p>It is in-memory and cleared on server stop, so it does not persist across a restart; the reconcile in
 * {@link #countLive} rebuilds it from loaded pets as chunks come back. The only consequence is that, immediately after
 * a restart, a pet still sitting in an unloaded chunk is not counted until its chunk loads: this can leave a player
 * transiently able to grow one over the cap, which is harmless because existing over-cap pets are deliberately left
 * alone (they are the player's property) and the count self-corrects the moment that chunk loads.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SaibamanPetRegistry
{
    private SaibamanPetRegistry() {}

    // ownerUUID -> set of that owner's live pet UUIDs. Server-side, in-memory, per server. Java types only (no
    // Minecraft-typed fields): this is never persisted, but keeping it plain matches the storage discipline anyway.
    private static final Map<UUID, Set<UUID>> OWNED = new HashMap<>();

    /** Record a pet under its owner. Called from {@link SaibamanPetEntity} when it joins a level, server-side only. */
    public static synchronized void track(UUID owner, UUID pet)
    {
        if (owner == null || pet == null)
        {
            return;
        }
        OWNED.computeIfAbsent(owner, k -> new HashSet<>()).add(pet);
    }

    /**
     * Forget a pet, freeing its owner's slot. Called only for a PERMANENT removal (death or discard), never for a
     * chunk unload. The pet's owner is looked up by scanning the (small) map rather than trusting a possibly-cleared
     * owner field on a dying entity.
     */
    public static synchronized void untrack(UUID pet)
    {
        if (pet == null)
        {
            return;
        }
        Iterator<Map.Entry<UUID, Set<UUID>>> it = OWNED.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<UUID, Set<UUID>> entry = it.next();
            if (entry.getValue().remove(pet) && entry.getValue().isEmpty())
            {
                it.remove();
            }
        }
    }

    /**
     * The number of live pets an owner currently holds, reconciled against loaded entities. This is the value the cap
     * is checked against. See the class note for how loaded-vs-unloaded entries are treated.
     */
    public static synchronized int countLive(MinecraftServer server, UUID owner)
    {
        if (server == null || owner == null)
        {
            return 0;
        }
        Set<UUID> tracked = OWNED.computeIfAbsent(owner, k -> new HashSet<>());

        // One pass over every level: gather every loaded pet UUID (for the stale check) and the subset that is alive
        // and owned by this player (the ground truth for what is loaded).
        Set<UUID> allLoaded = new HashSet<>();
        Set<UUID> ownedAlive = new HashSet<>();
        for (ServerLevel level : server.getAllLevels())
        {
            for (Entity e : level.getAllEntities())
            {
                if (e instanceof SaibamanPetEntity pet)
                {
                    allLoaded.add(pet.getUUID());
                    if (pet.isAlive() && owner.equals(pet.getOwnerUUID()))
                    {
                        ownedAlive.add(pet.getUUID());
                    }
                }
            }
        }

        // adopt any loaded-and-owned pet we somehow never tracked, and drop any tracked UUID that is loaded but no
        // longer a live pet of this owner (dead, or re-owned). Unloaded entries are left untouched and trusted.
        tracked.addAll(ownedAlive);
        tracked.removeIf(id -> allLoaded.contains(id) && !ownedAlive.contains(id));

        int count = tracked.size();
        if (tracked.isEmpty())
        {
            OWNED.remove(owner);
        }
        return count;
    }

    /**
     * Track a pet as it joins the level (covers both a fresh spawn and a chunk load). Server-side only. Runs at
     * LOWEST priority and does NOT receive canceled events, so a join that {@code ContentGate} cancels on a keyless
     * dedicated server (saibaman is gated content) is never tracked: that entity is never added to the world, so its
     * {@link SaibamanPetEntity#remove} never fires and tracking it would leak a slot that could never be freed.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoin(EntityJoinLevelEvent event)
    {
        if (event.getLevel().isClientSide)
        {
            return;
        }
        if (event.getEntity() instanceof SaibamanPetEntity pet)
        {
            track(pet.getOwnerUUID(), pet.getUUID());
        }
    }

    /** Drop the whole registry on shutdown so it cannot leak stale entries into the next world (singleplayer) or run. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event)
    {
        synchronized (SaibamanPetRegistry.class)
        {
            OWNED.clear();
        }
    }
}
