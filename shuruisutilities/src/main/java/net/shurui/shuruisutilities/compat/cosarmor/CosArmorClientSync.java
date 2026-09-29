package net.shurui.shuruisutilities.compat.cosarmor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * CLIENT-ONLY. Writes a carried Cosmetic Armor Reworked inventory straight into that mod's client cache, mirroring
 * exactly what {@code PacketSyncCosArmor.handlePacketClient} does per slot:
 *
 * <pre>
 *     ModObjects.invMan.getCosArmorInventoryClient(uuid).setStackInSlot(slot, stack);
 *     ModObjects.invMan.getCosArmorInventoryClient(uuid).setSkinArmor(slot, isSkinArmor);
 * </pre>
 *
 * <p>Cosmetic Armor is an OPTIONAL dependency and is NOT on the compile classpath, so every hop through it is
 * reflection, following {@link CosArmorVaultCompat}: methods resolved once and cached, an {@link #AVAILABLE} flag,
 * and every path degrading to a no-op rather than throwing. This class is only ever classloaded on the client,
 * from inside the {@code Dist.CLIENT} guard in {@link PacketCosArmorSync#handle}, so a dedicated server never
 * touches it.
 *
 * <p>The carried tag is Cosmetic Armor's own {@code CAStacksBase.serializeNBT} output: a top-level {@code Size}
 * int and an {@code Items} list whose entries are each an {@link ItemStack} NBT plus a {@code Slot} int and an
 * {@code isSkinArmor} boolean. We set the size first because {@code setStackInSlot} throws for a slot past the
 * handler's length, and a freshly cache-loaded client inventory may be shorter than the carried one.
 */
final class CosArmorClientSync
{
    private CosArmorClientSync()
    {
    }

    private static final Field INV_MAN;
    private static final Method GET_INV_CLIENT;
    private static final Method SET_STACK;
    private static final Method SET_SKIN;
    private static final Method SET_SIZE;
    private static final boolean AVAILABLE;

    static
    {
        Field invMan = null;
        Method getInv = null;
        Method setStack = null;
        Method setSkin = null;
        Method setSize = null;
        // Resolve nothing unless the mod is actually present: Class.forName on an absent class would throw here,
        // and the whole point is to degrade to a silent no-op.
        if (ModList.get().isLoaded("cosmeticarmorreworked"))
        {
            try
            {
                Class<?> modObjects = Class.forName("lain.mods.cos.impl.ModObjects");
                invMan = modObjects.getField("invMan");
                // getCosArmorInventoryClient is declared on InventoryManager (the invMan field's type), not on the
                // InventoryManagerClient subclass, so resolve it there.
                Class<?> manager = Class.forName("lain.mods.cos.impl.InventoryManager");
                getInv = manager.getMethod("getCosArmorInventoryClient", UUID.class);
                Class<?> inventory = Class.forName("lain.mods.cos.impl.inventory.InventoryCosArmor");
                // setStackInSlot is inherited from Forge's ItemStackHandler; setSkinArmor and setSize from
                // CAStacksBase. getMethod walks the hierarchy, so resolving all three off InventoryCosArmor is fine.
                setStack = inventory.getMethod("setStackInSlot", int.class, ItemStack.class);
                setSkin = inventory.getMethod("setSkinArmor", int.class, boolean.class);
                setSize = inventory.getMethod("setSize", int.class);
            }
            catch (Throwable t)
            {
                invMan = null;
                getInv = null;
                setStack = null;
                setSkin = null;
                setSize = null;
            }
        }
        INV_MAN = invMan;
        GET_INV_CLIENT = getInv;
        SET_STACK = setStack;
        SET_SKIN = setSkin;
        SET_SIZE = setSize;
        AVAILABLE = invMan != null && getInv != null && setStack != null && setSkin != null && setSize != null;
    }

    /** Write the carried inventory into Cosmetic Armor's client cache for this player. Never throws. */
    static void apply(UUID target, CompoundTag tag)
    {
        if (!AVAILABLE || target == null || tag == null || tag.isEmpty())
            return;
        try
        {
            Object manager = INV_MAN.get(null);
            if (manager == null)
                return;
            Object inv = GET_INV_CLIENT.invoke(manager, target);
            if (inv == null)
                return;

            // Size first: a slot index past the handler's length would make setStackInSlot throw. See class note.
            if (tag.contains("Size"))
            {
                int size = tag.getInt("Size");
                if (size > 0)
                    SET_SIZE.invoke(inv, size);
            }

            int applied = 0;
            ListTag items = tag.getList("Items", Tag.TAG_COMPOUND);
            for (int i = 0; i < items.size(); i++)
            {
                CompoundTag entry = items.getCompound(i);
                int slot = entry.getInt("Slot");
                boolean isSkinArmor = entry.getBoolean("isSkinArmor");
                ItemStack stack = ItemStack.of(entry);
                SET_STACK.invoke(inv, slot, stack);
                SET_SKIN.invoke(inv, slot, isSkinArmor);
                applied++;
            }
            // One diagnosable line so the next backend-hop test can be read from the client log alone.
            LoggingHandler.sulog.info("[shard] Cosmetic armor received over SU channel for {}: {} item(s) applied",
                    target, applied);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[shard] Could not apply cosmetic armor received over SU channel for {}: {}",
                    target, t.toString());
        }
    }
}
