package net.shurui.shuruisutilities.compat.sophisticatedbackpacks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

/**
 * Reads and writes Sophisticated Backpacks' per-server contents store so the shard vault can carry a
 * backpack's items between servers.
 *
 * <h2>Why the vault could not already do this</h2>
 *
 * <p>A Sophisticated Backpacks item carries only a {@code contentsUuid} in its NBT. The actual items live in a
 * single world-global {@code SavedData} ({@code sophisticatedbackpacks.dat}, class
 * {@code net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage}), a {@code Map<UUID, CompoundTag>} keyed
 * by that UUID. So the backpack ITEM rides along in the player's inventory NBT for free, but its contents never
 * appear in the player's NBT at all: they sit in the origin server's storage file. After a hop the arriving
 * server has no entry for that UUID, so the backpack opens empty. Nothing was broken; the contents were never
 * carried.
 *
 * <h2>Why reflection rather than a compileOnly dependency</h2>
 *
 * <p>Sophisticated Backpacks is NOT on this project's compile classpath, and adding the jar to {@code libs/} for
 * a handful of calls is more weight than the escape hatch the optional-dependency pattern sanctions for exactly
 * this case. Every lookup here is resolved once and cached; any failure leaves {@link #AVAILABLE} false so both
 * entry points degrade to doing nothing rather than throwing on every hop.
 *
 * <h2>What the API looks like (read out of the 3.24.59.1960 jar)</h2>
 * <ul>
 *   <li>{@code static BackpackStorage BackpackStorage.get()}: the server-global instance, backed by the
 *       overworld's {@code DimensionDataStorage}. On the server thread it returns the real store; off it, a
 *       throwaway client copy, which is why every call here is on the server thread only.</li>
 *   <li>{@code private final Map<UUID, CompoundTag> backpackContents}: read directly for a present-or-absent
 *       lookup, because the only public reader ({@code getOrCreateBackpackContents}) CREATES an empty entry and
 *       marks the store dirty for a UUID it has never seen, which we do not want to do to the ORIGIN server on a
 *       capture.</li>
 *   <li>{@code void setBackpackContents(UUID, CompoundTag)}: writes, and marks dirty itself. Note its quirk: if
 *       the UUID is already present it MERGES the incoming keys into the existing tag rather than replacing, so a
 *       clean overwrite means calling {@link #remove(UUID)} first.</li>
 *   <li>{@code void removeBackpackContents(UUID)}: drops one entry and marks dirty.</li>
 * </ul>
 */
final class BackpackStorageAccess
{
    private BackpackStorageAccess()
    {
    }

    /** The NBT key on a backpack ItemStack that holds its contents UUID, as a 4-int array. */
    static final String CONTENTS_UUID_TAG = "contentsUuid";

    private static final Method GET;
    private static final Field CONTENTS_MAP;
    private static final Method SET_CONTENTS;
    private static final Method REMOVE_CONTENTS;
    private static final boolean AVAILABLE;

    static
    {
        Method get = null;
        Field map = null;
        Method set = null;
        Method remove = null;
        try
        {
            Class<?> storage = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage");
            get = storage.getMethod("get");
            set = storage.getMethod("setBackpackContents", UUID.class, CompoundTag.class);
            remove = storage.getMethod("removeBackpackContents", UUID.class);
            map = storage.getDeclaredField("backpackContents");
            map.setAccessible(true);
        }
        catch (Throwable t)
        {
            get = null;
            map = null;
            set = null;
            remove = null;
        }
        GET = get;
        CONTENTS_MAP = map;
        SET_CONTENTS = set;
        REMOVE_CONTENTS = remove;
        AVAILABLE = get != null && map != null && set != null && remove != null;
    }

    /** True when Sophisticated Backpacks is present AND its storage still has the shape this class drives. */
    static boolean available()
    {
        return AVAILABLE;
    }

    /** The server-global storage instance, or null on any failure. Server thread only. */
    private static Object storage() throws Exception
    {
        return GET.invoke(null);
    }

    /**
     * The stored contents for one backpack UUID, or null when this server has no entry for it. Reads the private
     * map directly so a capture never creates an entry on the origin server for a UUID it did not already hold.
     */
    static CompoundTag read(UUID id) throws Exception
    {
        if (!AVAILABLE || id == null)
            return null;
        Object store = storage();
        if (store == null)
            return null;
        @SuppressWarnings("unchecked")
        Map<UUID, CompoundTag> map = (Map<UUID, CompoundTag>) CONTENTS_MAP.get(store);
        CompoundTag tag = map == null ? null : map.get(id);
        return tag == null ? null : tag;
    }

    /**
     * Write contents for one backpack UUID, replacing whatever was there. Removes first so
     * {@code setBackpackContents} takes its clean put branch instead of merging keys into a stale entry left by
     * an earlier visit to this server.
     */
    static void write(UUID id, CompoundTag contents) throws Exception
    {
        if (!AVAILABLE || id == null || contents == null)
            return;
        Object store = storage();
        if (store == null)
            return;
        REMOVE_CONTENTS.invoke(store, id);
        SET_CONTENTS.invoke(store, id, contents);
    }

    /**
     * Drop this server's entry for one backpack UUID. Used when custody of a backpack ITEM leaves this server (a
     * trade, an auction listing, or a confirmed hop): once the contents are durably carried elsewhere, the copy
     * here must go so exactly one server holds them. A no-op when there is no entry.
     */
    static void remove(UUID id) throws Exception
    {
        if (!AVAILABLE || id == null)
            return;
        Object store = storage();
        if (store == null)
            return;
        REMOVE_CONTENTS.invoke(store, id);
    }

    /** How many item entries a stored contents tag holds, for logging and the stuck check. */
    static int itemCount(CompoundTag contents)
    {
        if (contents == null)
            return 0;
        // The item list is written by SavedData under "inventory" as a standard ItemStackHandler tag. A missing
        // or differently named list simply reports zero, which is the correct answer for "nothing to carry".
        CompoundTag inv = contents.getCompound("inventory");
        return inv.getList("Items", 10).size();
    }
}
