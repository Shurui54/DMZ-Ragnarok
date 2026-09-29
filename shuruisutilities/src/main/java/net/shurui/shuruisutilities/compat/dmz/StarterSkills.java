package net.shurui.shuruisutilities.compat.dmz;

import java.util.Locale;
import java.util.Set;

import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.skills.Skills;

import net.shurui.dev.sdu.api.key.CoreGateHooks;

/**
 * Gives a newly created Spiritualist or Cleric the Ki Control skill, so those two classes begin able to use ki
 * rather than having to buy the prerequisite for their own class fantasy.
 *
 * <h2>What Ki Control is and why these two</h2>
 *
 * <p>{@code kicontrol} is DMZ's gate skill for everything ki: without it a player cannot use techniques ("You need
 * the Ki Control skill to use techniques"), cannot fire a ki blast, and cannot use ki-based actions at all.
 * {@code KiBlastC2S} tests it by presence ({@code hasSkill}), {@code TechniqueChargeC2S} by level
 * ({@code getSkillLevel > 0}), so level 1 satisfies both. It is a single-level skill costing 3000 TP in
 * {@code config/dragonminez/skills.json}. DMZ grants no skills at character creation for any class, so every class
 * starts unable to do any of that. For a Spiritualist and a Cleric, whose whole identity is ki, that start is
 * backwards, which is what this fixes. Every other class is untouched.
 *
 * <p>Flight is deliberately NOT included. It is a separate skill, {@code fly}, read by DMZ's
 * {@code FlyStatusHandler}; {@code kicontrol} does not grant it. A new Spiritualist or Cleric can use ki and
 * techniques at once but still buys their way off the ground like everyone else.
 *
 * <h2>Key gate</h2>
 *
 * <p>Guarded on {@link CoreGateHooks.Impl#starterSkills()}, which only the Ragnarok Key installs (keyless default:
 * false) and which answers the suite's standard full-key predicate, {@code RagnarokKey.unlocked()}: the Ragnarok Key
 * installed its hooks. There is no singleplayer or LAN exemption; that is the same rule every other full-key feature
 * uses.
 *
 * <h2>Creation only</h2>
 *
 * <p>This runs where the character is committed, so it affects new characters. An existing Spiritualist or Cleric
 * who already bought Ki Control keeps it (we never lower a level), and one who has not is left alone rather than
 * being handed a 3000 TP skill retroactively on their next login.
 */
public final class StarterSkills
{
    private StarterSkills()
    {
    }

    /** DMZ's gate skill for flight, techniques and every other ki action. */
    public static final String KI_CONTROL = "kicontrol";

    /**
     * The classes that begin with {@link #KI_CONTROL}. Lowercase ids as DMZ stores them (the full default set is
     * warrior, martialartist, spiritualist, berserker, paladin, tank, cleric).
     */
    private static final Set<String> KI_CONTROL_AT_START = Set.of("spiritualist", "cleric");

    /**
     * Grant the starting skill if this freshly initialised character qualifies. Safe to call more than once and on
     * any class: it does nothing unless the class is one of ours, the key gate passes, and the skill is missing.
     */
    public static void applyTo(StatsData data)
    {
        if (data == null || !CoreGateHooks.get().starterSkills())
        {
            return;
        }
        String characterClass = data.getCharacter() != null ? data.getCharacter().getCharacterClass() : null;
        if (characterClass == null || !KI_CONTROL_AT_START.contains(characterClass.toLowerCase(Locale.ROOT)))
        {
            return;
        }
        Skills skills = data.getSkills();
        if (skills == null || skills.getSkillLevel(KI_CONTROL) >= 1)
        {
            // never lower an existing level: a player who already bought it keeps exactly what they had
            return;
        }
        skills.setSkillLevel(KI_CONTROL, 1);
    }
}
