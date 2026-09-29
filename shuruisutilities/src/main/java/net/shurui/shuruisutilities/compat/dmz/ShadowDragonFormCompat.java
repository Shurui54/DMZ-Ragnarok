package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/**
 * Guard entry point for the shadow dragon transformation grant and the live DMZ race read it depends on. Holds no
 * DMZ imports: it only checks that DMZ is present before touching {@link ShadowDragonFormSkill} (the race read) or the
 * Ragnarok Key's form grant (feature {@code shadowform}, through {@code ShadowFormHooks}). Follows
 * the optional-dependency pattern.
 *
 * <p>The transformation is applied in DMZ by raising the player's shared {@code superforms} skill to the shadow
 * dragon form's unlock level, but ONLY while the player's live race is {@code shadow_dragon} (see
 * {@link ShadowDragonFormSkill} for why: the skill group is shared across races). The durable entitlement itself is
 * the SU permission property in {@link net.shurui.shuruisutilities.corrupted.RaceUnlocks}; this only reflects it into
 * DMZ as working state.
 */
public final class ShadowDragonFormCompat
{
    private ShadowDragonFormCompat() {}

    /** The DMZ race id the transformation belongs to; safe to compare without DMZ loaded. */
    public static final String SHADOW_DRAGON_RACE = "shadow_dragon";

    /**
     * The shadow dragon SUB-races, which hold the transformation just as the base race does. Each is a full DMZ race
     * with its own {@code superforms} group (see {@code RaceBundleExtractor}), so six of the seven shadow dragon races
     * are these.
     *
     * <p>WHY THIS EXISTS: the transformation grant used to test {@code "shadow_dragon".equals(race)} exactly, which is
     * true for the base race alone. A player on any sub-race (Eis, Nuova, Haze, Rage, Oceanus, Naturon, the stars)
     * was refused the Omega form outright, with the refusal logged as a cross-race guard rather than surfaced. That is
     * the "Omega does not work" report: it works only for whoever happens to be on the plain base race.
     */
    public static final java.util.Set<String> SHADOW_DRAGON_SUBRACES = java.util.Set.of(
            "shadow_dragon_2star", "shadow_dragon_3star", "shadow_dragon_4star",
            "shadow_dragon_5star", "shadow_dragon_6star", "shadow_dragon_7star");

    /**
     * True for the base shadow dragon race and every sub-race: the set the transformation may be granted to.
     *
     * <p>This is NOT a relaxation of the cross-race guard the exact check was protecting. That guard exists because
     * {@code superforms} is shared across races, so raising it for a saiyan would hand them Super Saiyan for free.
     * Every id admitted here is a shadow dragon race whose own {@code superforms} group defines a shadow dragon form,
     * and the form config is resolved off the player's live race name, so the grant stays scoped to that race's own
     * form. A saiyan is still refused.
     */
    public static boolean isShadowDragonRace(String race)
    {
        // NULL IS A NORMAL ARGUMENT HERE, and this guard is not defensive padding. currentRace is documented to
        // return null for a statless player, and every caller feeds it straight in. A player who has just died is
        // exactly that: the crash that produced this guard has the dying player as removed=KILLED while the
        // once-a-second energy sync was walking the player list. Without it the null falls through to
        // Set.of(...).contains(null), and an immutable set THROWS on a null lookup rather than answering false, so an
        // ordinary death took the whole server tick loop down with an NPE.
        if (race == null)
            return false;
        return SHADOW_DRAGON_RACE.equals(race) || SHADOW_DRAGON_SUBRACES.contains(race);
    }

    /**
     * The {@code superforms} form key for the base shadow dragon race's transformation (Omega Shenron, level 1). This
     * class only ever grants for the base {@code shadow_dragon} race, so this is the form it resolves to; the shadow
     * dragon sub-races define their own super forms at the same level 1 rung. The name is a persisted argument value
     * and must not change.
     */
    public static final String FORM_OMEGA_SHENRON = "omega_shenron";

    /**
     * The player's current DMZ race id (lowercase as DMZ stores it), or null when DMZ is absent or the player is
     * statless. Used by the kill path to decide whether a kill counts toward the race-scoped transformation tally,
     * and by the grant/login paths to enforce the cross-race guard.
     */
    public static String currentRace(ServerPlayer player)
    {
        if (player == null || !ModList.get().isLoaded("dragonminez"))
            return null;
        return ShadowDragonFormSkill.currentRace(player);
    }

    /**
     * True when the player is currently playing ANY shadow dragon race: the base race or one of the six sub-races.
     *
     * <p>Both callers need the wider set. The kill tally must count a sub-race player's kills or they can never
     * progress toward the transformation at all, and the login re-apply gates the form grant on this before it even
     * reaches {@link #applyFormSkill}, so a base-race-only test here would keep refusing sub-races no matter what the
     * skill guard allows.
     */
    public static boolean isShadowDragon(ServerPlayer player)
    {
        return isShadowDragonRace(currentRace(player));
    }

    /**
     * Reflect a transformation entitlement into DMZ for a player who is currently {@code shadow_dragon}, by raising
     * their {@code superforms} skill to the given form's unlock level (only ever raises). {@code formName} is the
     * {@code superforms} form key for the base race ({@link #FORM_OMEGA_SHENRON}); since the grant is a skill LEVEL,
     * that key resolves to whichever form the race defines at that level. The required level is
     * read from that form's config. No-op (returns false) when DMZ is absent, the player is another race, the DMZ
     * internals no longer match, or the Ragnarok Key is absent (the form is private). Never throws into the caller.
     */
    public static boolean applyFormSkill(ServerPlayer player, String formName)
    {
        if (player == null || !ModList.get().isLoaded("dragonminez"))
            return false;
        // The Omega form is the Ragnarok Key's (feature shadowform); keyless nothing is applied.
        return net.shurui.shuruisutilities.api.key.ShadowFormHooks.get().applyFormSkill(player, formName);
    }
}
