package net.shurui.dev.shuruis_raid_bosses.raid;

public enum RaidState {
    /** no raid; scheduler may open sign-ups */
    IDLE,
    SIGNUP,
    /** participants teleported in and healed, countdown ticking */
    COUNTDOWN,
    ACTIVE,
    /** boss dead (rewards paid) or timed out */
    FINISHED
}
