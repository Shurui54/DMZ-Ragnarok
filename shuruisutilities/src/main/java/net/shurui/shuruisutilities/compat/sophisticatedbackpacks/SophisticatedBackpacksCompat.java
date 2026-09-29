package net.shurui.shuruisutilities.compat.sophisticatedbackpacks;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Carries Sophisticated Backpacks contents alongside the BACKPACK ITEM wherever it goes: across a shard hop with the
 * player, and across a cross-server trade or auction with the item. Classload-safe entry point: nothing here imports
 * a Sophisticated Backpacks type, and every touch of its storage goes through {@link BackpackStorageAccess}, which is
 * pure reflection, so a missing mod is simply a no-op.
 *
 * <h2>Why the contents have to travel with the item</h2>
 *
 * <p>A Sophisticated Backpacks item carries only a {@code contentsUuid}; the items live in a PER-SERVER global store
 * keyed by that UUID ({@link BackpackStorageAccess}). So the item rides in a player's inventory NBT (or a trade/auction
 * row) for free, but its contents do NOT: they sit in the origin server's store. If they are merely COPIED to a
 * destination and left on the origin, both servers then hold a full copy for the same UUID, which is a duplication
 * exploit: bring the item to either server and open it. The rule this class enforces is therefore that a backpack's
 * contents live on exactly ONE server at a time, the one whose store currently holds the item.
 *
 * <h2>The two directions</h2>
 * <ul>
 *   <li><b>CAPTURE</b> walks an NBT tree (the whole vault payload for a hop, or a single staged item for a trade)
 *       looking for {@code contentsUuid} tags. For each it reads that backpack's contents out of THIS server's store
 *       and, because a backpack can hold other backpacks, deep-scans those contents for more UUIDs and repeats. A
 *       hop capture COPIES (the origin copy is cleared only later, once the destination write is confirmed, see
 *       {@code ShardPayload}); a trade or auction capture MOVES, removing the contents from the origin store in the
 *       same breath that takes custody of the item.</li>
 *   <li><b>APPLY</b> writes each carried contents tag into THIS server's store under the same UUID, overwriting any
 *       stale entry, before the item can be opened. An empty carried tag is a CLEAR marker: it removes any stale
 *       entry so an emptied backpack never inherits a destination's older, fuller copy.</li>
 * </ul>
 */
public final class SophisticatedBackpacksCompat
{
    private SophisticatedBackpacksCompat()
    {
    }

    private static final String MODID = "sophisticatedbackpacks";

    /**
     * Guard against a single pathological backpack (or a chain of nested ones) blowing up a vault or trade write. The
     * payload is gzipped NBT in a LONGBLOB, so these limits are on the UNCOMPRESSED bytes and are deliberately
     * generous: a normal loadout is well under a megabyte. When a limit bites we log loudly and carry what fits
     * rather than truncating somebody's items in silence.
     */
    private static final long MAX_SINGLE_BYTES = 32L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 96L * 1024 * 1024;

    /** A hard stop on how many distinct backpacks one capture can carry, so a cycle or a griefed nest cannot spin. */
    private static final int MAX_BACKPACKS = 4096;

    /**
     * The backpack UUIDs the vault DELIVERED a baseline for this session, keyed by player, one set per online player.
     *
     * <h2>Why this has to exist: a fabricated empty and a deliberate empty look identical</h2>
     *
     * <p>Sophisticated Backpacks' own {@code getOrCreateBackpackContents} FABRICATES an entry the first time a player
     * opens a backpack this server's store has never seen: {@code {inventory:{Items:[]}, partitioner, settings}}. That
     * tag has keys, so {@code CompoundTag.isEmpty()} is false, yet it holds no items. In the store it is
     * byte-for-byte indistinguishable from a backpack the player genuinely EMPTIED. Capture cannot tell them apart by
     * looking at the tag, and getting it wrong is a data-loss bug in one direction and a duplication bug in the other:
     *
     * <ul>
     *   <li>Carry a FABRICATED blank and it overwrites the vault's good copy with nothing, which is how a backpack
     *       gets silently emptied network-wide (the item still references the UUID, so the wipe follows it to every
     *       shard, and eventually onto the one server still holding a real orphaned copy).</li>
     *   <li>Do NOT carry a DELIBERATE empty and the vault keeps its stale, fuller copy; on the next hop those items
     *       come back while the player is also still holding the ones they took out, duplicating everything.</li>
     * </ul>
     *
     * <p>The discriminator is delivery. On arrival {@link #applyFromVault} records every UUID the vault handed us a
     * baseline for. During a hop capture, a store entry with no items is a DELIBERATE empty only if its UUID is in
     * this set (we knew its real state and the player emptied it since), and otherwise a fabricated blank we must not
     * propagate. This is airtight against duplication because the only entries we ever SKIP are ones with zero items:
     * skipping something with no items can never carry items back to duplicate, and a deliberate empty (the one case
     * that MUST overwrite) is always in this set, because emptying a backpack requires the vault to have delivered its
     * contents here first.
     *
     * <p>Per player and cleared on their logout ({@link #forgetSession}), never left to accumulate: a STALE entry
     * surviving into a later session on the same process is the one thing that would reintroduce the loss (a
     * fabricated blank whose UUID lingered here would be mistaken for a deliberate empty and overwrite a good copy).
     * The map is process-local, so each shard answers for its own deliveries and a crash simply drops it.
     */
    private static final Map<UUID, Set<UUID>> DELIVERED_THIS_SESSION = new ConcurrentHashMap<>();

    private static boolean present()
    {
        return ModList.get().isLoaded(MODID) && BackpackStorageAccess.available();
    }

    /** True when the integration resolved, so a caller can decide whether a transfer even needs to carry anything. */
    public static boolean available()
    {
        return present();
    }

    /** Log once at startup whether the integration actually resolved, the same way the cosmetic transfer does. */
    public static void init()
    {
        if (!ModList.get().isLoaded(MODID))
            return;
        LoggingHandler.sulog.info("[shard] Sophisticated Backpacks vault transfer: {}",
                BackpackStorageAccess.available()
                        ? "ready"
                        : "UNAVAILABLE (the mod is installed but its storage API did not resolve; backpack "
                                + "contents will not cross a hop)");
    }

    /**
     * Every backpack's contents that this player is carrying, keyed by UUID string, or null when the mod is absent
     * or nothing was found. COPIES: the origin keeps its copy until {@code ShardPayload} confirms the destination
     * write and clears it, so a failed hop never destroys items. Never throws.
     *
     * @param payload  the assembled vault payload, already holding the character, ender chest, caps and forge data
     * @param playerId the leaving player, so a store entry with no items can be read as a DELIBERATE empty (its UUID
     *                 was delivered here this session) rather than a blank the store fabricated on open, which must
     *                 not be carried. See {@link #DELIVERED_THIS_SESSION}.
     */
    public static CompoundTag captureForVault(CompoundTag payload, UUID playerId)
    {
        if (payload == null || !present())
            return null;
        try
        {
            CompoundTag out = new CompoundTag();
            Set<UUID> authoritative = playerId == null ? null : DELIVERED_THIS_SESSION.get(playerId);
            gather(payload, out, false, false, authoritative);
            if (out.isEmpty())
                return null;
            LoggingHandler.sulog.info("[shard] Captured {} backpack(s) for the vault.", out.size());
            return out;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[shard] Could not capture backpack contents: {}", t.toString());
            return null;
        }
    }

    /**
     * The backpack contents reachable from ONE item's NBT (and any backpacks nested inside them), MOVED out of this
     * server's store so custody travels with the item into a trade offer or an auction listing. Every referenced
     * UUID gets an entry: a real contents tag when this server holds one, or an EMPTY tag as a clear marker so the
     * recipient's store cannot keep a stale, fuller copy for that UUID. Returns an empty tag when the item carries
     * no backpack (never null, so a caller can always store the column unconditionally). Never throws. Server thread
     * only: the store hands out a throwaway client copy off-thread.
     *
     * <p>The removal is the point of custody transfer. It MUST run inside the same operation that persists the item
     * into the trade or auction row (see {@code ShardTrade.commitAdd} and {@code AuctionServer.handleCreate}): the
     * item and its contents leave this server together or not at all. If a caller cannot persist the row, it must
     * put the contents back with {@link #restoreToStore}.
     */
    public static CompoundTag captureForTransfer(Tag itemNode)
    {
        CompoundTag out = new CompoundTag();
        if (itemNode == null || !present())
            return out;
        try
        {
            // A trade or auction MOVES custody with the item, so a referenced UUID with no items becomes a clear
            // marker for the recipient rather than being skipped. The delivered set does not apply: custody, not a
            // session baseline, is the authority here.
            gather(itemNode, out, true, true, null);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[shard] Could not capture backpack contents for a transfer: {}", t.toString());
        }
        return out;
    }

    /**
     * Put contents captured by {@link #captureForTransfer} back into this server's store, used when the row write
     * that would have taken custody failed and the item is handed straight back to its owner. Overwrites, so a
     * clear marker removes the entry and a real tag restores it. Server thread only. Never throws.
     */
    public static void restoreToStore(CompoundTag carried)
    {
        applyToStore(carried, "restore");
    }

    /**
     * Write carried contents into this server's store on DELIVERY of a traded or auctioned item, before the item is
     * handed over. Same write path {@link #applyFromVault} uses. Server thread only. Never throws.
     */
    public static void deliverToStore(CompoundTag carried)
    {
        applyToStore(carried, "deliver");
    }

    /**
     * Write carried backpack contents into this server's store under their original UUIDs. Never throws. Runs at
     * arrival, before the player can open a backpack, so the authoritative store already holds the items by the time
     * any GUI reads it. An empty carried tag clears any stale entry for that UUID.
     */
    public static void applyFromVault(ServerPlayer player, CompoundTag carried)
    {
        if (player == null || carried == null || carried.isEmpty() || !present())
            return;
        // Record what the vault delivered a baseline for BEFORE writing it, so a later capture can read a now-empty
        // one of these as a deliberate empty rather than a fabricated blank. Every carried key counts, real tag or
        // clear marker alike: a clear marker still means "the vault knows this backpack and it is empty", which is
        // exactly the baseline that makes a subsequent empty deliberate rather than invented. See
        // DELIVERED_THIS_SESSION for why getting this wrong loses items in one direction and duplicates in the other.
        Set<UUID> delivered = DELIVERED_THIS_SESSION.computeIfAbsent(player.getUUID(), k -> ConcurrentHashMap.newKeySet());
        for (String key : carried.getAllKeys())
        {
            UUID id = parseUuid(key);
            if (id != null)
                delivered.add(id);
        }
        int written = applyToStore(carried, "arrival");
        if (written > 0)
            LoggingHandler.sulog.info("[shard] Applied {} backpack(s) for {}.",
                    written, player.getGameProfile().getName());
    }

    /**
     * Forget a player's delivered-this-session baselines, called from the shard logout handler. This MUST run when a
     * player leaves a server: a stale set surviving into their next session on the same process would let a fabricated
     * blank be mistaken for a deliberate empty and overwrite a good copy, which is the exact loss this whole mechanism
     * exists to prevent. Cheap and idempotent, so it is safe to call on any logout whether or not backpacks travelled.
     */
    public static void forgetSession(UUID playerId)
    {
        if (playerId != null)
            DELIVERED_THIS_SESSION.remove(playerId);
    }

    /**
     * Whether two stored backpack contents tags hold the SAME items, comparing only the item-bearing lists and
     * ignoring volatile bookkeeping (settings, render info, and the like). Used by the hop-confirm in
     * {@code ShardPayload} to decide whether the vault durably holds a backpack's items before the origin copy is
     * cleared. The confirm's real question is "are the items safely in the vault", not "is every byte identical", and
     * whole-compound equality answers the wrong one: it treats a difference in non-item bookkeeping as a mismatch and
     * so refuses to clear, leaving the origin copy orphaned forever. This compares what actually matters and fails
     * toward KEEPING the origin copy: an unequal item picture means we do not clear.
     */
    public static boolean sameStoredItems(CompoundTag a, CompoundTag b)
    {
        if (a == null || b == null)
            return false;
        return collectItemLists(a, new java.util.ArrayList<>()).equals(collectItemLists(b, new java.util.ArrayList<>()));
    }

    /**
     * How many item stacks a stored contents tag holds, counting the inventory and the installed upgrades exactly
     * the way {@link #sameStoredItems} compares them. For diagnostics: a confirm that reports a difference should
     * say how many items each side actually holds, not how many top level keys the compound has.
     */
    public static int countStoredItems(CompoundTag contents)
    {
        if (contents == null)
            return 0;
        int n = 0;
        for (ListTag list : collectItemLists(contents, new java.util.ArrayList<>()))
            n += list.size();
        return n;
    }

    /**
     * Collect every {@code Items} list in a contents tree, in a stable tree order, as the canonical item picture of a
     * backpack. Sophisticated Backpacks serialises both the inventory AND the installed upgrades as ItemStackHandler
     * tags, each written as {@code {Items:[...], Size:n}}, so gathering every {@code Items} list captures the stored
     * items and the upgrade items together while ignoring settings and render bookkeeping. Order is deterministic
     * (a fixed walk over the same structure on both sides), so two faithful serialisations of the same contents
     * produce equal lists.
     */
    private static java.util.List<ListTag> collectItemLists(Tag node, java.util.List<ListTag> out)
    {
        if (node instanceof CompoundTag compound)
        {
            for (String key : new java.util.TreeSet<>(compound.getAllKeys()))
            {
                Tag child = compound.get(key);
                if ("Items".equals(key) && child instanceof ListTag list)
                    out.add(list);
                else
                    collectItemLists(child, out);
            }
        }
        else if (node instanceof ListTag list)
        {
            for (Tag child : list)
                collectItemLists(child, out);
        }
        return out;
    }

    /**
     * Drop this server's entries for the given backpack UUIDs. Called by {@code ShardPayload} on the server thread
     * ONLY after it has confirmed the durable vault payload carries these contents and the player is no longer
     * online here, so the copy left behind by a hop's COPY becomes a MOVE without ever risking the items. A no-op
     * when the mod is absent. Never throws.
     */
    public static void clearFromStore(Collection<UUID> ids)
    {
        if (ids == null || ids.isEmpty() || !present())
            return;
        int cleared = 0;
        for (UUID id : ids)
        {
            try
            {
                BackpackStorageAccess.remove(id);
                cleared++;
            }
            catch (Throwable t)
            {
                LoggingHandler.sulog.warn("[shard] Could not clear origin backpack {}: {}", id, t.toString());
            }
        }
        if (cleared > 0)
            LoggingHandler.sulog.info("[shard] Cleared {} backpack(s) from the origin store after a confirmed hop.",
                    cleared);
    }

    /**
     * Breadth-first over an NBT tree: for every {@code contentsUuid} it references (at any nesting depth, through
     * nested backpacks' own contents), read this server's stored contents and add them to {@code out}. When
     * {@code move} is set each carried backpack is removed from the store as it is taken. When {@code markAbsent} is
     * set a referenced UUID this server has nothing for is still recorded as an empty CLEAR marker, so a recipient
     * cannot keep a stale copy. The size and count caps bound a pathological nest.
     *
     * <p>An entry with NO items (empty, absent, or a store-fabricated blank) is the delicate case, because a blank
     * the store invented on open looks exactly like a backpack the player emptied. On a MOVE custody decides it and
     * we leave a clear marker. On a hop COPY it is decided by {@code authoritative}: a no-item entry whose UUID the
     * vault delivered a baseline for this session is a DELIBERATE empty and is carried as a clear marker (so the
     * vault stops holding a stale fuller copy), and one whose UUID was never delivered is a fabricated blank and is
     * SKIPPED, so it can never overwrite a good copy. Skipping a no-item entry can lose no items, and a deliberate
     * empty is always in {@code authoritative}, so this cannot duplicate either. See {@link #DELIVERED_THIS_SESSION}.
     *
     * @param authoritative the leaving player's delivered-this-session UUIDs, or null for a MOVE (where it is unused)
     */
    private static void gather(Tag rootNode, CompoundTag out, boolean move, boolean markAbsent, Set<UUID> authoritative)
            throws Exception
    {
        Set<UUID> seen = new HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        collectUuids(rootNode, seen, queue);

        long total = 0;
        while (!queue.isEmpty() && out.size() < MAX_BACKPACKS)
        {
            UUID id = queue.poll();
            CompoundTag contents = BackpackStorageAccess.read(id);
            // No items here means empty, absent, or a blank the store fabricated on open. "No items" is tested by
            // hasAnyItems, NOT by CompoundTag.isEmpty(): a fabricated blank whose GUI was opened and touched gains
            // keys (inventory, partitioner, settings) so it is not isEmpty(), yet holds nothing, and the old
            // isEmpty() test let it through to be carried as genuine zero-item contents, which is the network-wide
            // emptying bug. hasAnyItems also treats installed upgrades as content (they serialise as an
            // ItemStackHandler too), so an upgrades-only backpack is carried, not mistaken for a blank and dropped.
            if (contents == null || contents.isEmpty() || !hasAnyItems(contents))
            {
                // A no-item entry is one of two things, and telling them apart is the whole ballgame: a backpack the
                // player DELIBERATELY emptied, or a BLANK the store fabricated when a backpack was opened here before
                // its contents had been delivered (Sophisticated Backpacks' getOrCreateBackpackContents inserts a
                // bare, key-less compound the first time this server sees a UUID). The discriminator is structural and
                // needs no session bookkeeping: a real backpack always carries an "inventory" child once its handler
                // has saved (an empty one is {Items:[],Size:n}, which is not an empty compound), and a fabricated
                // blank never does. This is exactly the mod's own BackpackStorage.isPlayerBackpackOrNotEmpty test. A
                // fabricated blank proves only that THIS server has not materialised the contents (a hop mid-flight,
                // a spurious clear, or a crash while opening, which is bug 993), never that the player emptied the
                // backpack, so it must NEVER become a clear marker: doing so overwrites the vault's real copy with
                // nothing and the emptiness then follows the item to every shard. It is dropped locally on a move so
                // custody still leaves cleanly, and left entirely on a hop so the vault's good copy stands and
                // re-delivers on the next login.
                if (isFabricatedBlank(contents))
                {
                    if (move)
                        BackpackStorageAccess.remove(id);
                    continue;
                }
                if (move)
                {
                    // Custody moves with the item, so record a clear marker and drop the local emptied entry. Fails
                    // toward the recipient holding nothing for this UUID, which is correct: the sender no longer has
                    // the items either.
                    if (markAbsent)
                        out.put(id.toString(), new CompoundTag());
                    BackpackStorageAccess.remove(id);
                }
                else if (authoritative != null && authoritative.contains(id))
                {
                    // Hop, a genuine deliberate empty (its "inventory" child is present) whose baseline the vault
                    // delivered this session. Carry a clear marker so the vault records the emptying. Fails toward
                    // OVERWRITE, which is the safe direction here: the items the player took out live in their
                    // inventory (carried in the CHARACTER tag), so recording the empty loses nothing, whereas NOT
                    // recording it would leave the vault's stale fuller copy to come back and duplicate on the next hop.
                    out.put(id.toString(), new CompoundTag());
                }
                else
                {
                    // Hop, a genuine empty this UUID's baseline was never delivered here for. SKIP it. Fails toward
                    // KEEPING whatever the vault already holds, and skipping a no-item entry can lose no items.
                }
                continue;
            }
            long size = measure(contents);
            if (size > MAX_SINGLE_BYTES)
            {
                // Too big to carry. Leave it exactly where it is (do NOT remove on a move, or the items would be
                // lost with nothing carrying them); the item arrives holding its origin contents on the next hop.
                LoggingHandler.sulog.warn("[shard] Backpack {} is {} bytes, over the {} byte per-backpack cap; "
                        + "leaving its contents on the origin server.", id, size, MAX_SINGLE_BYTES);
                continue;
            }
            if (total + size > MAX_TOTAL_BYTES)
            {
                LoggingHandler.sulog.warn("[shard] More than {} bytes of backpack contents in one capture; {} "
                        + "backpack(s) past this point stay on the origin server.", MAX_TOTAL_BYTES, queue.size() + 1);
                break;
            }
            total += size;
            CompoundTag copy = contents.copy();
            out.put(id.toString(), copy);
            // A carried backpack can itself contain backpacks: fold their UUIDs into the same queue.
            collectUuids(copy, seen, queue);
            if (move)
                BackpackStorageAccess.remove(id);
        }
    }

    /**
     * Write each carried UUID into this server's store, overwriting. An empty carried tag removes the entry (a clear
     * marker); a real tag is written and read back to confirm it landed. Returns how many real (non-clear) entries
     * were written. Never throws.
     */
    private static int applyToStore(CompoundTag carried, String phase)
    {
        if (carried == null || carried.isEmpty() || !present())
            return 0;
        int written = 0;
        for (String key : carried.getAllKeys())
        {
            UUID id;
            try
            {
                id = UUID.fromString(key);
            }
            catch (IllegalArgumentException bad)
            {
                continue;   // not one of ours; leave it alone
            }
            CompoundTag contents = carried.getCompound(key);
            try
            {
                if (contents.isEmpty())
                {
                    // Clear marker: the sender holds nothing for this UUID, so any stale entry here must go.
                    BackpackStorageAccess.remove(id);
                    continue;
                }
                BackpackStorageAccess.write(id, contents.copy());
                CompoundTag back = BackpackStorageAccess.read(id);
                if (back != null && BackpackStorageAccess.itemCount(back) >= BackpackStorageAccess.itemCount(contents))
                    written++;
                else
                    LoggingHandler.sulog.warn("[shard] Backpack {} did not read back after a {} write.", id, phase);
            }
            catch (Throwable t)
            {
                LoggingHandler.sulog.warn("[shard] Could not apply backpack {} on {}: {}", id, phase, t.toString());
            }
        }
        return written;
    }

    /**
     * Walk an NBT tree and enqueue every not-yet-seen backpack UUID it references. A backpack item stores its UUID
     * as a 4-int array under {@code contentsUuid} inside the item's own {@code tag}, so a plain recursive scan for
     * that key finds them wherever they sit, at any nesting depth, without knowing the inventory layout.
     */
    private static void collectUuids(Tag node, Set<UUID> seen, Deque<UUID> queue)
    {
        if (node instanceof CompoundTag compound)
        {
            if (compound.hasUUID(BackpackStorageAccess.CONTENTS_UUID_TAG))
            {
                UUID id = compound.getUUID(BackpackStorageAccess.CONTENTS_UUID_TAG);
                if (seen.add(id))
                    queue.add(id);
            }
            for (String key : compound.getAllKeys())
                collectUuids(compound.get(key), seen, queue);
        }
        else if (node instanceof ListTag list)
        {
            for (Tag child : list)
                collectUuids(child, seen, queue);
        }
    }

    /**
     * Whether a stored contents tag holds any ItemStacks at all, counting both the inventory AND installed upgrades.
     * Sophisticated Backpacks serialises every ItemStackHandler (the inventory and the upgrade handler alike) as
     * {@code {Items:[...], Size:n}}, so a non-empty {@code Items} list anywhere in the tree means real content. This
     * is what separates a backpack with something in it (or upgrades on it) from a blank the store fabricated on
     * open, and it is deliberately structural rather than keyed to one field name, so an upgrades-only backpack is
     * never mistaken for empty and dropped. A nested backpack held in a slot counts as an item here (it is a real
     * ItemStack occupying a slot); its own contents ride separately under their own UUID.
     */
    private static boolean hasAnyItems(Tag node)
    {
        if (node instanceof CompoundTag compound)
        {
            for (String key : compound.getAllKeys())
            {
                Tag child = compound.get(key);
                if ("Items".equals(key) && child instanceof ListTag list && !list.isEmpty())
                    return true;
                if (hasAnyItems(child))
                    return true;
            }
            return false;
        }
        if (node instanceof ListTag list)
        {
            for (Tag child : list)
                if (hasAnyItems(child))
                    return true;
        }
        return false;
    }

    /**
     * Whether a stored contents tag is a BLANK the store fabricated on open, as opposed to a backpack a player
     * deliberately emptied. Sophisticated Backpacks' {@code getOrCreateBackpackContents} inserts a bare, key-less
     * compound the first time this server sees a UUID, which happens when a backpack is opened here before its
     * contents have been delivered (a hop mid-flight, a store entry cleared out from under the player, or the crash
     * while opening that produced bug 993). Only once a slot is edited does the handler write an {@code inventory}
     * child, so a backpack that was genuinely emptied always carries an {@code inventory} compound ({@code
     * {Items:[],Size:n}}, which is not an empty compound) and a fabricated blank never does. This is the same test
     * the mod itself uses in {@code BackpackStorage.isPlayerBackpackOrNotEmpty}. A fabricated blank must never be
     * carried as a clear marker: it would overwrite the vault's real copy with nothing and the emptiness would then
     * follow the item to every shard.
     */
    private static boolean isFabricatedBlank(CompoundTag contents)
    {
        return contents == null || contents.getCompound("inventory").isEmpty();
    }

    /** Parse a stored key as a backpack UUID, or null when it is not one of ours. */
    private static UUID parseUuid(String key)
    {
        try
        {
            return UUID.fromString(key);
        }
        catch (IllegalArgumentException bad)
        {
            return null;
        }
    }

    /** Serialised size of a tag in bytes, without retaining the buffer, for the size guards. */
    private static long measure(CompoundTag tag) throws Exception
    {
        CountingOutputStream counter = new CountingOutputStream();
        try (DataOutputStream out = new DataOutputStream(counter))
        {
            NbtIo.write(tag, out);
        }
        return counter.count;
    }

    /** Counts bytes written and discards them, so measuring a large tag does not allocate a copy of it. */
    private static final class CountingOutputStream extends OutputStream
    {
        private long count;

        @Override
        public void write(int b)
        {
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len)
        {
            count += len;
        }
    }
}
