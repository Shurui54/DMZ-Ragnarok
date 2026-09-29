package net.shurui.dev.sdu.client;

import net.shurui.dev.sdu.DmzNpc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * All valid DragonMine Z skill ids, read from DMZ's synced {@code SkillsConfig}. Every id here is a
 * real skill the config defines, so a quest SKILL reward/objective always names something DMZ knows.
 * Sourced from the UNION of the config's {@code skills} map plus its {@code kiSkills}/{@code formSkills}/
 * {@code stackSkills} lists - on a dedicated server the client sometimes only receives the lists, so
 * unioning guarantees ki attacks (kamehameha, galick_gun, …), form skills and stack skills are never
 * dropped from the picker. Guarded: a missing/absent config just yields an empty list.
 *
 * <p>NOTE for quest rewards: ki attacks are learnable skills DMZ ALSO treats as equippable
 * "techniques" (the reward editor grants those via a TECHNIQUE reward; see {@code DmzTechniques}). A
 * SKILL reward only sets a skill's level, so whether the attack becomes castable is up to DMZ's own
 * skill/technique unlock rules for the player's race/class.
 */
public final class DmzSkills {

    private DmzSkills() {
    }

    public static List<String> skillIds() {
        Set<String> ids = new LinkedHashSet<>();
        try {
            var config = com.dragonminez.common.config.ConfigManager.getSkillsConfig();
            if (config != null) {
                if (config.getSkills() != null) {
                    ids.addAll(config.getSkills().keySet());
                }
                addAll(ids, config.getKiSkills());
                addAll(ids, config.getFormSkills());
                addAll(ids, config.getStackSkills());
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not read DMZ skills: {}", DmzNpc.MODID, t.toString());
        }
        List<String> list = new ArrayList<>(ids);
        list.sort(Comparator.naturalOrder());
        return list;
    }

    private static void addAll(java.util.Collection<String> out, List<String> in) {
        if (in != null) {
            for (String s : in) {
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
    }
}
