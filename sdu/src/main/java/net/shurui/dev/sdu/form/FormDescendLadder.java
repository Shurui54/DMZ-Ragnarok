package net.shurui.dev.sdu.form;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.util.TransformationsHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Works out which form a character should drop into when they descend one step.
 *
 * <h2>Why this exists</h2>
 *
 * <p>DMZ's {@code TransformationsHelper.getPreviousForm} answers that question by walking the group's
 * {@code forms} map and returning whatever entry sits immediately BEFORE the active one. That is the raw JSON
 * key order of the group file, which DMZ only ever writes in ladder order itself, so the walk is right for a
 * stock config and wrong for any file whose keys were reordered. When the order is not the ladder, the entry
 * before the active form can be a HIGHER form, and DMZ then sets it as the active form with no skill, mastery
 * or level check at all, which is how a descend puts a player into a form they never bought.
 *
 * <p>So the step down is computed from the CONTENT instead of from the file layout: rank every form in the
 * group by {@code unlockOnSkillLevel}, break ties by name, and take the highest rank that is strictly below the
 * active form and that the character may actually be in. Nothing is found means there is no lower rung, and
 * returning null is exactly what DMZ needs to revert to base.
 *
 * <h2>Why "may actually be in" and not just "is lower"</h2>
 *
 * <p>A descend must never be a way into content: it is the one transformation path DMZ does not gate. Every
 * candidate is therefore filtered through {@link TransformationsHelper#getUnlockedForms} (which carries DMZ's
 * own skill level, mastery requisite, android and oozaru tail rules) and through {@link FormLevelGate}, so a
 * rung the character cannot enter by transforming is not a rung they can fall onto either. Dropping further
 * than one step, or all the way to base, is always the safe direction.
 *
 * <p>Everything fails OPEN: any unexpected DMZ shape throws out to the caller's guard, which leaves DMZ's own
 * behaviour in place rather than trapping a player in a form.
 */
public final class FormDescendLadder {

    private FormDescendLadder() {
    }

    /**
     * The form one rung below the character's active form, or null when there is none (revert to base).
     *
     * @param data the character's stats, as DMZ hands them to {@code getPreviousForm}
     */
    public static FormConfig.FormData previous(StatsData data) {
        if (data == null || data.getCharacter() == null) {
            return null;
        }
        String race = data.getCharacter().getRaceName();
        String group = data.getCharacter().getActiveFormGroup();
        String current = data.getCharacter().getActiveForm();
        if (group == null || group.isEmpty() || current == null || current.isEmpty()) {
            return null;
        }
        FormConfig config = ConfigManager.getFormGroup(race, group);
        if (config == null) {
            return null;
        }
        Map<String, FormConfig.FormData> forms = config.getForms();
        if (forms == null || forms.isEmpty()) {
            return null;
        }

        List<FormConfig.FormData> all = new ArrayList<>(forms.values());
        FormConfig.FormData activeData = null;
        for (FormConfig.FormData f : all) {
            if (f != null && f.getName() != null && f.getName().equalsIgnoreCase(current)) {
                activeData = f;
                break;
            }
        }
        if (activeData == null) {
            // The active form is not in this group at all. Nothing sane to step to, so let the caller revert.
            return null;
        }
        int activeLevel = level(activeData);
        String activeKey = key(activeData);

        Set<String> enterable = new HashSet<>();
        for (FormConfig.FormData f : TransformationsHelper.getUnlockedForms(data, race, group)) {
            if (f != null && f.getName() != null) {
                enterable.add(f.getName().toLowerCase(Locale.ROOT));
            }
        }

        FormConfig.FormData best = null;
        int bestLevel = Integer.MIN_VALUE;
        String bestKey = null;
        for (FormConfig.FormData f : all) {
            if (f == null || f.getName() == null || f == activeData) {
                continue;
            }
            int lvl = level(f);
            String k = key(f);
            if (!below(lvl, k, activeLevel, activeKey)) {
                continue;
            }
            if (!enterable.contains(k)) {
                continue;
            }
            if (FormLevelGate.blocks(data, group, f.getName())) {
                continue;
            }
            if (best == null || below(bestLevel, bestKey, lvl, k)) {
                best = f;
                bestLevel = lvl;
                bestKey = k;
            }
        }
        return best;
    }

    /** Ladder rank comparison: level first, then name, which is the order a group file is written in. */
    private static boolean below(int level, String key, int otherLevel, String otherKey) {
        if (level != otherLevel) {
            return level < otherLevel;
        }
        return key.compareTo(otherKey) < 0;
    }

    private static int level(FormConfig.FormData form) {
        Integer lvl = form.getUnlockOnSkillLevel();
        return lvl == null ? 0 : lvl;
    }

    private static String key(FormConfig.FormData form) {
        return form.getName().toLowerCase(Locale.ROOT);
    }
}
