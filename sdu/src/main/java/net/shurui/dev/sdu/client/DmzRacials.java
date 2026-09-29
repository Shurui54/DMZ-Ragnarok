package net.shurui.dev.sdu.client;

import net.shurui.dev.sdu.DmzNpc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The set of DragonMine Z <em>racial abilities</em> (a.k.a. racial skills) currently in use - the
 * value a race's {@code character.json} stores in {@code racialSkill}. Unlike {@link DmzSkills}
 * (which lists <em>every</em> ki/form skill), this is only the racial passives, so the race editor's
 * "Racial Ability" picker isn't drowned in unrelated skills.
 *
 * <p>Sourced live from the distinct {@code getRacialSkill()} of every loaded race character (so it
 * includes custom races/abilities added by this addon or others), unioned with DMZ's six built-in
 * racials as a fallback. Guarded: a missing/absent config just yields the built-in defaults.
 *
 * <p>NOTE: DMZ's actual racial <em>behaviour</em> (Saiyan Zenkai, Namekian Assimilation, …) is
 * hardcoded per built-in race in {@code RacialSkillLogic}; a brand-new racial ability id is therefore
 * display-only (name + description) and grants no built-in gameplay power.
 */
public final class DmzRacials {

    /** DMZ's built-in racial ability ids (from its lang keys {@code skill.dragonminez.racial_*}). */
    public static final List<String> DEFAULTS =
            List.of("human", "saiyan", "namekian", "frostdemon", "bioandroid", "majin");

    private DmzRacials() {
    }

    public static List<String> abilityIds() {
        Set<String> ids = new LinkedHashSet<>(DEFAULTS);
        try {
            var chars = com.dragonminez.common.config.ConfigManager.getAllRaceCharacters();
            if (chars != null) {
                for (var cfg : chars.values()) {
                    if (cfg == null) {
                        continue;
                    }
                    String racial = cfg.getRacialSkill();
                    if (racial != null && !racial.isBlank()) {
                        ids.add(racial.trim());
                    }
                }
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not read DMZ racial abilities: {}", DmzNpc.MODID, t.toString());
        }
        List<String> list = new ArrayList<>(ids);
        list.sort(Comparator.naturalOrder());
        return list;
    }
}
