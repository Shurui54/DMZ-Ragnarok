package net.shurui.shuruisutilities.client.hud;

/**
 * What the client knows about the staff task reminder. Written only by {@code PacketStaffHud}, read only by {@link
 * StaffTaskHudOverlay}. No logic on purpose: whether a reminder is up is the server's call.
 */
public final class StaffTaskHudState
{
    private StaffTaskHudState() {}

    private static boolean active;
    private static String title = "";
    private static String description = "";
    private static String denialReason = "";

    public static void set(boolean on, String taskTitle, String taskDescription, String denial)
    {
        active = on;
        title = taskTitle == null ? "" : taskTitle;
        description = taskDescription == null ? "" : taskDescription;
        denialReason = denial == null ? "" : denial;
    }

    /** Forget everything. Called on disconnect so a reminder cannot survive into the next server. */
    public static void clear()
    {
        set(false, "", "", "");
    }

    public static boolean isActive()
    {
        return active;
    }

    public static String title()
    {
        return title;
    }

    public static String description()
    {
        return description;
    }

    public static String denialReason()
    {
        return denialReason;
    }
}
