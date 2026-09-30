package net.shurui.shuruisutilities.client.radial;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import com.dragonminez.client.gui.radial.AbstractRadialNode;
import com.dragonminez.common.stats.StatsData;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.compat.curios.SenzuBagCurios;
import net.shurui.shuruisutilities.senzu.bag.PacketTakeSenzuBean;
import net.shurui.shuruisutilities.senzu.bag.SenzuBagInventory;

/**
 * The senzu bean bag, as an entry in DragonMineZ's radial (utility) menu, so the emergency bean is one wheel-flick
 * away in a fight instead of behind a hotbar swap. It lives inside DMZ's own "Actions" sub-ring (see
 * {@code MixinDmzActionsNode}) because that ring is where the game already gathers a player's on-demand abilities
 * (racial skill, fusion, release, ki weapon, ki actions); pulling a bean is one more such action, and the base ring
 * is a FIXED eight slots that must never grow, so a new base slot was never an option.
 *
 * <h2>Two clicks, one node</h2>
 * <ul>
 *   <li><b>Right click</b> cycles through the bean TYPES the bag currently holds, purely on the client: it moves the
 *       selection to the next distinct bean id present and updates the icon and count.</li>
 *   <li><b>Left click</b> asks the server to pull one bean of the selected type into the inventory
 *       ({@link PacketTakeSenzuBean} carrying that id). The server re-validates the type is present, the shared bean
 *       cooldown and the inventory space, so the wheel is only ever a request.</li>
 * </ul>
 * DMZ's radial screen does not pass the mouse button to a node, so the pressed button is read straight from GLFW
 * inside {@code onSelect}: the press callback fires while the button is still physically down, so the right button
 * reads as pressed for a right click and not for a left one.
 *
 * <h2>What it shows, and when it hides</h2>
 * The face shows the selected bean type's own item texture; the hover label names that bean and how many of it the
 * bag holds. The node is HIDDEN entirely when the player has no bag equipped (an absent feature, not a dead entry),
 * and is shown but GREYED (non-interactive) when the bag is equipped but empty, so a player who owns a bag can see
 * where the entry lives even before they fill it.
 *
 * <p>The bag contents are read from the player's own Curios-synced bag stack on the client; the selection is client
 * state only. Nothing here punishes a disconnect: a click is a plain request the server may decline.
 */
public final class SenzuRadialNode extends AbstractRadialNode
{
    // the item texture folder every bean and the bag itself live under, so an id path maps straight to its icon.
    private static final String ITEM_TEX = "textures/item/";
    private static final ResourceLocation BAG_ICON = new ResourceLocation("dmz_ragnarok", ITEM_TEX + "senzubag.png");

    // label keys (defined in en_us / es_es).
    private static final String EMPTY_LABEL = "gui.dmz_ragnarok.core.senzu_radial_empty";
    private static final String ENTRY_LABEL = "gui.dmz_ragnarok.core.senzu_radial_entry";

    // The cycled-to bean id, remembered across menu opens within a session so the selection is not lost every time the
    // wheel is reopened. Static because DMZ rebuilds the node on each menu open; the id is re-resolved against the live
    // bag each time, so a stale id simply falls back to the first present type.
    private static String selectedId = "";

    @Override
    public Component label(StatsData stats)
    {
        List<Bean> beans = beans();
        if (beans.isEmpty())
        {
            return Component.translatable(EMPTY_LABEL);
        }
        Bean sel = selected(beans);
        return Component.translatable(ENTRY_LABEL, sel.stack.getHoverName(), sel.count);
    }

    @Override
    public ResourceLocation icon(StatsData stats)
    {
        List<Bean> beans = beans();
        if (beans.isEmpty())
        {
            return BAG_ICON;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(selected(beans).stack.getItem());
        // id.getPath() is the bean's registry path (e.g. bean_hp); its item texture shares that name.
        return id == null ? BAG_ICON : new ResourceLocation(id.getNamespace(), ITEM_TEX + id.getPath() + ".png");
    }

    /** Hidden when no bag is equipped: an absent feature is hidden, never a dead entry on the wheel. */
    @Override
    public boolean visible(StatsData stats)
    {
        return !bag().isEmpty();
    }

    /** Greyed (non-interactive) when the bag is equipped but empty, so it cannot be clicked with nothing to pull. */
    @Override
    public boolean interactive(StatsData stats)
    {
        return !beans().isEmpty();
    }

    @Override
    public void onSelect(StatsData stats)
    {
        // DMZ's radial screen does not pass the mouse button to a node, so branch on the live GLFW button here: a
        // right click cycles the selected type, a left click pulls one bean. Both behaviours live in their own methods
        // so a caller that already knows the intended button (a test harness that cannot press a physical button) can
        // drive the exact same code path directly.
        if (isRightClick())
        {
            cycleSelection();
        }
        else
        {
            requestPull();
        }
    }

    /**
     * The right-click behaviour: move the selection to the next distinct bean type present in the bag. Pure client
     * state; does nothing when the bag holds no beans.
     */
    public void cycleSelection()
    {
        List<Bean> beans = beans();
        if (beans.isEmpty())
        {
            return;
        }
        int at = indexOfSelected(beans);
        selectedId = registryId(beans.get((at + 1) % beans.size()).stack.getItem());
        playClick();
    }

    /**
     * The left-click behaviour: ask the server to pull one bean of the selected type into the inventory. The server is
     * the sole authority; this only sends the request. Does nothing when the bag holds no beans.
     */
    public void requestPull()
    {
        List<Bean> beans = beans();
        if (beans.isEmpty())
        {
            return;
        }
        String id = registryId(selected(beans).stack.getItem());
        if (!id.isEmpty())
        {
            NetworkUtils.INSTANCE.sendToServer(new PacketTakeSenzuBean(id));
            playClick();
        }
    }

    /** The registry id of the currently-selected bean type (resolving the default first type), or "" when empty. */
    public static String selectedBeanId()
    {
        List<Bean> beans = beans();
        return beans.isEmpty() ? "" : registryId(selected(beans).stack.getItem());
    }

    /** The total count of the currently-selected bean type across the bag, or 0 when empty. */
    public static int selectedCount()
    {
        List<Bean> beans = beans();
        return beans.isEmpty() ? 0 : selected(beans).count;
    }

    /** The number of distinct bean types the client currently sees in the equipped bag. */
    public static int distinctTypes()
    {
        return beans().size();
    }

    // the local player's own equipped bag (Curios syncs it to the wearer's client), or EMPTY when none / Curios absent.
    private static ItemStack bag()
    {
        Player player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : SenzuBagCurios.findEquipped(player);
    }

    // distinct bean types in the bag, in first-seen slot order, each with the total count of that type across all slots.
    private static List<Bean> beans()
    {
        ItemStack bag = bag();
        if (bag.isEmpty())
        {
            return List.of();
        }
        ItemStackHandler handler = SenzuBagInventory.read(bag);
        Map<Item, Bean> byItem = new LinkedHashMap<>();
        for (int i = 0; i < handler.getSlots(); ++i)
        {
            ItemStack inSlot = handler.getStackInSlot(i);
            if (inSlot.isEmpty())
            {
                continue;
            }
            Bean bean = byItem.get(inSlot.getItem());
            if (bean == null)
            {
                byItem.put(inSlot.getItem(), new Bean(inSlot, inSlot.getCount()));
            }
            else
            {
                bean.count += inSlot.getCount();
            }
        }
        return new ArrayList<>(byItem.values());
    }

    // the currently-selected bean, defaulting to the first present type when the remembered id is gone or unset.
    private static Bean selected(List<Bean> beans)
    {
        return beans.get(indexOfSelected(beans));
    }

    // index of the remembered selection within the present list, or 0 when it is no longer present.
    private static int indexOfSelected(List<Bean> beans)
    {
        for (int i = 0; i < beans.size(); ++i)
        {
            if (registryId(beans.get(i).stack.getItem()).equals(selectedId))
            {
                return i;
            }
        }
        return 0;
    }

    private static String registryId(Item item)
    {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        return id == null ? "" : id.toString();
    }

    // DMZ's radial screen does not tell a node which button was clicked, so read it from GLFW: onSelect runs from the
    // press callback while the button is still down, so the right button reads pressed for a right click only.
    private static boolean isRightClick()
    {
        try
        {
            long window = Minecraft.getInstance().getWindow().getWindow();
            return GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        }
        catch (Throwable t)
        {
            // if the window state cannot be read for any reason, fall back to the pull action (the safe default).
            return false;
        }
    }

    // one distinct bean type present in the bag: a representative stack (for name and item) and the total count.
    private static final class Bean
    {
        private final ItemStack stack;
        private int count;

        private Bean(ItemStack stack, int count)
        {
            this.stack = stack;
            this.count = count;
        }
    }
}
