package net.shurui.shuruisutilities.corrupted;

import net.minecraftforge.fml.ModList;

/**
 * Optional-dependency bridge to sdu's HUD compass, used to give each live shadow dragon its own pip. Modelled on
 * {@link net.shurui.shuruisutilities.airdrop.SduMarkerBridge} but generalised: it takes an explicit marker id so
 * the seven slots each get a distinct, stable pip (see {@link #markerId(int)}). sdu classes are touched only
 * inside the inner holder, behind isLoaded, so this is safe to load without sdu (the encounter then simply shows
 * no pips; everything else still works).
 *
 * <p>Two things to remember about sdu's markers, so nobody is surprised later:
 * <ul>
 *   <li>They are TRANSIENT: sdu holds them in memory only and does not persist them. That is exactly why the
 *       encounter re-registers a pip for every still-living dragon on server start (see {@link
 *       ShadowDragonBossManager#restoreOnStart}). Without that restore, a restart would silently drop every pip.</li>
 *   <li>A pip only renders while the VIEWING player is in the pip's own dimension. A dragon in the overworld is
 *       invisible on the compass of a player standing in the nether, by design.</li>
 * </ul>
 */
final class SduDragonMarkerBridge
{
    // one stable pip per slot: su_shadow_dragon_1 .. su_shadow_dragon_7. Stable so an update replaces in place and
    // a restart can re-register the exact same id.
    private static final String MARKER_ID_PREFIX = "su_shadow_dragon_";
    // deep red, matching the "pure malice" theme of the cinematic
    private static final int MARKER_COLOR = 0xFFB22222;
    // a skull reads as "boss target" on the bar regardless of the boss entity's actual model
    private static final String MARKER_ICON = "minecraft:wither_skeleton_skull";

    private SduDragonMarkerBridge() {}

    static String markerId(int slot)
    {
        return MARKER_ID_PREFIX + slot;
    }

    // sdu is now part of this same container, so the pip is always registered/removed.
    // register or move the pip for a slot. name is the dragon's display name so the compass label reads sensibly.
    static void set(int slot, String dim, double x, double y, double z, String name)
    {
        Sdu.set(markerId(slot), dim, x, y, z, name);
    }

    static void remove(int slot)
    {
        Sdu.remove(markerId(slot));
    }

    // the only class that names sdu types; never classloaded unless sdu is present
    private static final class Sdu
    {
        static void set(String id, String dim, double x, double y, double z, String name)
        {
            net.shurui.dev.sdu.waypoint.GlobalMarkers.set(id, dim, x, y, z, name, MARKER_COLOR, MARKER_ICON);
        }

        static void remove(String id)
        {
            net.shurui.dev.sdu.waypoint.GlobalMarkers.remove(id);
        }
    }
}
