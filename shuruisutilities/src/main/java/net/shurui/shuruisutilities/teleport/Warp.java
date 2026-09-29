package net.shurui.shuruisutilities.teleport;

import net.shurui.shuruisutilities.commons.selections.WarpPoint;

import net.minecraft.world.entity.Entity;

/**
 * A global warp, persisted through {@link net.shurui.shuruisutilities.data.v2.DataManager} (one file per warp name).
 *
 * <p>The SIMPLE CLASS NAME is the DataManager folder ({@code SUData/json/Warp}), so it must never be renamed. It used
 * to be nested in {@code CommandWarp}; the command moved into the Ragnarok Key (S10) and the persisted record stayed
 * here in core, unchanged (same simple name, same fields), so existing warp files load exactly as before. The shard
 * warp table ({@code ShardWarps}) seeds from the same folder.
 */
public class Warp extends WarpPoint
{
    public Warp(Entity entity)
    {
        super(entity);
    }
}
