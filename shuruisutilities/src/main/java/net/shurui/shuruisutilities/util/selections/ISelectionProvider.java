package net.shurui.shuruisutilities.util.selections;

import net.shurui.shuruisutilities.commons.selections.AreaBase;
import net.shurui.shuruisutilities.commons.selections.Point;
import net.shurui.shuruisutilities.commons.selections.Selection;

import net.minecraft.server.level.ServerPlayer;

public interface ISelectionProvider
{

    public Selection getSelection(ServerPlayer player);

    public void setDimension(ServerPlayer player, String dim);

    public void setStart(ServerPlayer player, Point start);

    public void setEnd(ServerPlayer player, Point end);

    public void select(ServerPlayer player, String dimension, AreaBase area);

}
