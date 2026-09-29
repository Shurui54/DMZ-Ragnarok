package net.shurui.shuruisutilities.core;

/**
 * Build-level switches for content deliberately held back from a release.
 *
 * <h2>Read this before changing anything here</h2>
 *
 * These are compile-time constants, NOT config. That is the whole point: a config default only applies to a
 * config file that does not exist yet, so flipping one would hold the content back on a fresh CurseForge install
 * while leaving it switched ON for every server that already has a config on disk, the live server included. A
 * constant is the same answer everywhere.
 *
 * <p>Flip a field back to {@code true} and rebuild to restore the feature. Nothing else needs undoing: every use
 * site is a guard, and none of them unregisters a block, item, entity or packet. That restraint is deliberate.
 * Pulling a registry entry to hide a feature silently orphans the world data and player inventories that
 * reference it, which is the one failure here that would not be temporary.</p>
 *
 * <h2>CURRENT STATE: ALL FOUR ARE ON</h2>
 *
 * They were switched back on for the TESTING builds, so a test jar carries the custom dragon ball sets, the
 * corrupted ball event, the ritual form unlocks and the task board GUI. Key gating is unaffected and still
 * applies normally; this only controls whether the content is present in the jar at all.
 *
 * <p><b>These must be flipped back to {@code false} before any build that goes to CurseForge or the main
 * server.</b> Nothing in the build distinguishes a test jar from a public one, so this file is the ONLY thing
 * standing between held-back content and a public release. Check it as part of cutting a release.</p>
 */
public final class ReleaseToggles
{
    private ReleaseToggles() {}

    /**
     * Shurui's three custom dragon ball SETS (Black Star, Super, Cerulean), their radars and their wishes, as
     * registered into DragonMineZ by {@code SuDragonBallDefinitions}.
     *
     * <p>Off means the sets are never handed to DMZ, so DMZ knows only its own seven-star set. Balls from a
     * custom set that are already in a world are left where they are and simply stop resolving to a set until
     * this is turned back on; nothing deletes them.</p>
     */
    public static final boolean CUSTOM_DRAGON_BALL_SETS = true;

    /**
     * The corrupted dragon ball event: arming the ball swap at the wish threshold, the seven corrupted balls it
     * scatters, defiling them, and the shadow dragon cinematic that follows.
     *
     * <p>Off means the event can no longer ARM, TRIGGER or be defiled. Deliberately still running: the leftover
     * cleanup pass, so corrupted balls already in a world are tidied away rather than stranded, and
     * {@code ShadowDragonUnlocks.onLogin}, which re-applies the omega form skill for players who ALREADY earned
     * the shadow dragon race. Those players keep everything they won; they just cannot win more while this is
     * off, and nobody new can start the ladder.</p>
     */
    public static final boolean CORRUPTED_DRAGON_BALL_EVENT = true;

    /**
     * The two ritual form unlocks: Super Saiyan 5 (the Shenron fusion wish) and Primal Namekian (granted for
     * recreating a dragon ball set).
     *
     * <p>Off means neither form can be unlocked. The SSJ5 wish is not offered on Shenron's list and is refused if
     * a crafted packet asks for it anyway, because both questions run through the same eligibility check.
     * Deliberately still working: recreating a dragon ball SET, which is a world event other players depend on
     * and is only incidentally where Primal Namekian is handed out. Recreating a set keeps announcing, keeps
     * undefiling the set and keeps costing nothing extra; it just grants no form while this is off.</p>
     *
     * <p>Players who already hold either form keep it. This gates the unlock, not the form.</p>
     */
    public static final boolean RITUAL_FORM_UNLOCKS = true;

    /**
     * The daily/weekly/monthly task board GUI.
     *
     * <p>Off means the board never opens: the server refuses to send it, whichever of the keybind, the hub menu
     * or a direct request asked, and the hub row is hidden so there is no dead button. The Tasks MODULE keeps
     * running underneath, so boards still roll and progress still counts. Turning this back on returns every
     * player to a live, correctly rolled board rather than an empty one.</p>
     */
    public static final boolean TASK_BOARD_GUI = true;
}
