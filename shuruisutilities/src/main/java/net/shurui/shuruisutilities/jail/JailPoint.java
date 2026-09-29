package net.shurui.shuruisutilities.jail;

import net.shurui.shuruisutilities.commons.selections.WarpPoint;

import net.minecraft.world.entity.Entity;

/**
 * A named jail location, persisted through {@link net.shurui.shuruisutilities.data.v2.DataManager} exactly like
 * a warp (keyed by jail name). Extending {@link WarpPoint} gives us dimension + position + facing plus the
 * {@code getWorld()} resolution used to teleport players in and to keep them contained.
 */
public class JailPoint extends WarpPoint
{
    /** Captures the current location/facing of {@code entity} (used by {@code /setjail}). */
    public JailPoint(Entity entity)
    {
        super(entity);
    }

    public JailPoint(String dimension, double x, double y, double z, float pitch, float yaw)
    {
        super(dimension, x, y, z, pitch, yaw);
    }
}
