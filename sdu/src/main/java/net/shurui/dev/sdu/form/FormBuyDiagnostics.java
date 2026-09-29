package net.shurui.dev.sdu.form;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.RaceCharacterConfig;
import com.dragonminez.common.stats.StatsData;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.DmzNpc;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Says WHY a form skill would not buy, instead of the button doing nothing.
 *
 * <h2>Why this exists</h2>
 * DragonMineZ prices a form skill through {@code computeTpCost}, and every refusal it can make comes back as the
 * same {@code -1}. The caller compares {@code cost < 0} and returns, with no message, no sound and no log line. From
 * the player's side a purchase they can afford simply does not happen, and there is no way to tell a deliberate gate
 * from a broken config: "clicking them does nothing at all" is the only symptom any of these produce.
 *
 * <p>The causes are genuinely different and want different fixes, so this separates them:
 * <ul>
 *   <li>the race has no price list for that form type at all</li>
 *   <li>the skill is already at the maximum level the price list allows</li>
 *   <li>the price list has an EMPTY SLOT at that level, which is what a trailing comma in {@code character.json}
 *       turns into once the game's lenient JSON reader has been through it</li>
 * </ul>
 *
 * <p>Nothing here changes what is allowed. It only explains a refusal that has already been decided.
 */
public final class FormBuyDiagnostics {

    private FormBuyDiagnostics() {
    }

    /** Last explanation per player, so holding a click does not repeat the same line every packet. */
    private static final Map<UUID, String> LAST = new HashMap<>();

    /**
     * Explain a {@code -1} from {@code computeTpCost}, and tell the player.
     *
     * @param level the CURRENT level being priced from, exactly as DMZ passed it, so the slot named in the message
     *              is the slot DMZ actually looked at.
     */
    public static void explainRefusal(StatsData data, String skillName, int level) {
        try {
            if (data == null || skillName == null || skillName.isEmpty()) {
                return;
            }
            // Form skills only. Every other skill prices through a different list and would be noise here.
            var skills = ConfigManager.getSkillsConfig();
            if (skills == null || !skills.getFormSkills().contains(skillName.toLowerCase(Locale.ROOT))) {
                return;
            }
            if (!(data.getPlayer() instanceof ServerPlayer player)) {
                return;
            }

            String race = data.getCharacter() == null ? "" : data.getCharacter().getRaceName();
            RaceCharacterConfig config = ConfigManager.getRaceCharacter(race);
            Integer[] prices = config == null ? null : config.getFormSkillTpCosts(skillName);

            String reason;
            if (config == null) {
                reason = "your race (" + race + ") has no character config loaded";
            } else if (prices == null || prices.length == 0) {
                reason = "'" + skillName + "' has no prices set for " + race
                        + ", so it has no levels to buy (formSkillsCosts in that race's character.json)";
            } else if (level >= prices.length) {
                reason = "'" + skillName + "' is already at its highest level (" + prices.length + ")";
            } else if (level < 0) {
                reason = "'" + skillName + "' was asked for at an invalid level";
            } else if (prices[level] == null) {
                reason = "'" + skillName + "' has an EMPTY price at level " + (level + 1) + " of " + prices.length
                        + ". That slot is almost always a trailing comma in the prices list in "
                        + race + "/character.json, which the game reads as a blank level nothing can buy";
            } else {
                // A real price that still refused: something upstream of the price decided, and that path
                // reports itself (the quest gate messages the player directly).
                return;
            }

            String key = skillName + "|" + reason;
            if (key.equals(LAST.get(player.getUUID()))) {
                return;
            }
            LAST.put(player.getUUID(), key);

            player.sendSystemMessage(Component.literal("Cannot buy that form: " + reason + ".")
                    .withStyle(ChatFormatting.RED));
            // Logged too: the player sees a sentence, the operator sees which file to open.
            DmzNpc.LOGGER.warn("[{}] form buy refused for {}: {}", DmzNpc.MODID,
                    player.getGameProfile().getName(), reason);
        } catch (Throwable t) {
            // An explanation is never worth breaking a purchase over.
            DmzNpc.LOGGER.debug("[{}] could not explain a form refusal: {}", DmzNpc.MODID, t.toString());
        }
    }

    /** Forget a player's last message, so a genuine retry after a fix is explained again. */
    public static void clear(UUID player) {
        if (player != null) {
            LAST.remove(player);
        }
    }
}
