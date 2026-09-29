package net.shurui.shuruisutilities.senzu.bag;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.compat.curios.SenzuBagCurios;
import net.shurui.shuruisutilities.senzu.SenzuRegistry;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client -> server, empty payload: the "pull one bean out of my bean bag" keybind. This is the mid-fight action:
 * one keypress and a single bean lands in the player's inventory, ready to right-click. The server is the sole
 * authority; the client never says which bean or how many, it only asks to pull one.
 *
 * <p>WHY pull a bean OUT rather than consume it directly: the bag can legitimately hold beans with wildly different
 * effects (a full-heal senzu, but also a POISON bean and the energy/stamina-DRAIN death beans). Auto-consuming a bean
 * on a keypress could just as easily poison or drain the player as heal them, so instead we hand the player a bean and
 * let the existing single-click consume path (BeanItem#use) do the healing, where the player can see what they got.
 * That also means we reuse the one and only "what using a bean does" definition and never duplicate stat logic here.
 *
 * <p>The shared bean cooldown is respected: if the player is currently on the shared bean cooldown (i.e. they just ate
 * a bean) the pull does nothing and messages them, so this keybind cannot be spammed to dump the bag into the
 * inventory during the very window where beans cannot be used anyway.
 *
 * <p>NO BEAN LOSS: the pulled bean is added to the player's inventory FIRST, and only once that add is confirmed
 * (the added copy came back empty) is one bean removed from the bag. The bean therefore always exists in the
 * destination before it is removed from the source; if the inventory is full the add fails and the bag is left
 * completely untouched.
 */
public class PacketTakeSenzuBean implements ISUPacket
{
    // translation keys (defined in en_us / es_es).
    private static final String NO_BAG_KEY = "message.dmz_ragnarok.core.senzu_bag_none";
    private static final String EMPTY_KEY = "message.dmz_ragnarok.core.senzu_bag_empty";
    private static final String COOLDOWN_KEY = "message.dmz_ragnarok.core.senzu_bag_cooldown";
    private static final String FULL_KEY = "message.dmz_ragnarok.core.senzu_bag_full";
    private static final String TOOK_KEY = "message.dmz_ragnarok.core.senzu_bag_took";

    public PacketTakeSenzuBean()
    {
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        // no payload: the packet is the "pull one bean" signal
    }

    public static PacketTakeSenzuBean decode(FriendlyByteBuf buf)
    {
        return new PacketTakeSenzuBean();
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
        {
            return;
        }

        ItemStack bag = SenzuBagCurios.findEquipped(player);
        if (bag.isEmpty())
        {
            player.sendSystemMessage(Component.translatable(NO_BAG_KEY));
            return;
        }

        // respect the shared bean cooldown: while it is running, beans cannot be used, so pulling one would just
        // clutter the inventory and could be spammed to empty the bag. Refuse with feedback instead.
        if (SenzuRegistry.isOnBeanCooldown(player))
        {
            player.displayClientMessage(Component.translatable(COOLDOWN_KEY), true);
            return;
        }

        ItemStackHandler handler = SenzuBagInventory.read(bag);

        // leftmost occupied slot wins, so a player who arranges the bag with their preferred emergency bean in the
        // first slot gets that bean every time. A deterministic, predictable pick beats a "smart" one here.
        int slot = firstOccupiedSlot(handler);
        if (slot < 0)
        {
            player.displayClientMessage(Component.translatable(EMPTY_KEY), true);
            return;
        }

        ItemStack inSlot = handler.getStackInSlot(slot);
        Component takenName = inSlot.getHoverName(); // captured before any mutation, for the feedback line

        // NO BEAN LOSS step 1: put one bean into the inventory FIRST. add() mutates the copy, emptying it if it was
        // fully accepted. We never touch the bag until this succeeds.
        ItemStack one = inSlot.copy();
        one.setCount(1);
        player.getInventory().add(one);
        if (!one.isEmpty())
        {
            // inventory full: nothing was added, so leave the bag exactly as it is and tell the player.
            player.displayClientMessage(Component.translatable(FULL_KEY), true);
            return;
        }

        // NO BEAN LOSS step 2: the bean now exists in the inventory, so it is safe to remove exactly one from the bag.
        handler.extractItem(slot, 1, false);
        SenzuBagInventory.write(bag, handler);
        SenzuBagCurios.persist(player, bag);

        player.displayClientMessage(Component.translatable(TOOK_KEY, takenName), true);
    }

    // index of the first non-empty bag slot, or -1 when the bag holds no beans.
    private static int firstOccupiedSlot(ItemStackHandler handler)
    {
        for (int i = 0; i < handler.getSlots(); ++i)
        {
            if (!handler.getStackInSlot(i).isEmpty())
            {
                return i;
            }
        }
        return -1;
    }

    public static void handler(final PacketTakeSenzuBean message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
