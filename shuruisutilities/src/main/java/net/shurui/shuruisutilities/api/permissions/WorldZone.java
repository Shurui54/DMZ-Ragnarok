package net.shurui.shuruisutilities.api.permissions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.commons.selections.WorldArea;
import net.shurui.shuruisutilities.commons.selections.WorldPoint;
import net.shurui.shuruisutilities.data.v2.Loadable;
import com.google.gson.annotations.Expose;

import net.minecraft.world.entity.player.Player;

// covers a whole world. third-lowest priority, above ServerZone.
public class WorldZone extends Zone implements Loadable
{

    @Expose(serialize = false)
    // Back-reference, rebuilt by ServerZone.afterLoad(), so it is runtime wiring rather than file content.
    // transient as well as @Expose so the exclusion survives anyone dropping the annotation: it closes a
    // reference cycle Gson would otherwise walk.
    protected transient ServerZone serverZone;

    private String dimensionID;

    private List<AreaZone> areaZones = new ArrayList<>();

    public WorldZone(int id)
    {
        super(id);
    }

    public WorldZone(ServerZone serverZone, String dimensionID, int id)
    {
        this(id);
        this.dimensionID = dimensionID;
        this.serverZone = serverZone;
        this.serverZone.addWorldZone(this);
    }

    public WorldZone(ServerZone serverZone, String dimensionID)
    {
        this(serverZone, dimensionID, serverZone.nextZoneID());
    }

    @Override
    public void afterLoad()
    {
        for (AreaZone zone : areaZones)
            zone.worldZone = this;
    }

    @Override
    public boolean isPlayerInZone(Player player)
    {
        return player.level().dimension().location().toString().equals(dimensionID);
    }

    @Override
    public boolean isInZone(WorldPoint point)
    {
        return point.getDimension().equals(dimensionID);
    }

    @Override
    public boolean isInZone(WorldArea area)
    {
        return area.getDimension().equals(dimensionID);
    }

    @Override
    public boolean isPartOfZone(WorldArea area)
    {
        return area.getDimension().equals(dimensionID);
    }

    @Override
    public String getName()
    {
        return dimensionID;
    }

    @Override
    public Zone getParent()
    {
        return serverZone;
    }

    @Override
    public ServerZone getServerZone()
    {
        return serverZone;
    }

    public String getDimensionID()
    {
        return dimensionID;
    }

    public AreaZone getAreaZone(String areaName)
    {
        for (AreaZone areaZone : areaZones)
        {
            if (areaZone.getShortName().equals(areaName))
            {
                return areaZone;
            }
        }
        return null;
    }

    public boolean removeAreaZone(AreaZone zone)
    {
        if (APIRegistry.getSUEventBus().post(new PermissionEvent.Zone.Delete(getServerZone(), zone)))
            return false;
        return serverZone.removeZone(zone) | areaZones.remove(zone);
    }

    public Collection<AreaZone> getAreaZones()
    {
        return areaZones;
    }

    public void sortAreaZones()
    {
        Collections.sort(areaZones);
    }

    void addAreaZone(AreaZone areaZone)
    {
        areaZones.add(areaZone);
        getServerZone().addZone(areaZone);
        sortAreaZones();
        setDirty();
    }

}