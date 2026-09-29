package net.shurui.shuruisutilities.util;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.commons.selections.AreaBase;
import net.shurui.shuruisutilities.commons.selections.Point;
import net.shurui.shuruisutilities.commons.selections.WorldArea;
import net.shurui.shuruisutilities.commons.selections.WorldPoint;
import com.google.gson.annotations.Expose;

import net.minecraft.server.level.ServerLevel;

// pins a WorldArea to a multiworld by name, so it survives dim-id changes
public class NamedWorldArea extends WorldArea
{

    protected String worldName;

    @Expose(serialize = false)
    protected boolean isLinked = false;

    @Expose(serialize = false)
    protected boolean isValid = true;

    public NamedWorldArea(String dimension, String worldName, Point start, Point end)
    {
        super(dimension, start, end);
        this.worldName = worldName;
        isLinked();
    }

    public NamedWorldArea(Point start, Point end, String worldName)
    {
        this("minecraft:overworld", worldName, start, end);
    }

    public NamedWorldArea(AreaBase area, String worldName)
    {
        this("minecraft:overworld", worldName, area.getLowPoint(), area.getHighPoint());
    }

    public NamedWorldArea(String dimension, Point start, Point end)
    {
        super(dimension, start, end);
        this.worldName = APIRegistry.namedWorldHandler.getWorldName(dimension);
        isLinked();
    }

    public NamedWorldArea(String dimension, AreaBase area)
    {
        this(dimension, area.getLowPoint(), area.getHighPoint());
    }

    public NamedWorldArea(WorldArea area)
    {
        this(area.getDimension(), area.getLowPoint(), area.getHighPoint());
    }

    public boolean isLinked()
    {
        if (!isValid())
            return false;
        return isLinked;
    }

    public boolean isValid()
    {
        if (!isValid)
        {
            if (worldName != null)
            {
                // If there is a name for the dimension, use it
                ServerLevel world = APIRegistry.namedWorldHandler.getWorld(worldName);
                if (world != null)
                {
                    this.dim = world.dimension().location().toString();
                    isLinked = true;
                    isValid = true;
                }
            }
            else
            {
                // If no name was set, just use dimID
                isLinked = false;
                isValid = true;
            }
        }
        return isValid;
    }

    @Override
    public boolean contains(WorldPoint point)
    {
        if (!isValid())
            return false;
        return super.contains(point);
    }

    @Override
    public boolean contains(WorldArea area)
    {
        if (!isValid())
            return false;
        return super.contains(area);
    }

}
