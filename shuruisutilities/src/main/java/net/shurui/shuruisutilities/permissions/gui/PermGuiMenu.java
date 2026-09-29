package net.shurui.shuruisutilities.permissions.gui;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.permissions.SUPermissions;
import net.shurui.shuruisutilities.api.permissions.Zone;
import net.shurui.shuruisutilities.api.key.PermissionHooks;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.shurui.shuruisutilities.client.gui.SUMenus;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// chest-style permissions editor (GENERIC_9x6, so it renders on any client, no client mod). three screens:
// group list, a group's attributes (prefix/suffix/priority + edit-permissions), and the paginated node list
// where each node is a coloured pane you click to cycle unset(white) -> allow(green) -> deny(red). text edits
// drop to chat input through PermissionHooks (the key's PermGuiManager). The menu type is a registry entry, so
// it stays in core; only the key's editor ever opens it on a server.
public class PermGuiMenu extends AbstractContainerMenu
{
    public enum Screen
    {
        GROUP_LIST, GROUP_EDIT, PERM_LIST
    }

    private static final int PAGE_SIZE = 45; // top 5 rows; bottom row = navigation

    public Screen screen;
    public String group;
    public int page;

    private final SimpleContainer container = new SimpleContainer(54);
    private final List<String> pageEntries = new ArrayList<>(); // slot->group name / perm node for the current page

    public PermGuiMenu(int id, Inventory playerInv, Screen screen, String group, int page)
    {
        this(id, playerInv, screen, group, page, true);
    }

    /**
     * Client-side constructor used by the custom menu type when the screen opens. Builds the slot layout
     * but does not touch server-only permission state; the pane contents sync from the server.
     */
    public PermGuiMenu(int id, Inventory playerInv)
    {
        this(id, playerInv, Screen.GROUP_LIST, "", 0, false);
    }

    private PermGuiMenu(int id, Inventory playerInv, Screen screen, String group, int page, boolean server)
    {
        super(SUMenus.PERM.get(), id);
        this.screen = screen;
        this.group = group;
        this.page = page;

        Container c = container;
        for (int row = 0; row < 6; ++row)
            for (int col = 0; col < 9; ++col)
                addSlot(new LockedSlot(c, col + row * 9, 8 + col * 18, 18 + row * 18));
        for (int row = 0; row < 3; ++row)
            for (int col = 0; col < 9; ++col)
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
        for (int col = 0; col < 9; ++col)
            addSlot(new Slot(playerInv, col, 8 + col * 18, 198));

        if (server)
            rebuild();
    }

    public void rebuild()
    {
        for (int i = 0; i < 54; ++i)
            container.setItem(i, ItemStack.EMPTY);
        pageEntries.clear();
        switch (screen)
        {
        case GROUP_LIST:
            buildGroupList();
            break;
        case GROUP_EDIT:
            buildGroupEdit();
            break;
        case PERM_LIST:
            buildPermList();
            break;
        }
        broadcastChanges();
    }

    private void buildGroupList()
    {
        List<String> groups = new ArrayList<>(APIRegistry.perms.getServerZone().getGroups());
        groups.sort(String::compareToIgnoreCase);
        paginate(groups);
        for (int i = 0; i < pageEntries.size(); ++i)
            container.setItem(i, named(Items.NAME_TAG, ChatFormatting.YELLOW + pageEntries.get(i)));
        navRow(groups.size());
        container.setItem(49, named(Items.EMERALD, ChatFormatting.GREEN + "Create new group"));
    }

    private void buildGroupEdit()
    {
        container.setItem(4, named(Items.PLAYER_HEAD, ChatFormatting.AQUA + "Group: " + group));
        container.setItem(19, named(Items.NAME_TAG, "Prefix: " + prop(SUPermissions.PREFIX)));
        container.setItem(21, named(Items.NAME_TAG, "Suffix: " + prop(SUPermissions.SUFFIX)));
        container.setItem(23, named(Items.COMPARATOR, "Priority: " + prop(SUPermissions.GROUP_PRIORITY)));
        container.setItem(25, named(Items.WRITABLE_BOOK, ChatFormatting.GOLD + "Parents: " + prop(SUPermissions.GROUP_PARENTS)));
        container.setItem(40, named(Items.COMMAND_BLOCK, ChatFormatting.LIGHT_PURPLE + "Edit permissions →"));
        container.setItem(45, named(Items.ARROW, "Back to groups"));
    }

    private void buildPermList()
    {
        List<String> nodes = new ArrayList<>(PermissionHooks.get().registeredNodes());
        nodes.sort(String::compareToIgnoreCase);
        paginate(nodes);
        for (int i = 0; i < pageEntries.size(); ++i)
        {
            String node = pageEntries.get(i);
            String val = APIRegistry.perms.getServerZone().getGroupPermissions(group).get(node);
            Item icon;
            String state;
            if (Zone.PERMISSION_TRUE.equals(val))
            {
                icon = Items.LIME_STAINED_GLASS_PANE;
                state = ChatFormatting.GREEN + "ALLOW";
            }
            else if (Zone.PERMISSION_FALSE.equals(val))
            {
                icon = Items.RED_STAINED_GLASS_PANE;
                state = ChatFormatting.RED + "DENY";
            }
            else
            {
                icon = Items.WHITE_STAINED_GLASS_PANE;
                state = ChatFormatting.GRAY + "unset";
            }
            container.setItem(i, named(icon, ChatFormatting.WHITE + node + ChatFormatting.DARK_GRAY + "  [" + state + ChatFormatting.DARK_GRAY + "]"));
        }
        navRow(nodes.size());
        container.setItem(49, named(Items.ARROW, "Back to group: " + group));
    }

    private void navRow(int total)
    {
        if (page > 0)
            container.setItem(45, named(Items.SPECTRAL_ARROW, "Previous page"));
        int maxPage = (total - 1) / PAGE_SIZE;
        if (page < maxPage)
            container.setItem(53, named(Items.SPECTRAL_ARROW, "Next page"));
        if (total > PAGE_SIZE)
            container.setItem(47, named(Items.PAPER, "Page " + (page + 1) + "/" + (maxPage + 1)));
    }

    private void paginate(List<String> all)
    {
        int from = page * PAGE_SIZE;
        for (int i = from; i < Math.min(from + PAGE_SIZE, all.size()); ++i)
            pageEntries.add(all.get(i));
    }

    private String prop(String node)
    {
        String v = APIRegistry.perms.getGroupPermissionProperty(group, node);
        return v == null || v.isEmpty() ? ChatFormatting.DARK_GRAY + "(none)" : ChatFormatting.WHITE + v;
    }

    @Override
    public void clicked(int slotId, int dragType, ClickType clickType, Player player)
    {
        if (slotId < 0 || slotId >= 54 || !(player instanceof net.minecraft.server.level.ServerPlayer sp))
            return; // ignore player-inventory clicks + non-container area

        switch (screen)
        {
        case GROUP_LIST:
            if (slotId == 49)
            {
                PermissionHooks.get().requestGuiInput(sp, "CREATE_GROUP", null);
                return;
            }
            if (handleNav(slotId))
                return;
            if (slotId < pageEntries.size())
            {
                group = pageEntries.get(slotId);
                screen = Screen.GROUP_EDIT;
                rebuild();
            }
            return;

        case GROUP_EDIT:
            switch (slotId)
            {
            case 19 -> PermissionHooks.get().requestGuiInput(sp, "PREFIX", group);
            case 21 -> PermissionHooks.get().requestGuiInput(sp, "SUFFIX", group);
            case 23 -> PermissionHooks.get().requestGuiInput(sp, "PRIORITY", group);
            case 25 -> PermissionHooks.get().requestGuiInput(sp, "PARENTS", group);
            case 40 -> { screen = Screen.PERM_LIST; page = 0; rebuild(); }
            case 45 -> { screen = Screen.GROUP_LIST; page = 0; rebuild(); }
            }
            return;

        case PERM_LIST:
            if (slotId == 49)
            {
                screen = Screen.GROUP_EDIT;
                page = 0;
                rebuild();
                return;
            }
            if (handleNav(slotId))
                return;
            if (slotId < pageEntries.size())
            {
                cyclePermission(pageEntries.get(slotId));
                rebuild();
            }
            return;
        }
    }

    private boolean handleNav(int slotId)
    {
        if (slotId == 45 && page > 0)
        {
            page--;
            rebuild();
            return true;
        }
        if (slotId == 53)
        {
            page++;
            rebuild();
            return true;
        }
        return false;
    }

    /** unset -> allow -> deny -> unset */
    private void cyclePermission(String node)
    {
        String val = APIRegistry.perms.getServerZone().getGroupPermissions(group).get(node);
        if (Zone.PERMISSION_TRUE.equals(val))
            APIRegistry.perms.setGroupPermission(group, node, false);
        else if (Zone.PERMISSION_FALSE.equals(val))
            APIRegistry.perms.getServerZone().clearGroupPermission(group, node);
        else
            APIRegistry.perms.setGroupPermission(group, node, true);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index)
    {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player)
    {
        return true;
    }

    private static ItemStack named(Item item, String name)
    {
        ItemStack stack = new ItemStack(item);
        stack.setHoverName(Component.literal(name));
        return stack;
    }

    /** The whole board is display-only; item interactions are handled in {@link #clicked}. */
    private static class LockedSlot extends Slot
    {
        LockedSlot(Container c, int index, int x, int y)
        {
            super(c, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack)
        {
            return false;
        }

        @Override
        public boolean mayPickup(Player player)
        {
            return false;
        }
    }
}
