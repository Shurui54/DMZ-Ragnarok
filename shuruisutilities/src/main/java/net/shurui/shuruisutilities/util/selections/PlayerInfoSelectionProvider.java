package net.shurui.shuruisutilities.util.selections;

import net.shurui.shuruisutilities.commons.selections.AreaBase;
import net.shurui.shuruisutilities.commons.selections.Point;
import net.shurui.shuruisutilities.commons.selections.Selection;
import net.shurui.shuruisutilities.util.PlayerInfo;

import net.minecraft.server.level.ServerPlayer;

public class PlayerInfoSelectionProvider implements ISelectionProvider
{

    @Override
    public Selection getSelection(ServerPlayer player)
    {
        PlayerInfo pi = PlayerInfo.get(player);
        return new Selection(pi.getSelDim(), pi.getSel1(), pi.getSel2());
    }

    @Override
    public void setDimension(ServerPlayer player, String dim)
    {
        PlayerInfo.get(player).setSelDim(dim);
    }

    @Override
    public void setStart(ServerPlayer player, Point start)
    {
        PlayerInfo.get(player).setSel1(start);
    }

    @Override
    public void setEnd(ServerPlayer player, Point end)
    {
        PlayerInfo.get(player).setSel2(end);
    }

    @Override
    public void select(ServerPlayer player, String dimension, AreaBase area)
    {
        PlayerInfo pi = PlayerInfo.get(player);
        pi.setSelDim(dimension);
        pi.setSel1(area.getLowPoint());
        pi.setSel2(area.getHighPoint());
        SelectionHandler.sendUpdate(player);
    }

}
