package net.shurui.shuruisutilities.commands.util;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.api.permissions.GroupEntry;
import net.shurui.shuruisutilities.audit.AuditLog;
import net.shurui.shuruisutilities.core.misc.Translator;
import net.shurui.shuruisutilities.util.PlayerInfo;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class Kit
{

    private static final long MILLISECONDS_PER_YEAR = 365L * 24L * 60L * 60L * 1000L;

    private String name;

    private int cooldown;

    private ItemStack[] items;

    private ItemStack[] armor;

    // groups allowed to claim this kit; null/empty = everyone (old kits deserialize this as null)
    private List<String> allowedGroups;

    // claimable exactly once per player, ever, independent of cooldown and not bypassed by ops (the per-kit
    // su.command.kit.<name> node is the ops gate, matching the group-lock). old kits deserialize this false.
    private boolean oneTime;

    public Kit(Player player, String name, int cooldown)
    {
        this.cooldown = cooldown;
        this.name = name;

        List<ItemStack> collapsedInventory = new ArrayList<>();
        for (int i = 0; i < player.getInventory().items.size(); i++)
            if (player.getInventory().items.get(i) != ItemStack.EMPTY)
            {
                collapsedInventory.add(player.getInventory().items.get(i).copy());
            }
        items = collapsedInventory.toArray(new ItemStack[collapsedInventory.size()]);

        armor = new ItemStack[player.getInventory().armor.size()];
        for (int i = 0; i < 4; i++)
            if (player.getInventory().armor.get(i) != ItemStack.EMPTY)
                armor[i] = player.getInventory().armor.get(i).copy();
    }

    public String getName()
    {
        return name;
    }

    public int getCooldown()
    {
        return cooldown;
    }

    public ItemStack[] getItems()
    {
        return items;
    }

    public ItemStack[] getArmor()
    {
        return armor;
    }

    // never null (empty = everyone)
    public List<String> getAllowedGroups()
    {
        return allowedGroups == null ? new ArrayList<>() : new ArrayList<>(allowedGroups);
    }

    // null/empty list stores null so the kit stays "everyone"
    public void setAllowedGroups(List<String> groups)
    {
        if (groups == null || groups.isEmpty())
        {
            allowedGroups = null;
            return;
        }
        List<String> cleaned = new ArrayList<>();
        for (String g : groups)
            if (g != null && !g.trim().isEmpty())
                cleaned.add(g.trim());
        allowedGroups = cleaned.isEmpty() ? null : cleaned;
    }

    public boolean isOneTime()
    {
        return oneTime;
    }

    public void setOneTime(boolean oneTime)
    {
        this.oneTime = oneTime;
    }

    // no group restriction = open to everyone; otherwise player must be in one of the listed groups
    // (case-insensitive). server-side.
    public boolean isAllowedFor(Player player)
    {
        if (allowedGroups == null || allowedGroups.isEmpty())
            return true;
        try
        {
            for (GroupEntry entry : APIRegistry.perms.getPlayerGroups(UserIdent.get(player)))
                for (String allowed : allowedGroups)
                    if (allowed.equalsIgnoreCase(entry.getGroup()))
                        return true;
        }
        catch (Exception ignored)
        {
        }
        return false;
    }

    public void giveKit(Player player)
    {
        PlayerInfo pi = PlayerInfo.get(player.getGameProfile().getId());

        // one-time gate: checked before cooldown, NOT bypassed by ops or the cooldown-bypass node (per-kit
        // node is the only ops gate), so a spent one-time kit always denies here.
        if (oneTime && pi.hasClaimedOneTimeKit(name))
        {
            ChatOutputHandler.chatError(player.createCommandSourceStack(),
                    Translator.format("&cYou have already claimed the %s kit. It can only be claimed once.", name));
            return;
        }

        if (!APIRegistry.perms.checkPermission(player, KitRegistry.PERM_BYPASS_COOLDOWN))
        {
            long timeout = pi.getRemainingTimeout("KIT_" + name);
            if (timeout > 0)
            {
                ChatOutputHandler.chatWarning(player.createCommandSourceStack(),
                        "Kit cooldown active, %s to go!", net.shurui.shuruisutilities.util.StringUtil.formatDuration(timeout / 1000L));
                return;
            }
            pi.startTimeout("KIT_" + name, cooldown < 0 ? 10L * MILLISECONDS_PER_YEAR : cooldown * 1000L);
        }

        // past both gates: record the permanent claim marker now (persisted atomically) so it holds even for
        // ops, who skip the cooldown branch above.
        if (oneTime)
            pi.markOneTimeKitClaimed(name);

        boolean droppedSomeItems = false;

        // add() mutates the stack down to whatever did not fit and returns true as soon as ONE item went in, so the
        // overflow of a PARTIAL merge used to be deleted here without even setting the flag. Read the remainder and
        // put it at the player's feet instead. Same defect as the voided dungeon gems (ticket 800).
        for (ItemStack stack : items)
            droppedSomeItems |= giveOrDrop(player, stack.copy());

        for (int i = 0; i < 4; i++)
            if (armor[i] != null)
                if (player.getInventory().armor.get(i) == ItemStack.EMPTY)
                {
                    player.getInventory().armor.set(i, armor[i].copy());
                }
                else
                    droppedSomeItems |= giveOrDrop(player, armor[i].copy());

        if (droppedSomeItems)
            ChatOutputHandler.chatError(player.createCommandSourceStack(),
                    "Your inventory was full: some kit items are at your feet.");
        // Kit claims were logged nowhere, which is why "who claimed super" could not be answered from the server log.
        // Written only here, past both gates and after the items actually went out, so the line marks a real give.
        AuditLog.log("{} claimed the {} kit", player.getName().getString(), name);
        ChatOutputHandler.chatConfirmation(player.createCommandSourceStack(), "Kit dropped.");
    }

    // Inventory first, the rest on the ground. True when anything had to be dropped.
    private static boolean giveOrDrop(Player player, ItemStack stack)
    {
        player.getInventory().add(stack);
        if (stack.isEmpty())
            return false;
        player.drop(stack, false);
        return true;
    }
}
