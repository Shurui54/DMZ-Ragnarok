package net.shurui.dev.sdu.form;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.form.FormAlignmentGateConfig.Bounds;

/**
 * Server-side evaluation of the per-form alignment gate ({@link FormAlignmentGateConfig}). Answers two questions,
 * both against DMZ's {@code Resources.getAlignment()} (an int {@code 0..100}):
 *
 * <ul>
 *   <li>"may this character USE (transform into, or stay in) {@code group.form}", enforced by the transform mixins
 *       and the tick safety net ({@code FormAlignmentGateEnforcer}) exactly as the level gate is;</li>
 *   <li>"may this character UNLOCK (buy) the form whose skill is {@code skillName} at {@code targetLevel}", enforced
 *       in {@code UpdateSkillC2SMixin} at the same choke points the quest gate and {@code MixinDmzSsgPurchaseGate}
 *       use.</li>
 * </ul>
 *
 * <p>Everything fails OPEN: any unexpected DMZ shape returns "not blocked", so a mismatch never traps a player out
 * of a form or out of a purchase they should be allowed.
 */
public final class FormAlignmentGate {

    /** Lang key for the red "use" refusal. Args: %1$s = form name, %2$d = min, %3$d = max, %4$d = current. */
    public static final String USE_LOCKED_MESSAGE_KEY = "message.dmz_ragnarok.form.alignment_use_locked";
    /** Lang key for the red "unlock" refusal. Args: %1$s = form name, %2$d = min, %3$d = max, %4$d = current. */
    public static final String UNLOCK_LOCKED_MESSAGE_KEY = "message.dmz_ragnarok.form.alignment_unlock_locked";

    private FormAlignmentGate() {
    }

    /** This character's DMZ alignment (0..100), or -1 if it cannot be read (callers then fail open). */
    public static int alignmentOf(StatsData data) {
        try {
            if (data == null || data.getResources() == null) {
                return -1;
            }
            return data.getResources().getAlignment();
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---- USE gate (transform into / stay in) --------------------------------------------------------------

    /** True when a use-window is set for {@code group.form} and this character's alignment is outside it. Fails open. */
    public static boolean blocksUse(StatsData data, String group, String form) {
        if (data == null) {
            return false;
        }
        try {
            Bounds b = FormAlignmentGateConfig.get(group, form);
            if (b == null || !b.hasUseGate()) {
                return false;
            }
            int a = alignmentOf(data);
            if (a < 0) {
                return false; // could not read: fail open
            }
            return !b.allowsUse(a);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Send the blocked player the red "alignment X-Y required to use <form>" action-bar message. Server-side only. */
    public static void notifyUseBlocked(StatsData data, String group, String form, boolean stack) {
        try {
            if (data == null) {
                return;
            }
            Player player = data.getPlayer();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            Bounds b = FormAlignmentGateConfig.get(group, form);
            if (b == null || !b.hasUseGate()) {
                return;
            }
            serverPlayer.displayClientMessage(
                    Component.translatable(USE_LOCKED_MESSAGE_KEY,
                                    FormLevelGate.formName(data, group, form, stack),
                                    b.effUseMin(), b.effUseMax(), Math.max(0, alignmentOf(data)))
                            .withStyle(ChatFormatting.RED),
                    true);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not send form-alignment-use-locked message: {}", DmzNpc.MODID, t.toString());
        }
    }

    // ---- UNLOCK gate (buy / purchase) ---------------------------------------------------------------------

    /** A {@code group.form} key resolved to the DMZ {@code (skillName, unlockLevel)} it is bought against. */
    private record Resolved(String skillName, int level) {
    }

    /**
     * Resolve a {@code group.form} key to {@code (skillName, unlockOnSkillLevel)}, or null (unknown / DMZ shape
     * mismatch) so callers fail OPEN. Scoped to {@code race}'s per-race form map so a gate can't leak onto a
     * different race sharing the group; stack forms are raceless and matched globally. Mirrors {@code FormQuestGate}.
     */
    private static Resolved resolve(String race, String formKey) {
        if (formKey == null) {
            return null;
        }
        int dot = formKey.indexOf('.');
        if (dot <= 0 || dot >= formKey.length() - 1) {
            return null;
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

    /** The loaded {@link FormConfig} named {@code group}, scoped to {@code race}, else a stack group. Mirrors
     * {@code FormQuestGate.findGroup}. */
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

    /** True when {@code group} is a raceless stack group (kaioken / ultimate), so the form name uses the stack key. */
    private static boolean isStackGroup(String group) {
        if (group == null || group.isBlank()) {
            return false;
        }
        try {
            var stack = ConfigManager.getAllStackForms();
            if (stack != null) {
                for (FormConfig fc : stack.values()) {
                    if (fc != null && group.equals(fc.getGroupName())) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // treat as non-stack on any doubt
        }
        return false;
    }

    /** one gated form: its {@code group.form} key and bounds, matched for a buy of a skill at a level */
    private record BlockingKey(String formKey, Bounds bounds) {
    }

    /** The alignment gate blocking a buy of {@code skillName} at {@code targetLevel} (outside its unlock window),
     * or null. Fails to null. */
    private static BlockingKey blockingUnlock(StatsData data, String skillName, int targetLevel) {
        if (data == null || skillName == null || skillName.isEmpty() || targetLevel < 1) {
            return null;
        }
        try {
            int a = alignmentOf(data);
            if (a < 0) {
                return null; // could not read alignment: fail open
            }
            String race = (data.getCharacter() == null) ? "" : data.getCharacter().getRaceName();
            for (var e : FormAlignmentGateConfig.all().entrySet()) {
                Bounds b = e.getValue();
                if (b == null || !b.hasUnlockGate()) {
                    continue;
                }
                Resolved r = resolve(race, e.getKey());
                if (r == null || !skillName.equals(r.skillName()) || r.level() != targetLevel) {
                    continue;
                }
                // Only block a form the player does NOT yet own at this level: an already-unlocked form is the
                // USE gate's business (whether they may transform), never the unlock gate's. Mirrors the quest gate.
                if (data.getSkills().getSkillLevel(skillName) >= targetLevel) {
                    continue;
                }
                if (!b.allowsUnlock(a)) {
                    return new BlockingKey(e.getKey(), b);
                }
            }
        } catch (Throwable ignored) {
            // fall through to null (fail open)
        }
        return null;
    }

    /** Block the buy of {@code skillName} at {@code targetLevel} when a mapped form's unlock window excludes this
     * character's alignment. Fails open. */
    public static boolean blocksUnlock(StatsData data, String skillName, int targetLevel) {
        return blockingUnlock(data, skillName, targetLevel) != null;
    }

    /** Send the blocked player a red "alignment X-Y required to unlock <form>" chat message. Server-side only. */
    public static void notifyUnlockBlocked(StatsData data, String skillName, int targetLevel) {
        try {
            BlockingKey block = blockingUnlock(data, skillName, targetLevel);
            if (block == null) {
                return;
            }
            Player player = data.getPlayer();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            String formKey = block.formKey();
            int dot = formKey.indexOf('.');
            String group = dot > 0 ? formKey.substring(0, dot) : "";
            String form = dot > 0 ? formKey.substring(dot + 1) : formKey;
            MutableComponent name = FormLevelGate.formName(data, group, form, isStackGroup(group));
            Bounds b = block.bounds();
            serverPlayer.sendSystemMessage(
                    Component.translatable(UNLOCK_LOCKED_MESSAGE_KEY, name,
                                    b.effUnlockMin(), b.effUnlockMax(), Math.max(0, alignmentOf(data)))
                            .withStyle(ChatFormatting.RED));
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not send form-alignment-unlock-locked message: {}", DmzNpc.MODID, t.toString());
        }
    }
}
