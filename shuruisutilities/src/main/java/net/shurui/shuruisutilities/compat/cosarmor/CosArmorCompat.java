package net.shurui.shuruisutilities.compat.cosarmor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

// classload-safe entry point for Cosmetic Armor Reworked. NO CAR imports here; the drop-cancel lives in
// CosArmorGraveCompat and the vault transfer in CosArmorVaultCompat, both only reached when
// cosmeticarmorreworked is loaded.
public final class CosArmorCompat
{
    // not a utility class any more: one instance is registered on the Forge bus for onLoggedIn.
    private CosArmorCompat() {}

    // wire up the keepinv drop cancel if CAR is present, else no-op
    public static void init()
    {
        if (!ModList.get().isLoaded("cosmeticarmorreworked"))
            return;
        try
        {
            CosArmorGraveCompat.register();
            // Say out loud whether the vault transfer is actually wired. Two attempts at this failed silently
            // because every path degrades to a no-op, so the one thing worth logging is whether the API resolved.
            LoggingHandler.sulog.info("[shard] Cosmetic armor vault transfer: {}",
                    CosArmorVaultCompat.available()
                            ? "ready"
                            : "UNAVAILABLE (the mod is installed but its API did not resolve; cosmetics will not "
                                    + "cross a hop)");
            // instance listener: the vault apply ordering guard in onLoggedIn.
            MinecraftForge.EVENT_BUS.register(new CosArmorCompat());
            LoggingHandler.sulog.info("[Grave] Cosmetic Armor Reworked keep-inventory integration enabled.");
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[Grave] Failed to enable Cosmetic Armor Reworked keep-inventory integration: {}", t.toString());
        }
    }

    /** True when Cosmetic Armor Reworked is installed and its classes are safe to touch. */
    private static boolean present()
    {
        return ModList.get().isLoaded("cosmeticarmorreworked");
    }

    /**
     * This player's cosmetic inventory for the shard vault, or null when the mod is absent or there is nothing to
     * carry. Never throws: cosmetics are the least important thing in a hop, so a failure here must not be allowed
     * to abort a capture that is also carrying the player's real inventory.
     */
    public static CompoundTag captureForVault(UUID playerId)
    {
        if (playerId == null || !present())
            return null;
        try
        {
            CompoundTag tag = CosArmorVaultCompat.capture(playerId);
            LoggingHandler.sulog.info("[shard] Cosmetic armor capture for {}: {}", playerId,
                    describe(tag));
            return tag;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[shard] Could not capture cosmetic armor for {}: {}", playerId, t.toString());
            return null;
        }
    }

    /**
     * Queue a vault-carried cosmetic inventory to be written onto this player. Never throws.
     *
     * <p>WHY THIS IS QUEUED RATHER THAN WRITTEN NOW, and it is the whole reason the first attempts did nothing:
     * Cosmetic Armor's own login handler does
     *
     * <pre>
     *     CommonCache.invalidate(uuid);      // throw away anything cached
     *     getCosArmorInventory(uuid);        // reload from THIS server's &lt;uuid&gt;.cosarmor file
     * </pre>
     *
     * so anything written before it runs is discarded and replaced by the destination's own (empty) stored copy.
     * This is called at HIGHEST priority from the shard restore, which is the earliest possible point, so it cannot
     * win that race on its own. Instead it parks the carried inventory in {@link #PENDING} and arms the self-heal
     * schedule, and the actual writes happen LATER, from two places that between them cover the timing:
     *
     * <ul>
     *   <li>a {@code PlayerLoggedInEvent} listener at LOWEST priority (Cosmetic Armor registers at NORMAL, so ours
     *       runs after its reload within the same dispatch), as the fast path for the common case, and</li>
     *   <li>the tick-based self-heal in {@link #onServerTick}, whose first point lands well after the whole login
     *       sequence, RE-APPLIES the parked inventory into Cosmetic Armor's server-side store, and only then clears
     *       the pending entry once a re-read confirms the items actually stuck.</li>
     * </ul>
     *
     * <p>Neither write removes the pending entry itself, so a later invalidation cannot leave us with nothing to
     * replay; the pending entry is dropped only on confirmation, or when the schedule is exhausted, so we never
     * fight a player who deliberately empties their own cosmetic slots.
     */
    public static void applyFromVault(ServerPlayer player, CompoundTag tag)
    {
        if (player == null || tag == null || tag.isEmpty() || !present())
            return;
        UUID playerId = player.getUUID();
        PENDING.put(playerId, tag.copy());
        scheduleSelfHeal(playerId);
    }

    /**
     * Fast path for the common case. LOWEST so Cosmetic Armor's own NORMAL-priority login handler, which reloads
     * the inventory from this server's disk, has already run within this dispatch and cannot undo the write. This
     * does NOT clear the pending entry: the proven bug is that a further invalidation still clobbers this write, so
     * the authoritative apply-and-confirm is left to the tick self-heal below.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() == null)
            return;
        UUID playerId = event.getEntity().getUUID();
        CompoundTag tag = PENDING.get(playerId);
        if (tag != null && writePending(playerId))
            LoggingHandler.sulog.info("[shard] Cosmetic armor applied for {}: {}", playerId, describe(tag));
    }

    /**
     * Write the parked inventory into Cosmetic Armor's server-side store and push it to the client. Does not touch
     * {@link #PENDING}: callers decide when it is safe to stop replaying. Returns whether the write ran.
     */
    private static boolean writePending(UUID playerId)
    {
        CompoundTag tag = PENDING.get(playerId);
        if (tag == null || !present())
            return false;
        try
        {
            // setStackInSlot inside apply() fires Cosmetic Armor's own onInventoryChanged, which broadcasts each
            // slot, so the server-side write is what actually syncs the client. The SU-channel push below is belt
            // and braces for a proxy hop where those native per-slot packets do not arrive.
            CosArmorVaultCompat.apply(playerId, tag);
            sendOverSuChannel(playerId, tag);
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[shard] Could not apply cosmetic armor for {}: {}", playerId, t.toString());
            return false;
        }
    }

    /**
     * What a serialised cosmetic inventory actually contains.
     *
     * <p>Counting NBT keys was useless: {@code CAStacksBase.serializeNBT} always writes exactly three (Items, Size,
     * Hidden) whether the inventory is full or empty, so the first probe reported "3 key(s)" either way. The item
     * list length is the number that distinguishes carrying something from carrying nothing.
     */
    private static String describe(CompoundTag tag)
    {
        if (tag == null)
            return "null";
        return itemCount(tag) + " item(s), size " + tag.getInt("Size");
    }

    /** Number of items in a serialised cosmetic inventory: the count that tells full apart from empty. */
    private static int itemCount(CompoundTag tag)
    {
        return tag == null ? 0 : tag.getList("Items", 10).size();
    }

    /** Inventories waiting for Cosmetic Armor's login reload to get out of the way, then confirmed to have stuck. */
    private static final Map<UUID, CompoundTag> PENDING = new ConcurrentHashMap<>();

    /**
     * Arrival tick for a player whose cosmetics are still being self-healed, and how many attempts are left.
     *
     * <p>WHY repeated attempts are needed at all, on BOTH sides.
     *
     * <p>SERVER side. Cosmetic Armor's login handler invalidates its {@code CommonCache} for the arriving player and
     * reloads the inventory from THIS server's own file, which after a hop is empty. That reload clobbers whatever we
     * wrote at login, so the server-side store itself ends up wrong, not merely the client view. The first scheduled
     * point lands well after that handler has run, so re-applying the parked inventory there sticks.
     *
     * <p>CLIENT side. Cosmetic Armor's client clears its whole cosmetic cache when the connection ends:
     *
     * <pre>
     *     private void handleLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
     *         this.ClientCache.invalidateAll();
     *     }
     * </pre>
     *
     * <p>A switch between backend servers behind the proxy is exactly that event, so the client throws away every
     * cosmetic it knew, and its login handler deliberately skips the arriving player when it syncs, so YOUR own
     * cosmetics are never sent back to you. The wipe happens on the client's own schedule as the switch completes,
     * which is not something the server can observe, so the later scheduled points keep re-announcing over both
     * channels until one lands after the wipe. Cheap, bounded self-healing.
     */
    private static final Map<UUID, int[]> SELF_HEAL = new ConcurrentHashMap<>();

    /** Ticks after arrival to self-heal on: half a second, then 1, 3, 6 and 10 seconds. */
    private static final int[] SELF_HEAL_TICKS = { 10, 20, 60, 120, 200 };

    /**
     * Self-heal on each scheduled tick after arrival, then stop.
     *
     * <p>While a pending entry survives, each point RE-APPLIES it into Cosmetic Armor's server-side store (the same
     * write the login apply uses), because the proven failure is that store being wiped, not just the client view;
     * a re-read then confirms it stuck and drops the pending entry so we stop. Once dropped, the later points fall
     * through to a plain re-announce, the cheap client-side self-heal for a cache the client wiped on the hop.
     *
     * <p>The entry holds the arrival tick and the index of the next attempt, and the index ADVANCES on every fire.
     * The first version of this stored absolute deadlines and never advanced, so once the earliest passed it fired
     * every tick for as long as the player stayed connected. Bounded work is the whole point here: if the write
     * never confirms, the pending entry is dropped when the schedule is exhausted rather than replayed forever.
     */
    @SubscribeEvent
    public void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event)
    {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || SELF_HEAL.isEmpty())
            return;
        MinecraftServer server = event.getServer();
        if (server == null)
            return;
        int now = server.getTickCount();
        SELF_HEAL.entrySet().removeIf(entry ->
        {
            int[] state = entry.getValue();
            int arrived = state[0];
            int next = state[1];
            if (next >= SELF_HEAL_TICKS.length)
            {
                PENDING.remove(entry.getKey());
                return true;
            }
            if (now - arrived < SELF_HEAL_TICKS[next])
                return false;
            state[1] = next + 1;
            UUID playerId = entry.getKey();
            if (server.getPlayerList().getPlayer(playerId) == null)
            {
                PENDING.remove(playerId); // gone; nothing to heal
                return true;
            }
            selfHeal(playerId, SELF_HEAL_TICKS[next]);
            boolean done = state[1] >= SELF_HEAL_TICKS.length;
            if (done)
                PENDING.remove(playerId); // bounded: never re-apply beyond the last scheduled point
            return done;
        });
    }

    /**
     * One self-heal pass. Re-applies and confirms while there is still a pending entry, otherwise re-announces the
     * already-correct server-side inventory to nudge a client that wiped its cache on the hop.
     */
    private static void selfHeal(UUID playerId, int ticksAfter)
    {
        CompoundTag pending = PENDING.get(playerId);
        if (pending != null)
        {
            writePending(playerId);
            if (confirmStuck(playerId, pending))
            {
                PENDING.remove(playerId);
                LoggingHandler.sulog.info("[shard] Cosmetic armor confirmed for {} ({} ticks after arrival): {}",
                        playerId, ticksAfter, describe(pending));
            }
            else
            {
                LoggingHandler.sulog.info("[shard] Cosmetic armor re-applied for {} ({} ticks after arrival): {}",
                        playerId, ticksAfter, describe(pending));
            }
            return;
        }
        CosArmorVaultCompat.reannounce(playerId);
        // Re-send over OUR channel too. Cosmetic Armor's re-announce rides its own channel, which does not reach the
        // client after a hop; this one does, so several attempts ensure one lands after the client's cache wipe.
        sendOverSuChannel(playerId, CosArmorVaultCompat.captureQuietly(playerId));
        LoggingHandler.sulog.info("[shard] Cosmetic armor re-announced for {} ({} ticks after arrival)",
                playerId, ticksAfter);
    }

    /**
     * Whether the server-side inventory now actually holds what we carried. A re-read that comes back with at least
     * as many items as we parked means Cosmetic Armor's reload is done clobbering us and the write finally stuck, so
     * it is safe to stop replaying. An empty carry needs no confirmation and never fights the player.
     */
    private static boolean confirmStuck(UUID playerId, CompoundTag pending)
    {
        int want = itemCount(pending);
        if (want <= 0)
            return true;
        CompoundTag current = CosArmorVaultCompat.captureQuietly(playerId);
        return current != null && itemCount(current) >= want;
    }

    /**
     * Push a player's whole cosmetic inventory to their client over the suite's OWN network channel.
     *
     * <p>This is the crux of the fix. Every server-side re-broadcast over Cosmetic Armor's channel has failed
     * because those bytes do not survive a Velocity backend hop, and the server state is provably correct. This
     * channel (the same one tab list, ghosts and chat use) does survive the hop, and the client end writes the tag
     * straight into Cosmetic Armor's client cache. Best effort: cosmetics are the least important thing in a hop,
     * so a failure here must never propagate.
     */
    private static void sendOverSuChannel(UUID playerId, CompoundTag tag)
    {
        if (playerId == null || tag == null || tag.isEmpty())
            return;
        try
        {
            MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null)
                return;
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null)
                return;
            net.shurui.shuruisutilities.commons.network.NetworkUtils.sendTo(
                    new PacketCosArmorSync(playerId, tag), player);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[shard] Could not send cosmetic armor over SU channel for {}: {}",
                    playerId, t.toString());
        }
    }

    /** Arm the tick self-heal for a player who has just had a cosmetic inventory parked for them. */
    private static void scheduleSelfHeal(UUID playerId)
    {
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;
        SELF_HEAL.put(playerId, new int[] { server.getTickCount(), 0 });
    }
}
