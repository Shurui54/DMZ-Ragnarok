package net.shurui.dev.sdu.form;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.Quest;
import com.dragonminez.common.quest.QuestRegistry;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.form.FormQuestGateConfig.Gate;

/**
 * Gate evaluation for quest-gated form purchases, shared by the server hook ({@code UpdateSkillC2SMixin})
 * and the advisory client UX ({@code SkillsMenuScreenMixin}). Reads the form -&gt; quest map from
 * {@link FormQuestGateConfig} and quest completion from {@link StatsData#getPlayerQuestData()}.
 *
 * <p>Everything fails OPEN (returns "not blocked") on any unexpected DMZ shape, so a mismatch never breaks
 * DMZ behaviour.
 */
public final class FormQuestGate {

    /** Lang key for the red chat message shown when a locked form is buy-attempted. Arg %s = quest title. */
    public static final String LOCKED_MESSAGE_KEY = "message.dmz_ragnarok.npc.form_quest_locked";

    private FormQuestGate() {
    }

    /** A {@code group.form} gate key resolved to its DMZ {@code (skillName, level)}. skillName is the
     * form-skill DMZ buys against ({@code getFormType()}, else group name); level is {@code getUnlockOnSkillLevel()}. */
    private record Resolved(String skillName, int level) {
    }

    /**
     * Resolve a {@code group.form} key to {@code (skillName, level)}, or null (unknown, missing level, DMZ
     * shape mismatch) so callers fail OPEN. Looks up the group in {@code race}'s per-race form map ONLY, so a
     * saiyan gate can't leak onto a namekian sharing the group/tier; stack forms are raceless, matched globally.
     */
    private static Resolved resolve(String race, String formKey) {
        if (formKey == null) {
            return null;
        }
        int dot = formKey.indexOf('.');
        if (dot <= 0 || dot >= formKey.length() - 1) {
            return null; // no group.form shape (a bare form id can't be resolved)
        }
        String group = formKey.substring(0, dot);
        String formId = formKey.substring(dot + 1);
        try {
            FormConfig fc = findGroup(race, group);
            if (fc == null) {
                return null;
            }
            String skill = fc.getFormType();
            if (skill == null || skill.isBlank()) {
                skill = fc.getGroupName();
            }
            if (skill == null || skill.isBlank()) {
                return null;
            }
            FormConfig.FormData form = fc.getFormByKey(formId);
            if (form == null) {
                return null;
            }
            Integer lvl = form.getUnlockOnSkillLevel();
            if (lvl == null) {
                return null;
            }
            return new Resolved(skill, lvl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The loaded {@link FormConfig} named {@code group}, scoped to {@code race} (so races sharing a group
     * name don't cross-leak gates). Stack forms are raceless, always scanned globally. */
    private static FormConfig findGroup(String race, String group) {
        if (group == null || group.isBlank()) {
            return null;
        }
        try {
            if (race != null && !race.isBlank()) {
                FormConfig fc = ConfigManager.getFormGroup(race, group);
                if (fc != null && group.equals(fc.getGroupName())) {
                    return fc;
                }
            }
            var stack = ConfigManager.getAllStackForms();
            if (stack != null) {
                for (FormConfig fc : stack.values()) {
                    if (fc != null && group.equals(fc.getGroupName())) {
                        return fc;
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall through to null (fail open)
        }
        return null;
    }

    /** Quest ids already warned about as dead, so the fail-open warning is logged once per id, not every tick. */
    private static final java.util.Set<String> WARNED_DEAD = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** True when {@code questId} still resolves in either the server-loaded or the client-synced quest table. A
     * quest id that resolves in neither has been deleted (or renamed) and can never be completed. */
    private static boolean questExists(String questId) {
        if (questId == null || questId.isBlank()) {
            return false;
        }
        try {
            if (QuestRegistry.getQuest(questId) != null) {
                return true;
            }
        } catch (Throwable ignored) {
            // fall through to the client table
        }
        try {
            return QuestRegistry.getClientQuest(questId) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True when this player has NOT yet completed the quest that {@code gate} names. Fails open (false), and a
     * gate whose quest id no longer resolves is treated as NOT blocking (a dead reference can never be
     * completed, so it would otherwise lock the form forever): fail open and warn once. */
    private static boolean questIncomplete(StatsData data, Gate gate) {
        try {
            if (gate == null || !gate.isValid()) {
                return false;
            }
            if (!questExists(gate.questId)) {
                if (WARNED_DEAD.add(gate.questId)) {
                    DmzNpc.LOGGER.warn("[{}] Form-quest gate points at quest '{}', which no longer resolves; "
                            + "treating the gate as open. Prune it from form_quest_gates.json.", DmzNpc.MODID, gate.questId);
                }
                return false; // dead reference: fail open so the form is not locked forever
            }
            PlayerQuestData quests = data.getPlayerQuestData();
            if (quests == null) {
                return false; // no quest data: don't block
            }
            return !quests.isQuestCompleted(gate.questId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Block the level-1 buy of {@code skillName} when a gate resolves to it, its quest is incomplete, and
     * it's unowned. Fails open. */
    public static boolean blocksInitialBuy(StatsData data, String skillName) {
        return blocksLevel(data, skillName, 1);
    }

    /** Block buying {@code skillName} up to {@code targetLevel} on the same conditions. Fails open. */
    public static boolean blocksUpgradeToLevel(StatsData data, String skillName, int targetLevel) {
        return blocksLevel(data, skillName, targetLevel);
    }

    private static boolean blocksLevel(StatsData data, String skillName, int targetLevel) {
        if (data == null || skillName == null || skillName.isEmpty() || targetLevel < 1) {
            return false;
        }
        try {
            String race = (data.getCharacter() == null) ? "" : data.getCharacter().getRaceName();
            for (var e : FormQuestGateConfig.all().entrySet()) {
                Gate gate = e.getValue();
                if (gate == null || !gate.isValid()) {
                    continue;
                }
                Resolved r = resolve(race, e.getKey());
                if (r == null || !skillName.equals(r.skillName()) || r.level() != targetLevel) {
                    continue;
                }
                if (!questIncomplete(data, gate)) {
                    continue;
                }
                if (data.getSkills().getSkillLevel(skillName) < targetLevel) {
                    return true; // gated, quest incomplete, not yet owned at this level
                }
            }
        } catch (Throwable t) {
            return false; // fail open
        }
        return false;
    }

    // the gate blocking skillName at targetLevel (incomplete quest, unowned), or null. fails to null.
    private static Gate blockingGate(StatsData data, String skillName, int targetLevel) {
        if (data == null || skillName == null || skillName.isEmpty() || targetLevel < 1) {
            return null;
        }
        try {
            String race = (data.getCharacter() == null) ? "" : data.getCharacter().getRaceName();
            for (var e : FormQuestGateConfig.all().entrySet()) {
                Gate gate = e.getValue();
                if (gate == null || !gate.isValid()) {
                    continue;
                }
                Resolved r = resolve(race, e.getKey());
                if (r == null || !skillName.equals(r.skillName()) || r.level() != targetLevel) {
                    continue;
                }
                if (questIncomplete(data, gate) && data.getSkills().getSkillLevel(skillName) < targetLevel) {
                    return gate;
                }
            }
        } catch (Throwable ignored) {
            // fall through to null
        }
        return null;
    }

    // client helper for the skills tooltip: quest title gating skillName at targetLevel, or null. fails to null.
    public static Component blockingQuestTitle(StatsData data, String skillName, int targetLevel) {
        Gate gate = blockingGate(data, skillName, targetLevel);
        return (gate == null || !gate.isValid()) ? null : questTitle(gate.questId);
    }

    // raw quest id gating skillName at targetLevel, or null. server hook logs it when it blocks a buy.
    public static String blockingQuestId(StatsData data, String skillName, int targetLevel) {
        Gate gate = blockingGate(data, skillName, targetLevel);
        return (gate == null || !gate.isValid()) ? null : gate.questId;
    }

    /** Send the blocked player a red "locked until you complete quest X" message. Server-side only, no-op
     * otherwise. */
    public static void notifyLocked(StatsData data, String skillName, int targetLevel) {
        try {
            Gate gate = blockingGate(data, skillName, targetLevel);
            if (gate == null || !gate.isValid()) {
                return;
            }
            Player player = data.getPlayer();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            Component title = questTitle(gate.questId);
            serverPlayer.sendSystemMessage(
                    Component.translatable(LOCKED_MESSAGE_KEY, title).withStyle(ChatFormatting.RED));
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not send form-quest-locked message: {}", DmzNpc.MODID, t.toString());
        }
    }

    /** Reverse lookup for the saga GUI: the form-skill names gated by {@code questId}, in config order.
     * Client-safe: reads the synced {@link FormQuestGateConfig} map only. */
    public static java.util.List<String> formsGatedByQuest(String questId) {
        java.util.List<String> forms = new java.util.ArrayList<>();
        if (questId == null || questId.isBlank()) {
            return forms;
        }
        try {
            for (java.util.Map.Entry<String, Gate> e : FormQuestGateConfig.all().entrySet()) {
                Gate gate = e.getValue();
                if (gate != null && gate.isValid() && questId.equals(gate.questId)) {
                    forms.add(e.getKey());
                }
            }
        } catch (Throwable ignored) {
            // fail closed to "no forms"; a display-only helper must never break the GUI
        }
        return forms;
    }

    /** Display name of a form-skill via the {@code skill.dragonminez.<name>} key (as DMZ's
     * {@code SkillReward.getDescription}), falling back to a prettified name when the key is missing. */
    public static Component formDisplayName(String formSkill) {
        if (formSkill == null || formSkill.isBlank()) {
            return Component.literal("?");
        }
        String key = "skill.dragonminez." + formSkill;
        try {
            if (net.minecraft.client.resources.language.I18n.exists(key)) {
                return Component.translatable(key);
            }
        } catch (Throwable ignored) {
            // I18n is client-only; fall through to the prettified literal
        }
        return Component.literal(prettify(formSkill));
    }

    /** super_saiyan -> "Super Saiyan". */
    private static String prettify(String raw) {
        String[] parts = raw.replace('_', ' ').trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? raw : sb.toString();
    }

    /** Quest display title (its lang key, resolved client-side), or the raw id. */
    public static Component questTitle(String questId) {
        if (questId == null || questId.isBlank()) {
            return Component.literal("?");
        }
        try {
            Quest quest = QuestRegistry.getQuest(questId);
            if (quest != null && quest.getTitle() != null && !quest.getTitle().isBlank()) {
                return Component.translatable(quest.getTitle());
            }
        } catch (Throwable ignored) {
            // fall through to the raw id
        }
        return Component.literal(questId);
    }
}
