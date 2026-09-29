package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/**
 * Guard entry point for the dragon-ball ritual reward grants. Holds no DMZ imports: it only checks that DMZ is present
 * before touching {@link RitualForms}, which is the sole class here that references DMZ types. Follows
 * the optional-dependency pattern, mirroring {@link ShadowDragonFormCompat}.
 */
public final class RitualFormsCompat
{
    private RitualFormsCompat() {}

    public static final String RACE_SAIYAN = "saiyan";
    public static final String RACE_HALF_SAIYAN = "half_saiyan";
    public static final String RACE_NAMEKIAN = "namekian";

    private static boolean dmz()
    {
        return ModList.get().isLoaded("dragonminez");
    }

    /** The player's DMZ race id (lowercase), or null when DMZ is absent or the player is statless. */
    public static String currentRace(ServerPlayer player)
    {
        if (player == null || !dmz())
            return null;
        return RitualForms.currentRace(player);
    }

    /** The player's DMZ level, or 0 when DMZ is absent or the player is statless. */
    public static int level(ServerPlayer player)
    {
        if (player == null || !dmz())
            return 0;
        return RitualForms.level(player);
    }

    /** True when the player is a saiyan currently transformed into Super Saiyan 4. */
    public static boolean isSaiyanInSsj4(ServerPlayer player)
    {
        return player != null && dmz() && RitualForms.isSaiyanInSsj4(player);
    }

    public static boolean isRace(ServerPlayer player, String race)
    {
        return player != null && dmz() && RitualForms.isRace(player, race);
    }

    /**
     * True when the player is on the Super Saiyan God race line: full saiyan OR half saiyan. Used ONLY for the SSG
     * knowledge wish and the SSG charge ritual, never for the saiyan-only SSJ5 line. No-op false without DMZ.
     */
    public static boolean isSsgRace(ServerPlayer player)
    {
        return player != null && dmz() && RitualForms.isSsgRace(player);
    }

    /** True when the player is a saiyan currently charging ki (SSG charge-ritual participation signal). */
    public static boolean isSaiyanChargingKi(ServerPlayer player)
    {
        return player != null && dmz() && RitualForms.isSaiyanChargingKi(player);
    }

    public static boolean grantSsj5(ServerPlayer player)
    {
        return player != null && dmz() && RitualForms.grantSsj5(player);
    }

    /**
     * The Namekian God unlock earned by restoring the dragon balls.
     *
     * <p>PLACEHOLDER. The form itself does not exist yet, so this grants Primal Namekian, which is the working
     * namekian super form that ships today. That keeps the reward real instead of an IOU. When the god form is built,
     * point this at it and nothing else in the ritual has to change: this method is the only place that decides what
     * "Namekian God" means.
     */
    public static boolean grantNamekianGod(ServerPlayer player)
    {
        return grantPrimalNamekian(player);
    }

    public static boolean grantPrimalNamekian(ServerPlayer player)
    {
        return player != null && dmz() && RitualForms.grantPrimalNamekian(player);
    }

    public static boolean beginTempSsg(ServerPlayer player)
    {
        return player != null && dmz() && RitualForms.beginTempSsg(player);
    }

    public static boolean isInSsg(ServerPlayer player)
    {
        return player != null && dmz() && RitualForms.isInSsg(player);
    }

    /** See {@link RitualForms#healStrandedForm}. Clears an unresolvable active form in any group. No-op without DMZ. */
    public static boolean healStrandedForm(ServerPlayer player)
    {
        return player != null && dmz() && RitualForms.healStrandedForm(player);
    }

    /**
     * See {@link RitualForms#reconcileTempSsgGrant}. Drops a temporary-SSG marker whose grant is already gone, so it
     * can never be spent on a form the player later buys. No-op without DMZ.
     */
    public static boolean reconcileTempSsgGrant(ServerPlayer player)
    {
        return player != null && dmz() && RitualForms.reconcileTempSsgGrant(player);
    }

    public static void endTempSsg(ServerPlayer player)
    {
        if (player != null && dmz())
            RitualForms.endTempSsg(player);
    }

    public static boolean playPotaraPose(ServerPlayer player, int ticks)
    {
        return player != null && dmz() && RitualForms.playPotaraPose(player, ticks);
    }

    public static void endPotaraPose(ServerPlayer player)
    {
        if (player != null && dmz())
            RitualForms.setPotaraTimer(player, 0);
    }

    /**
     * Apply the destructive level cost (random stat removal until the level drops by {@code levelsToRemove}, never below
     * zero in any stat). Returns the number of levels actually removed. No-op returning 0 when DMZ is absent.
     */
    public static int applyLevelCost(ServerPlayer player, int levelsToRemove)
    {
        if (player == null || !dmz())
            return 0;
        return RitualForms.applyLevelCost(player, levelsToRemove);
    }
}
