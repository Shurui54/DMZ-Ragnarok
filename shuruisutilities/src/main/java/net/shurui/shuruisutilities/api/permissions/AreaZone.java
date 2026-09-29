package net.shurui.shuruisutilities.api.permissions;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.commons.selections.AreaBase;
import net.shurui.shuruisutilities.commons.selections.AreaShape;
import net.shurui.shuruisutilities.commons.selections.Point;
import net.shurui.shuruisutilities.commons.selections.WorldArea;
import net.shurui.shuruisutilities.commons.selections.WorldPoint;
import net.shurui.shuruisutilities.util.events.EventCancelledException;
import com.google.gson.annotations.Expose;

// covers one area in a world. highest priority of all Zone types. may overlap; smallest/innermost wins.
public class AreaZone extends Zone implements Comparable<AreaZone>
{

    @Expose(serialize = false)
    // Back-reference, rebuilt by WorldZone.afterLoad(). transient as well as @Expose: see WorldZone.
    protected transient WorldZone worldZone;

    private String name;

    private AreaBase area;

    private AreaShape shape = AreaShape.BOX;

    private int priority;

    private AreaZone(int id)
    {
        super(id);
    }

    public AreaZone(WorldZone worldZone, String name, AreaBase area, int id)
    {
        this(id);
        this.worldZone = worldZone;
        this.name = name;
        this.area = area;
        this.worldZone.addAreaZone(this);
    }

    public AreaZone(WorldZone worldZone, String name, AreaBase area) throws EventCancelledException
    {
        this(worldZone.getServerZone().getMaxZoneID() + 1);
        this.worldZone = worldZone;
        this.name = name;
        this.area = area;

        // creation can be vetoed here
        EventCancelledException.checkedPost(new PermissionEvent.Zone.Create(worldZone.getServerZone(), this),
                APIRegistry.getSUEventBus());

        // not cancelled: bump the zoneID pointer and register with the world
        worldZone.getServerZone().nextZoneID();
        this.worldZone.addAreaZone(this);
    }

    @Override
    public boolean isInZone(WorldPoint point)
    {
        if (!worldZone.isInZone(point))
            return false;
        return shape.contains(area, point);
    }

    @Override
    public boolean isInZone(WorldArea otherArea)
    {
        if (!worldZone.isInZone(otherArea))
            return false;
        return shape.contains(area, otherArea);
    }

    @Override
    public boolean isPartOfZone(WorldArea otherArea)
    {
        if (!worldZone.isPartOfZone(otherArea))
            return false;
        return this.area.intersectsWith(otherArea);
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public String toString()
    {
        return worldZone.getName() + "_" + name;
    }

    @Override
    public Zone getParent()
    {
        return worldZone;
    }

    @Override
    public ServerZone getServerZone()
    {
        return worldZone.getServerZone();
    }

    public String getShortName()
    {
        return name;
    }

    public WorldZone getWorldZone()
    {
        return worldZone;
    }

    public AreaBase getArea()
    {
        return area;
    }

    public WorldArea getWorldArea()
    {
        return new WorldArea(worldZone.getDimensionID(), area);
    }

    public void setArea(AreaBase area)
    {
        this.area = area;
        setDirty();
        getWorldZone().sortAreaZones();
    }

    public AreaShape getShape()
    {
        return shape;
    }

    public void setShape(AreaShape shape)
    {
        if (shape == null)
            this.shape = AreaShape.BOX;
        else
            this.shape = shape;
        setDirty();
    }

    public int getPriority()
    {
        return priority;
    }

    public void setPriority(int priority)
    {
        this.priority = priority;
        setDirty();
    }

    @Override
    public int compareTo(AreaZone otherArea)
    {
        int cmp = otherArea.priority - this.priority;
        if (cmp != 0)
            return cmp;

        Point areaSize = otherArea.getArea().getSize();
        Point thisSize = this.getArea().getSize();
        cmp = (thisSize.getX() * thisSize.getY()) - (areaSize.getX() * areaSize.getY());

        return cmp;
    }

    @Override
    public boolean isHidden()
    {
        String hiddenValue = getGroupPermission(GROUP_DEFAULT, SUPermissions.ZONE_HIDDEN);
        return hiddenValue != null && !PERMISSION_FALSE.equals(hiddenValue);
    }

    public void setHidden(boolean hidden)
    {
        if (hidden)
            setGroupPermission(GROUP_DEFAULT, SUPermissions.ZONE_HIDDEN, hidden);
        else
            clearGroupPermission(GROUP_DEFAULT, SUPermissions.ZONE_HIDDEN);
    }

}
