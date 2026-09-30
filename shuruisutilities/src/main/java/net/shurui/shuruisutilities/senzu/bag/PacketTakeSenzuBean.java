package net.shurui.shuruisutilities.senzu.bag;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.compat.curios.SenzuBagCurios;
import net.shurui.shuruisutilities.senzu.SenzuRegistry;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Client -> server: the "pull one bean out of my bean bag" action. This is the mid-fight action: one keypress (or a
 * left click on the radial senzu node) and a single bean lands in the player's inventory, ready to use. The server is
 * the sole authority.
 *
 * <p>PAYLOAD (1.5.1): a single bean registry id. Two callers, one packet:
 * <ul>
 *   <li>The bare "pull a bean" keybind sends an EMPTY id: the server pulls the leftmost occupied slot, exactly as
 *       before, so a player who arranges their preferred emergency bean first always gets it.</li>
 *   <li>The radial senzu node sends the id of the bean TYPE the player has cycled to, so the server pulls exactly
 *       that type. The id is only a REQUEST: the server pulls it only if the bag actually holds it, so a doctored
 *       client can never conjure a bean the bag does not contain.</li>
 * </ul>
 * The cycle/selection state lives on the client; the server validates the type is present, the cooldown and the
 * inventory space every time. The packet id (57 on the SU channel) is unchanged; only the payload grew.
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

    // the requested bean registry id, or "" for "pull the leftmost occupied slot" (the bare keybind). The client only
    // ever names a type; it never says how many or from which slot, and the server ignores it unless the bag holds it.
    private final String beanId;

    /** The bare keybind form: no type named, so the server pulls the leftmost occupied slot. */
    public PacketTakeSenzuBean()
    {
        this("");
    }

    /** The radial form: pull one bean of exactly this registry id (e.g. {@code dmz_ragnarok:bean_hp}) if present. */
    public PacketTakeSenzuBean(String beanId)
    {
        this.beanId = beanId == null ? "" : beanId;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(beanId);
    }

    public static PacketTakeSenzuBean decode(FriendlyByteBuf buf)
    {
        return new PacketTakeSenzuBean(buf.readUtf());
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

        // Pick the slot to pull from. With a type requested (the radial), take the leftmost slot holding EXACTLY that
        // registry id, so the player gets the bean they cycled to. With no type (the bare keybind), take the leftmost
        // occupied slot. Either way the pick is deterministic and server-decided; the client's id is only a request.
        int slot = beanId.isEmpty() ? firstOccupiedSlot(handler) : firstSlotOfType(handler, beanId);
        if (slot < 0)
        {
            // no bean of the requested type (or an empty bag): nothing to pull, so leave the bag untouched and say so.
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

    // index of the first slot holding a bean whose registry id equals the requested one, or -1 when the bag holds
    // none of that type. A bad or unknown id simply resolves to no item and therefore matches nothing, so a doctored
    // client can never pull anything the bag does not actually contain.
    private static int firstSlotOfType(ItemStackHandler handler, String id)
    {
        Item wanted = null;
        try
        {
            wanted = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        }
        catch (Throwable ignored)
        {
            // malformed id: leave wanted null so nothing matches.
        }
        if (wanted == null)
        {
            return -1;
        }
        for (int i = 0; i < handler.getSlots(); ++i)
        {
            ItemStack inSlot = handler.getStackInSlot(i);
            if (!inSlot.isEmpty() && inSlot.getItem() == wanted)
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
