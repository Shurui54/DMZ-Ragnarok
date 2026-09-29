package net.shurui.shuruisutilities.teleport;

import java.util.HashMap;

import net.shurui.shuruisutilities.commons.selections.WarpPoint;

/**
 * One player's personal warps (name to point), persisted through
 * {@link net.shurui.shuruisutilities.data.v2.DataManager} (one file per player UUID).
 *
 * <p>The SIMPLE CLASS NAME is the DataManager folder ({@code SUData/json/PersonalWarp}), so it must never be renamed.
 * It used to be nested in {@code CommandPersonalWarp}; the command moved into the Ragnarok Key (S10) and the persisted
 * record stayed here in core, unchanged, so existing files load exactly as before.
 */
public class PersonalWarp extends HashMap<String, WarpPoint>
{
}
