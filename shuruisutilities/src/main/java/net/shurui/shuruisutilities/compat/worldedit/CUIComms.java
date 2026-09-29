package net.shurui.shuruisutilities.compat.worldedit;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.util.CommandUtils;
import net.shurui.shuruisutilities.util.CommandUtils.CommandInfo;
import net.shurui.shuruisutilities.util.selections.SelectionHandler;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

// watches for interactions that could change the WorldEdit selection and pushes a selection update to the client
public class CUIComms
{

    public CUIComms()
    {
        MinecraftForge.EVENT_BUS.register(this);
    }

    public static final String[] worldEditSelectionCommands = new String[] { "pos1", "pos2", "sel", "desel", "hpos1",
            "hpos2", "/hunk", "expand", "contract", "outset", "inset", "shift" };

    protected List<ServerPlayer> updatedSelectionPlayers = new ArrayList<>();

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void checkWECommands(CommandEvent e)
    {
    	if (e.getParseResults().getContext().getNodes().isEmpty())
            return;
        if (e.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer)
        {
            CommandInfo info = CommandUtils.getCommandInfo(e);
            for (String weCmd : worldEditSelectionCommands)
            {
                if (info.getCommandName().equals(weCmd) && !(info.getSource().getEntity() instanceof FakePlayer))
                {
                    updatedSelectionPlayers.add((ServerPlayer) info.getSource().getEntity());
                    return;
                }
            }
        }
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent e)
    {
        for (ServerPlayer player : updatedSelectionPlayers)
            SelectionHandler.sendUpdate(player);
        updatedSelectionPlayers.clear();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void playerInteractEvent(PlayerInteractEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer)
            updatedSelectionPlayers.add((ServerPlayer) event.getEntity());
    }

}
