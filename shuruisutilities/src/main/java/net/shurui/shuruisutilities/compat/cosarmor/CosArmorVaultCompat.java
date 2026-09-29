package net.shurui.shuruisutilities.compat.cosarmor;

import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

/**
 * Reads and writes a player's Cosmetic Armor Reworked inventory so the shard vault can carry it between servers.
 *
 * <h2>Why the vault could not already do this</h2>
 *
 * <p>{@code ShardPayload} captures the player's {@code ForgeCaps}, which is how most mods' per-player data rides
 * along for free. Cosmetic Armor is not one of them: its inventories live in that mod's own {@code InventoryManager},
 * keyed by UUID, and are never attached to the player as a capability. So they appear nowhere in the player's NBT,
 * the vault never saw them, and a player's cosmetics simply stayed on whichever server they set them on. Nothing was
 * broken; the data was never carried.
 *
 * <p>The client end needs no work. {@code deserializeNBT} runs the inventory's {@code onLoad}, which fires the change
 * listeners Cosmetic Armor itself registers to sync a player's cosmetics out, so writing on the server is enough for
 * the wearer and for everyone looking at them.
 *
 * <h2>Why reflection rather than a compileOnly dependency</h2>
 *
 * <p>Cosmetic Armor is NOT on this project's compile classpath, and the existing {@link CosArmorGraveCompat} avoids
 * the problem by touching only Forge events. Adding the mod to the build for three calls would mean committing
 * another binary to {@code libs/}, so this takes the reflection escape hatch that the optional-dependency pattern
 * sanctions for exactly this case (dungeons drives WorldEdit the same way).
 *
 * <p>It also buys the version guard the suite's standing rule asks for: a {@code ModList} check proves the mod is
 * present, not that this API still looks like this. Every lookup here is resolved once and cached, and any failure
 * leaves {@link #AVAILABLE} false so both entry points degrade to doing nothing rather than throwing on every hop.
 */
final class CosArmorVaultCompat
{
    private CosArmorVaultCompat()
    {
    }

    private static final Method GET_CA_STACKS;
    private static final Method SERIALIZE;
    private static final Method DESERIALIZE;
    private static final Method GET_SLOTS;
    private static final Method GET_STACK;
    private static final Method SET_STACK;
    private static final boolean AVAILABLE;

    static
    {
        Method get = null;
        Method write = null;
        Method read = null;
        Method slots = null;
        Method getStack = null;
        Method setStack = null;
        try
        {
            Class<?> api = Class.forName("lain.mods.cos.api.CosArmorAPI");
            get = api.getMethod("getCAStacks", UUID.class);
            Class<?> stacks = Class.forName("lain.mods.cos.api.inventory.CAStacksBase");
            // CAStacksBase carries two serializeNBT entries, the real one and the covariant bridge from
            // INBTSerializable. Either is fine: the bridge delegates and hands back the same object, and the
            // caller type-checks the result rather than trusting the declared return type.
            write = stacks.getMethod("serializeNBT");
            read = stacks.getMethod("deserializeNBT", CompoundTag.class);
            // ItemStackHandler's own accessors, used to re-announce each slot after a load. See apply().
            slots = stacks.getMethod("getSlots");
            getStack = stacks.getMethod("getStackInSlot", int.class);
            setStack = stacks.getMethod("setStackInSlot", int.class,
                    Class.forName("net.minecraft.world.item.ItemStack"));
        }
        catch (Throwable t)
        {
            get = null;
            write = null;
            read = null;
            slots = null;
            getStack = null;
            setStack = null;
        }
        GET_CA_STACKS = get;
        SERIALIZE = write;
        DESERIALIZE = read;
        GET_SLOTS = slots;
        GET_STACK = getStack;
        SET_STACK = setStack;
        AVAILABLE = get != null && write != null && read != null;
    }

    /** True when Cosmetic Armor is present AND its API still has the shape this class drives. */
    static boolean available()
    {
        return AVAILABLE;
    }

    /** This player's cosmetic inventory as NBT, or null when there is nothing worth carrying. */
    static CompoundTag capture(UUID playerId) throws Exception
    {
        if (!AVAILABLE)
        {
            return null;
        }
        Object stacks = GET_CA_STACKS.invoke(null, playerId);
        if (stacks == null)
        {
            return null;
        }
        Object tag = SERIALIZE.invoke(stacks);
        if (!(tag instanceof CompoundTag compound) || compound.isEmpty())
        {
            return null;
        }
        return compound;
    }

    /** Write a captured cosmetic inventory back onto this player. */
    static void apply(UUID playerId, CompoundTag tag) throws Exception
    {
        if (!AVAILABLE || tag == null || tag.isEmpty())
        {
            return;
        }
        Object stacks = GET_CA_STACKS.invoke(null, playerId);
        if (stacks == null)
        {
            return;
        }
        DESERIALIZE.invoke(stacks, tag);
        announce(stacks);
    }

    /**
     * This player's current cosmetic inventory as NBT, or null on any failure. The re-announce path sends this over
     * the suite's own channel and cannot afford a checked exception mid tick, so this swallows what {@link #capture}
     * throws.
     */
    static CompoundTag captureQuietly(UUID playerId)
    {
        try
        {
            return capture(playerId);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /** Re-announce an already-loaded inventory, for a client that may have missed the first attempt. */
    static void reannounce(UUID playerId)
    {
        if (!AVAILABLE)
            return;
        try
        {
            Object stacks = GET_CA_STACKS.invoke(null, playerId);
            if (stacks != null)
                announce(stacks);
        }
        catch (Throwable ignored)
        {
            // best effort
        }
    }

    /**
     * Re-set every slot to what it already holds, so Cosmetic Armor tells the clients about it.
     *
     * <p>WHY this is needed even though the load worked. The inventory on the server is correct after
     * {@code deserializeNBT}: it reads back with the carried items and still has them a quarter of a minute later.
     * What does not happen is the client ever hearing about it. Cosmetic Armor pushes a player's own cosmetics out
     * through {@code onInventoryChanged}, one packet per slot, and its login handler deliberately skips the
     * arriving player when it syncs ({@code if (other == player) continue;}), so an arrival is entirely dependent
     * on a change notification firing. Whatever {@code onLoad} does on this path, that notification is not reaching
     * the client, which is exactly the symptom: right on the server, absent in the inventory screen.
     *
     * <p>{@code setStackInSlot} is the path a live edit takes and always calls {@code onContentsChanged(slot)}, so
     * writing each slot's existing contents back re-announces the whole inventory without altering it. Best effort:
     * a failure here leaves the server-side inventory correct, which is still better than before.
     */
    private static void announce(Object stacks)
    {
        if (GET_SLOTS == null || GET_STACK == null || SET_STACK == null)
        {
            return;
        }
        try
        {
            int count = (int) GET_SLOTS.invoke(stacks);
            for (int slot = 0; slot < count; slot++)
            {
                SET_STACK.invoke(stacks, slot, GET_STACK.invoke(stacks, slot));
            }
        }
        catch (Throwable ignored)
        {
            // the inventory is already loaded; only the re-announce failed
        }
    }
}
