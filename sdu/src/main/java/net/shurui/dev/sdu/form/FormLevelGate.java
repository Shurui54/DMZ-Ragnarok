package net.shurui.dev.sdu.form;

import com.dragonminez.common.stats.StatsData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.shurui.dev.sdu.DmzNpc;

/**
 * Server-side evaluation of the per-form minimum-level gate ({@link FormLevelGateConfig}). Answers "may this
 * character transform into {@code group.form}", using DMZ's {@code StatsData.getLevel()} (character level, the
 * same number the level HUD shows), and formats the "reach level X" refusal.
 *
 * <p>Everything fails OPEN: any unexpected DMZ shape returns "not blocked", so a mismatch never traps a player
 * out of a form. A level EQUAL to the minimum is allowed (strictly-less-than is the block).
 */
public final class FormLevelGate {

    /** Lang key for the red refusal. Args: %1$s = form name, %2$d = required level. */
    public static final String LOCKED_MESSAGE_KEY = "message.dmz_ragnarok.form.level_locked";

    private FormLevelGate() {
    }

    /** The configured minimum level for {@code group.form}, or 0 (no minimum). */
    public static int required(String group, String form) {
        try {
            return FormLevelGateConfig.get(group, form);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** True when a minimum is set for {@code group.form} and this character's level is below it. Fails open. */
    public static boolean blocks(StatsData data, String group, String form) {
        if (data == null) {
            return false;
        }
        try {
            int min = required(group, form);
            if (min < 1) {
                return false;
            }
            return data.getLevel() < min;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Localized display name of a form, matching the keys DMZ transforms print:
     * {@code race.dragonminez.<race>.form.<group>.<form>} for a base form,
     * {@code race.dragonminez.stack.form.<group>.<form>} for a stack form.
     */
    public static MutableComponent formName(StatsData data, String group, String form, boolean stack) {
        try {
            if (stack) {
                return Component.translatable("race.dragonminez.stack.form." + group + "." + form);
            }
            String race = (data == null || data.getCharacter() == null) ? "" : data.getCharacter().getRaceName();
            return Component.translatable("race.dragonminez." + race + ".form." + group + "." + form);
        } catch (Throwable t) {
            return Component.literal(form == null ? "?" : form);
        }
    }

    /** Send the blocked player the red "reach level X to use <form>" message (action-bar). Server-side only. */
    public static void notifyBlocked(StatsData data, String group, String form, boolean stack) {
        try {
            if (data == null) {
                return;
            }
            Player player = data.getPlayer();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            int min = required(group, form);
            if (min < 1) {
                return;
            }
            serverPlayer.displayClientMessage(
                    Component.translatable(LOCKED_MESSAGE_KEY, formName(data, group, form, stack), min)
                            .withStyle(ChatFormatting.RED),
                    true);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not send form-level-locked message: {}", DmzNpc.MODID, t.toString());
        }
    }
}
