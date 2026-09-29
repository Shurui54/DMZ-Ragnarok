package net.shurui.shuruisutilities.space;

import net.minecraft.server.level.ServerPlayer;

/**
 * The pod-launch autopilot's target (dimension id / planet key), one string on the player's RAW persistent tag, ""
 * when inactive. On the raw tag like {@link PlanetCourse}'s course tag, which gives the relog/death behaviour for free:
 * <ul>
 *   <li>SURVIVES logout (Forge persists the root tag), so an autopilot interrupted by a relog RESUMES from
 *       {@code SpaceTravelModule}'s tick. Stranding a floating player with dead controls was the alternative.</li>
 *   <li>Does NOT survive DEATH (only the PlayerPersisted sub-tag copies onto the respawn clone), so dying mid-transit
 *       auto-cancels it.</li>
 * </ul>
 * Dismount, pod destroyed, landing and manual leave are cleared explicitly by the driver.
 */
public final class SpaceAutopilot
{
    private SpaceAutopilot()
    {
    }

    private static final String AUTOPILOT_TAG = "su_space_autopilot";

    public static String target(ServerPlayer player)
    {
        return player.getPersistentData().getString(AUTOPILOT_TAG);
    }

    public static boolean isActive(ServerPlayer player)
    {
        return !target(player).isEmpty();
    }

    public static void set(ServerPlayer player, String bodyKey)
    {
        if (bodyKey == null || bodyKey.isEmpty())
        {
            clear(player);
            return;
        }
        player.getPersistentData().putString(AUTOPILOT_TAG, bodyKey);
    }

    public static void clear(ServerPlayer player)
    {
        player.getPersistentData().remove(AUTOPILOT_TAG);
    }
}
