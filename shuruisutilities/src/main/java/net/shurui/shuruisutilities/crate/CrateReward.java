package net.shurui.shuruisutilities.crate;

import net.minecraft.world.item.ItemStack;

// one possible reward inside a Crate: an item stack (full NBT/enchants survive), a relative weight for the
// roll, and optional extras (console command with %player substituted, chat message to the winner)
public class CrateReward
{
    public ItemStack stack = ItemStack.EMPTY;

    public int weight = 1;

    public String command; // optional console command on win; %player substituted

    public String message; // optional chat message to the winner

    public CrateReward()
    {
    }

    public CrateReward(ItemStack stack, int weight)
    {
        this.stack = stack;
        this.weight = Math.max(1, weight);
    }

    // short label for lists/GUIs; shows item, command and message parts
    public String describe()
    {
        StringBuilder sb = new StringBuilder();
        if (stack != null && !stack.isEmpty())
            sb.append(stack.getCount()).append("x ").append(stack.getHoverName().getString());
        if (command != null && !command.isEmpty())
            sb.append(sb.length() > 0 ? " §7+ " : "").append("§bcmd:§r ").append(command);
        if (message != null && !message.isEmpty())
            sb.append(sb.length() > 0 ? " §7+ " : "").append("§dmsg:§r ").append(message);
        return sb.length() == 0 ? "(empty)" : sb.toString();
    }
}
