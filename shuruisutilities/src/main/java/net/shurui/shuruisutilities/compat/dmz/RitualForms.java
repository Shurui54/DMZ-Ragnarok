package net.shurui.shuruisutilities.compat.dmz;

import java.util.Locale;
import java.util.Set;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Character;
import com.dragonminez.common.stats.character.Stats;
import com.dragonminez.common.stats.character.Status;
import com.dragonminez.common.stats.skills.Skills;
import com.dragonminez.common.util.TransformationsHelper;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

import net.shurui.shuruisutilities.compat.DmzBridge;
import net.shurui.shuruisutilities.ritual.WishRitualStore;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * DMZ-facing half of the dragon-ball ritual rewards, reached only through {@link RitualFormsCompat}'s
 * {@code isLoaded("dragonminez")} guard, so DMZ types are safe to reference here. Everything is wrapped so a DMZ
 * internals change or a statless player degrades to a no-op rather than a crash.
 *
 * <p>Forms are granted the way DMZ marks a form learned: a form is available iff the form-group skill level reaches the
 * form's {@code unlockOnSkillLevel}. SSJ5 and Primal Namekian are new forms in the shared {@code superforms} group at the
 * next rung above the race's top form (saiyan 9, namekian 4); Super Saiyan God is a form in the {@code godforms} group.
 * The ritual JSON that defines those rungs is seeded by {@link RitualRaceMergeImpl}.
 */
final class RitualForms
{
    private RitualForms() {}

    static final String RACE_SAIYAN = "saiyan";
    static final String RACE_HALF_SAIYAN = "half_saiyan";
    static final String RACE_NAMEKIAN = "namekian";

    /**
     * The races that share the Super Saiyan God line: full saiyan and half saiyan. Half saiyan keeps the god
     * ritual (its character.json ships and is priced for {@code godforms}, and RitualRaceMergeImpl seeds the form
     * for it) but NOT the SSJ5/oozaru line, so this set gates ONLY the SSG knowledge wish and the SSG charge
     * ritual. SSJ4/SSJ5 stay strictly {@link #RACE_SAIYAN} (see {@link #isSaiyanInSsj4} and {@link #grantSsj5}).
     */
    private static final Set<String> SSG_RACES = Set.of(RACE_SAIYAN, RACE_HALF_SAIYAN);

    /** DMZ form id of Super Saiyan 4 (the gate for the SSJ5 fusion offer). */
    private static final String FORM_SSJ4 = "supersaiyan4";

    /** The shared super-form skill; SSJ5 sits at rung 9 for saiyan, Primal Namekian at rung 4 for namekian. */
    private static final String SKILL_SUPERFORMS = "superforms";
    private static final int SSJ5_RUNG = 9;
    private static final int PRIMAL_NAMEKIAN_RUNG = 4;

    /** The god-form skill and the Super Saiyan God form id/group. */
    private static final String SKILL_GODFORMS = "godforms";
    private static final String GROUP_GODFORMS = "godforms";
    private static final String FORM_SSG = "supersaiyangod";

    // stat keys: Stats.removeStat wants lowercase, StatsData.getCurrentStatValue wants uppercase.
    private static final String[] STAT_KEYS_LOWER = { "str", "skp", "res", "vit", "pwr", "ene" };

    static String currentRace(ServerPlayer player)
    {
        StatsData stats = DmzBridge.stats(player);
        if (stats == null)
            return null;
        try
        {
            Character c = stats.getCharacter();
            return c == null ? null : c.getRaceName();
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    static int level(ServerPlayer player)
    {
        StatsData stats = DmzBridge.stats(player);
        if (stats == null)
            return 0;
        try
        {
            return stats.getLevel();
        }
        catch (Throwable t)
        {
            return 0;
        }
    }

    /** True when the player is a saiyan currently transformed into Super Saiyan 4. */
    static boolean isSaiyanInSsj4(ServerPlayer player)
    {
        StatsData stats = DmzBridge.stats(player);
        if (stats == null)
            return false;
        try
        {
            Character c = stats.getCharacter();
            if (c == null || !RACE_SAIYAN.equalsIgnoreCase(c.getRaceName()))
                return false;
            return FORM_SSJ4.equalsIgnoreCase(c.getActiveForm());
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    static boolean isRace(ServerPlayer player, String race)
    {
        return race != null && race.equalsIgnoreCase(currentRace(player));
    }

    /**
     * True when the player is on the Super Saiyan God race line (saiyan or half saiyan). Null-safe: an immutable
     * {@link Set} throws on {@code contains(null)}, and {@link #currentRace} returns null for a statless player.
     */
    static boolean isSsgRace(ServerPlayer player)
    {
        return isSsgRaceName(currentRace(player));
    }

    /** {@link #isSsgRace} on a race NAME already in hand (from {@code Character.getRaceName()}). Null-safe. */
    private static boolean isSsgRaceName(String raceName)
    {
        return raceName != null && SSG_RACES.contains(raceName.toLowerCase(Locale.ROOT));
    }

    /** True when the player is a saiyan who is currently charging ki (the SSG charge ritual's participation signal). */
    static boolean isSaiyanChargingKi(ServerPlayer player)
    {
        StatsData stats = DmzBridge.stats(player);
        if (stats == null)
            return false;
        try
        {
            Character c = stats.getCharacter();
            // SSG charge ritual: half saiyans share the god line, so they may take part and centre a ring.
            if (c == null || !isSsgRaceName(c.getRaceName()))
                return false;
            return stats.getStatus().isChargingKi();
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** Grant SSJ5 to a saiyan by raising the superforms skill to rung 9. Returns true when the rung was reached. */
    static boolean grantSsj5(ServerPlayer player)
    {
        return raiseSuperform(player, RACE_SAIYAN, SSJ5_RUNG);
    }

    /** Grant Primal Namekian to a namekian by raising the superforms skill to rung 4. */
    static boolean grantPrimalNamekian(ServerPlayer player)
    {
        return raiseSuperform(player, RACE_NAMEKIAN, PRIMAL_NAMEKIAN_RUNG);
    }

    // Only ever RAISES the shared superforms skill, and only for the expected race (the skill is shared across races,
    // so touching it for the wrong race would hand that race its own top form for free).
    private static boolean raiseSuperform(ServerPlayer player, String expectedRace, int rung)
    {
        StatsData stats = DmzBridge.stats(player);
        if (stats == null)
            return false;
        try
        {
            Character c = stats.getCharacter();
            if (c == null || !expectedRace.equalsIgnoreCase(c.getRaceName()))
                return false;
            Skills skills = stats.getSkills();
            if (!skills.hasSkill(SKILL_SUPERFORMS))
                return false;
            if (skills.getSkillLevel(SKILL_SUPERFORMS) < rung)
            {
                skills.setSkillLevel(SKILL_SUPERFORMS, rung);
                resync(player);
            }
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[ritual] could not raise superform rung: {}", t.toString());
            return false;
        }
    }

    /**
     * Temporarily make Super Saiyan God available and active on a saiyan by raising the godforms skill to 1 and setting
     * the active form. Returns true when the temporary form was applied.
     *
     * <p>A ritual grant and a PURCHASE are the same state in DMZ. Buying the form ({@code UpdateSkillC2S.PURCHASE},
     * 150000 TP on the live configs) does exactly what this method does, it sets the {@code godforms} skill to 1, and
     * DMZ records nothing about which of the two happened. So the only thing that can tell them apart later is a
     * marker written HERE, and only when the ritual is what raised the skill from 0:
     * {@link WishRitualStore#SSG_TEMP_GRANT}. A player who already holds the skill keeps it untouched and gets no
     * marker, which is what stops {@link #endTempSsg} from taking a real purchase away.
     *
     * <p>The right to buy (the durable entitlement the ritual also earns) is separate again and lives in SU perms
     * ({@link WishRitualStore#SSG_PURCHASE}), so nothing here can remove what the player earned.
     */
    static boolean beginTempSsg(ServerPlayer player)
    {
        StatsData stats = DmzBridge.stats(player);
        if (stats == null)
            return false;
        try
        {
            Character c = stats.getCharacter();
            // SSG temporary form on the ritual centre: half saiyans share the god line (godforms is seeded and
            // priced for half_saiyan), so they may centre the ring and turn Super Saiyan God.
            if (c == null || !isSsgRaceName(c.getRaceName()))
                return false;
            Skills skills = stats.getSkills();
            if (!skills.hasSkill(SKILL_GODFORMS))
                return false;
            if (skills.getSkillLevel(SKILL_GODFORMS) < 1)
            {
                // The ritual is what is putting the skill there, so it is ours to take back later. Marked BEFORE the
                // skill goes up so a crash between the two lines leaves a marker with no grant (harmless, reconciled
                // at login) rather than a grant with no marker (a free permanent form).
                WishRitualStore.markSsgTempGrant(player);
                skills.setSkillLevel(SKILL_GODFORMS, 1);
            }
            // Never assume the group is called "godforms": live shards have drifted (a renamed godforms_copy), and
            // pointing the character at a group that does not exist strands it in an unresolvable form.
            c.setActiveForm(resolveSsgGroup(c.getRaceName()), FORM_SSG);
            resync(player);
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[ritual] could not begin temp SSG: {}", t.toString());
            return false;
        }
    }

    /** True while the player is currently transformed into Super Saiyan God (used to detect detransform). */
    static boolean isInSsg(ServerPlayer player)
    {
        StatsData stats = DmzBridge.stats(player);
        if (stats == null)
            return false;
        try
        {
            Character c = stats.getCharacter();
            return c != null && FORM_SSG.equalsIgnoreCase(c.getActiveForm());
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * Revert the temporary SSG grant: clear the active form if the character is still standing in a god form, and lower
     * the godforms skill back to 0 so the form is no longer available until it is bought.
     *
     * <p><b>Only what the ritual itself gave is revoked.</b> This method used to drop the skill whenever it was above
     * 0, which destroyed the form for anyone who had PAID the 150000 TP for it and then helped with a charge ritual:
     * a purchase and a ritual grant are byte for byte the same state in DMZ ({@code godforms} at level 1), so "the
     * skill is up" was never evidence that the ritual is what put it there. {@link #beginTempSsg} now marks the player
     * when IT raises the skill from 0, and no marker means the form is the player's own property: we return at once
     * and touch nothing.
     *
     * <p>The durable purchase entitlement lives in SU perms and is untouched either way. Safe to call for an
     * offline-then-reconnected player or one who already detransformed.
     */
    static void endTempSsg(ServerPlayer player)
    {
        StatsData stats = DmzBridge.stats(player);
        if (stats == null)
            return;
        try
        {
            // The whole safety of this method: no marker, no revoke. A player who bought Super Saiyan God, or who
            // reached it by any route other than a ritual grant, never gets here past this line.
            if (!WishRitualStore.hasSsgTempGrant(player))
                return;

            Character c = stats.getCharacter();
            // Clear on the whole god form, not just the name "supersaiyangod". Dropping the skill while the character
            // still points at a god form strands the player: DMZ then fails to resolve their active form and warns
            // about it EVERY TICK, for ever. A live server logged that warning 122,860 times in one 2h43m session for
            // a single stranded player, which is what made the whole log useless and put a needless string format and
            // a disk write on the server thread twenty times a second.
            if (inGodFormsSkill(c))
                c.clearActiveForm(player);
            Skills skills = stats.getSkills();
            // Only give the skill back up if we know the character is no longer standing in a god form. If the clear
            // did not happen (or did not take), leaving the skill alone keeps the form RESOLVABLE, which is a free
            // temporary form at worst; taking it away would be the unresolvable state above.
            boolean safeToLower = !inGodFormsSkill(c);
            if (safeToLower && skills.hasSkill(SKILL_GODFORMS) && skills.getSkillLevel(SKILL_GODFORMS) > 0)
            {
                skills.setSkillLevel(SKILL_GODFORMS, 0);
                // Only once the grant is actually gone. A marker kept past a declined revoke means the next attempt
                // still knows the form was ours to take.
                WishRitualStore.clearSsgTempGrant(player);
            }
            resync(player);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[ritual] could not end temp SSG: {}", t.toString());
        }
    }

    /**
     * Drop a temporary-grant marker whose grant is already gone, so it can never be spent on a later purchase.
     *
     * <p>The marker says "the godforms skill on this character is ours to take back". If the skill reaches 0 by some
     * other route while the marker is still set (a DMZ character reset wipes {@code StatsData}, an admin runs
     * {@code /dmzskills}), the player is then free to BUY Super Saiyan God, and a stale marker would let the next
     * ritual revoke that purchase: exactly the bug this whole change removes, one step further along. Login is the
     * cheap, certain moment to check, and the check is one-way: a marker is only ever dropped when there is nothing
     * left for it to describe, never written here.
     *
     * @return true when a stale marker was cleared
     */
    static boolean reconcileTempSsgGrant(ServerPlayer player)
    {
        if (!WishRitualStore.hasSsgTempGrant(player))
            return false;
        StatsData stats = DmzBridge.stats(player);
        if (stats == null)
            return false;
        try
        {
            Skills skills = stats.getSkills();
            if (skills.hasSkill(SKILL_GODFORMS) && skills.getSkillLevel(SKILL_GODFORMS) > 0)
                return false; // the grant is still standing, so the marker still describes something real
            WishRitualStore.clearSsgTempGrant(player);
            LoggingHandler.sulog.debug("[ritual] dropped a stale temporary SSG marker for {} (the godforms skill is "
                    + "already back at 0)", player.getGameProfile().getName());
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[ritual] could not reconcile the temporary SSG marker: {}", t.toString());
            return false;
        }
    }

    /**
     * True when the character's active form is served by the {@code godforms} SKILL, whatever its group happens to be
     * called.
     *
     * <p>Asking "is the active group named godforms" is what let a copied group leak a permanent free form. The form
     * editor's paste used to produce a second group (godforms_copy) carrying the same {@code supersaiyangod} on the
     * same {@code formType}, so a player who transformed through the copy was in a group this cleanup did not
     * recognise and was never reverted. The group NAME is decoration; the {@code formType} is what DMZ gates on, so
     * that is what we ask about.
     *
     * <p>The mapping is DMZ's own {@link TransformationsHelper#getSkillNameForType(String)}, which is substring based:
     * any type CONTAINING {@code godform} routes onto the stock {@code godforms} skill, exactly as {@code androidform}
     * does (sdu's {@code TransformationsHelperMixin} excepts registered custom types, which is correct here too, since
     * such a type is gated by its own skill and the godforms skill is none of its business).
     */
    private static boolean inGodFormsSkill(Character c)
    {
        if (c == null)
            return false;
        String group = c.getActiveFormGroup();
        String form = c.getActiveForm();
        if (group == null || group.isBlank() || form == null || form.isBlank())
            return false;
        try
        {
            String race = c.getRaceName();
            FormConfig cfg = race == null || race.isBlank() ? null : ConfigManager.getFormGroup(race, group);
            if (cfg != null && cfg.getFormType() != null)
                return SKILL_GODFORMS.equalsIgnoreCase(TransformationsHelper.getSkillNameForType(cfg.getFormType()));
        }
        catch (Throwable ignored)
        {
        }
        // The group did not resolve (config drift, a load-order hiccup): fall back to the two names we do know.
        return FORM_SSG.equalsIgnoreCase(form) || GROUP_GODFORMS.equalsIgnoreCase(group);
    }

    /**
     * The group name to transform a member of {@code race} into Super Saiyan God with: the group of theirs that both
     * routes to the {@code godforms} skill and actually holds the form. The stock {@code godforms} wins when it is
     * present, so nothing changes on an ordinary install; a shard whose file was renamed gets a resolvable form
     * instead of the stranded state. Falls back to the stock name when nothing resolves.
     */
    private static String resolveSsgGroup(String race)
    {
        try
        {
            if (race == null || race.isBlank())
                return GROUP_GODFORMS;
            FormConfig stock = ConfigManager.getFormGroup(race, GROUP_GODFORMS);
            if (holdsSsg(stock))
                return GROUP_GODFORMS;
            for (java.util.Map.Entry<String, FormConfig> e : ConfigManager.getAllFormsForRace(race).entrySet())
            {
                FormConfig cfg = e.getValue();
                if (cfg == null || cfg.getFormType() == null || !holdsSsg(cfg))
                    continue;
                if (SKILL_GODFORMS.equalsIgnoreCase(TransformationsHelper.getSkillNameForType(cfg.getFormType())))
                    return e.getKey();
            }
        }
        catch (Throwable ignored)
        {
        }
        return GROUP_GODFORMS;
    }

    // Whether a group holds the Super Saiyan God form, by display name or by map key (the pair DMZ's own resolver uses).
    private static boolean holdsSsg(FormConfig cfg)
    {
        return cfg != null && (cfg.getForm(FORM_SSG) != null || cfg.getFormByKey(FORM_SSG) != null);
    }

    /**
     * Repair a player who is already stranded in an unresolvable active form, in ANY form group.
     *
     * <p>The state is: the character's active form is in some group but DMZ cannot resolve the active form NAME in that
     * group, so {@code TransformStatusHandler} logs a warning on every one of its effect ticks. Once a player is in it
     * nothing clears it by itself, not relogging, not dying, so it persists across restarts and grows the log by roughly
     * a megabyte an hour per stranded player. A live server logged 122,860 of these warnings in one session, and
     * separately one player (active form {@code ssg}, a name present in NO form JSON anywhere) drove 9,862 lines in 8
     * minutes, 78% of one shard's whole log, all on the server thread; the spam then moved to a second account carrying
     * the same stale {@code ssg}, confirming it is orphaned data on several characters rather than one bad login.
     *
     * <p>We key on RESOLVABILITY, not on the skill level. The original version returned early whenever the group's skill
     * was above 0, on the assumption that a non-zero skill means the form resolves. That assumption is FALSE when the
     * active form name exists nowhere in the group: no skill level can make {@code ssg} resolve when the only god form is
     * {@code supersaiyangod}, so the old early return declined the heal and left the player warning for ever. The fix is
     * to ask DMZ the exact question its effect tick asks (see below) and clear only when that question answers "no".
     *
     * <p>The resolver we call is the SAME one the warn site uses, so it is authoritative by construction:
     * {@code TransformStatusHandler.resolveRegularFormData} does {@link ConfigManager#getFormGroup(String, String)} for
     * the character's race and the active group, then {@link FormConfig#getForm(String)} (match by display name,
     * case-insensitive) with a {@link FormConfig#getFormByKey(String)} fallback (match by map key). It warns iff both of
     * those come back null. We reproduce that pair exactly. We do NOT use {@code ConfigManager.getForm(race,group,form)}:
     * that convenience skips the {@code getFormByKey} fallback, so it would report unresolvable for a key-only match that
     * the tick actually resolves, and we could wrongly clear a legitimate form.
     *
     * <p>Group-agnostic on purpose: any group can drift, not just {@code godforms}. Live shards showed per-shard form
     * config drift (a saiyan {@code godforms.json} renamed to {@code godforms_copy.json} on one shard, a stray
     * {@code godforms_copy} group on others), so a stranded player is NOT guaranteed to sit in the {@code godforms}
     * group. The resolvability question is identical for every group, so we read the character's OWN active group rather
     * than assume one.
     *
     * <p>The distinction that keeps this safe: a group that does not RESOLVE is declined, a group that resolves but whose
     * form does not is cleared. A missing group can be transient config drift or a load-order hiccup, and clearing a
     * legitimate form because a file is briefly absent would be worse than the spam. Only a group that resolves while its
     * form does not is PROVABLY unresolvable. Safe-direction bias otherwise unchanged: any throwable, any null, the form
     * resolving, or the group not resolving all mean leave the player alone. A missed heal is a noisy log; a wrongly
     * cleared form is a player losing a transformation they earned.
     *
     * <p>Called on login, the one moment the player is certainly loaded and certainly not mid-ritual.
     *
     * @return true when a stranded form was cleared
     */
    static boolean healStrandedForm(ServerPlayer player)
    {
        StatsData stats = DmzBridge.stats(player);
        if (stats == null)
            return false;
        try
        {
            Character c = stats.getCharacter();
            if (c == null)
                return false;

            // Read the character's OWN active group, do not assume godforms. Drift can strand a player in godforms_copy
            // or any other renamed group, and the resolvability question is the same for all of them.
            String activeGroup = c.getActiveFormGroup();
            if (activeGroup == null || activeGroup.isBlank())
                return false;

            // Only a non-empty, non-base name can strand and spam: DMZ's tick treats null/empty/"base" as "no form" and
            // does not warn for it, so matching its warn condition here keeps us from touching a state that is not
            // actually producing the spam.
            String activeForm = c.getActiveForm();
            if (activeForm == null || activeForm.isBlank() || "base".equalsIgnoreCase(activeForm))
                return false;

            // Ask DMZ the SAME question its effect tick asks. getFormGroup is keyed on the character's race, exactly as
            // the warn site passes it. The form resolves iff getForm (by display name) OR getFormByKey (by map key)
            // returns non-null; both null is precisely the condition that warns every tick.
            FormConfig group = ConfigManager.getFormGroup(c.getRaceName(), activeGroup);
            // A group that does NOT resolve is declined, not cleared. This is the important half: a missing group can be
            // transient config drift or a load-order problem, and clearing a legitimate form because a file is briefly
            // absent would be worse than the spam. Only a group that resolves while its form does not is provably stale.
            if (group == null)
                return false;
            boolean resolvable = group.getForm(activeForm) != null || group.getFormByKey(activeForm) != null;
            if (resolvable)
                return false; // a real, purchased form: none of our business

            c.clearActiveForm(player);
            resync(player);
            // Name the actual group AND form that failed. The original message hardcoded FORM_SSG ("supersaiyangod"),
            // which would have printed the WRONG name (the live strand was "ssg"); logging the real names is what told
            // us "ssg" was the culprit.
            LoggingHandler.sulog.info("[ritual] cleared a stranded form '{}' in group '{}' for {} (DMZ could not resolve "
                    + "it at any skill level, so it was warning every tick).",
                    activeForm, activeGroup, player.getGameProfile().getName());
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[ritual] could not heal stranded form: {}", t.toString());
            return false;
        }
    }

    /**
     * Drive DMZ's own potara-fusion pose animation on the player for {@code ticks} server ticks. DMZ's client
     * {@code PlayerGeoAnimatableMixin} plays {@code FUSION_POTHALA_RIGHT} whenever the synced
     * {@code Status.potaraPoseTimer > 0} (it falls back to the right-side pose when no player partner resolves, which is
     * the case here since the partner is Shenron, not a player). Setting the timer server-side and syncing the whole
     * StatsData (which serialises Status) is enough; the Ragnarok Key's {@code PotaraPoseManager} sends
     * the closing zero when the window ends.
     */
    static boolean playPotaraPose(ServerPlayer player, int ticks)
    {
        return setPotaraTimer(player, Math.max(1, ticks));
    }

    /** Set the raw potara pose timer and resync. Used to open (ticks>0) and close (0) the pose window. */
    static boolean setPotaraTimer(ServerPlayer player, int ticks)
    {
        StatsData stats = DmzBridge.stats(player);
        if (stats == null)
            return false;
        try
        {
            Status st = stats.getStatus();
            st.setPotaraPoseTimer(Math.max(0, ticks));
            resync(player);
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[ritual] could not set potara pose timer: {}", t.toString());
            return false;
        }
    }

    /**
     * Remove points RANDOMLY from the player's six core stats, one at a time, until the DMZ level (derived live from
     * total stats) has dropped by {@code levelsToRemove}, never taking any stat below zero and never dropping the level
     * below 1. Applied ONCE, server-side, only after a form grant has already succeeded. Returns the number of levels
     * actually removed (may be fewer than requested if the player runs out of points first).
     */
    static int applyLevelCost(ServerPlayer player, int levelsToRemove)
    {
        StatsData stats = DmzBridge.stats(player);
        if (stats == null || levelsToRemove <= 0)
            return 0;
        try
        {
            Stats s = stats.getStats();
            int startLevel = stats.getLevel();
            int target = Math.max(1, startLevel - levelsToRemove);
            RandomSource rnd = player.getRandom();

            // Bound the loop by the total points on hand, plus a small margin, so it always terminates.
            int budget = s.getTotalStats() + 16;
            while (stats.getLevel() > target && budget-- > 0)
            {
                int idx = pickNonZeroStat(s, rnd);
                if (idx < 0)
                    break; // every stat is already at zero: cannot remove more without going negative
                s.removeStat(STAT_KEYS_LOWER[idx], 1);
            }
            resync(player);
            return startLevel - stats.getLevel();
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[ritual] level-cost application failed for {}: {}",
                    player.getGameProfile().getName(), t.toString());
            return 0;
        }
    }

    // Choose a uniformly random stat index whose base allocated value is > 0, or -1 when all six are zero. Reads the
    // base points directly (the same values getTotalStats sums and getLevel derives from), so removing one point always
    // lowers the total by one and never underflows a stat.
    private static int pickNonZeroStat(Stats s, RandomSource rnd)
    {
        int[] available = new int[STAT_KEYS_LOWER.length];
        int n = 0;
        for (int i = 0; i < STAT_KEYS_LOWER.length; i++)
        {
            if (baseStat(s, i) > 0)
                available[n++] = i;
        }
        if (n == 0)
            return -1;
        return available[rnd.nextInt(n)];
    }

    // Base allocated value of the i-th core stat, in the same order as STAT_KEYS_LOWER (str, skp, res, vit, pwr, ene).
    private static int baseStat(Stats s, int i)
    {
        try
        {
            switch (i)
            {
                case 0: return s.getStrength();
                case 1: return s.getStrikePower();
                case 2: return s.getResistance();
                case 3: return s.getVitality();
                case 4: return s.getKiPower();
                case 5: return s.getEnergy();
                default: return 0;
            }
        }
        catch (Throwable t)
        {
            return 0;
        }
    }

    private static void resync(ServerPlayer player)
    {
        try
        {
            NetworkHandler.sendToTrackingEntityAndSelf(new StatsSyncS2C(player), player);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[ritual] resync failed for {}: {}",
                    player.getGameProfile().getName(), t.toString());
        }
    }

    static String normalizeRace(String race)
    {
        return race == null ? null : race.toLowerCase(Locale.ROOT);
    }
}
