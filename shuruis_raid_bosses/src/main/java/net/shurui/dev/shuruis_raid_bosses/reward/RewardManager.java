package net.shurui.dev.shuruis_raid_bosses.reward;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.shurui.dev.shuruis_raid_bosses.dmz.DmzHooks;
import net.shurui.dev.shuruis_raid_bosses.util.TextUtil;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * Applies configured reward tokens to a player. Supported tokens:
 * <pre>
 *   command:&lt;cmd&gt;            run by the server console (permission level 4)
 *   item:&lt;id&gt;:&lt;count&gt;       give an item stack
 *   skill:&lt;dmzSkillId&gt;:&lt;n&gt;  add n levels to a DragonMineZ skill
 *   form:&lt;dmzFormId&gt;:&lt;n&gt;   unlock/level a DragonMineZ form (forms are skills; alias of skill:)
 *   tp:&lt;amount&gt;             grant DragonMineZ training points
 *   message:&lt;text&gt;          send a colored chat message
 *   chance:&lt;pct&gt;:&lt;token&gt;    roll 0-100; on success apply the wrapped reward token (a "custom drop")
 * </pre>
 * Command and message tokens have placeholders substituted first (see {@link #giveAll}).
 */
public final class RewardManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private RewardManager() {}

    /**
     * Apply every reward token to {@code player}. {@code placeholders} maps a placeholder token
     * (e.g. {@code "%damage_percent%"}) to its per-recipient value, replaced in command and message args
     * before execution. {@code %player%} / {@code %uuid%} are always available.
     */
    public static void giveAll(MinecraftServer server, ServerPlayer player,
                               List<? extends String> rewards, Map<String, String> placeholders) {
        if (player == null || rewards == null) return;
        for (String token : rewards) {
            try {
                apply(server, player, token.trim(), placeholders);
            } catch (Exception e) {
                LOGGER.warn("[Raid] Failed to apply reward '{}': {}", token, e.toString());
            }
        }
    }

    private static void apply(MinecraftServer server, ServerPlayer player, String token,
                              Map<String, String> placeholders) {
        int colon = token.indexOf(':');
        if (colon < 0) return;
        String type = token.substring(0, colon).toLowerCase();
        String arg = token.substring(colon + 1);

        switch (type) {
            case "chance" -> {
                // chance:<pct>:<wrapped token>: roll once, apply the wrapped reward on success
                int inner = arg.indexOf(':');
                if (inner < 0) return;
                double pct = safeFloat(arg.substring(0, inner), 0f);
                String wrapped = arg.substring(inner + 1);
                if (Math.random() * 100.0 < pct) apply(server, player, wrapped.trim(), placeholders);
            }
            case "command" -> {
                String cmd = fill(arg, player, placeholders);
                CommandSourceStack src = server.createCommandSourceStack()
                        .withPermission(4).withSuppressedOutput();
                server.getCommands().performPrefixedCommand(src, cmd);
            }
            case "message" -> player.sendSystemMessage(TextUtil.color(fill(arg, player, placeholders)));
            case "item" -> {
                String[] parts = arg.split(":");
                int count = 1;
                String id;
                if (parts.length >= 3) {
                    id = parts[0] + ":" + parts[1];
                    count = safeInt(parts[2], 1);
                } else {
                    id = arg;
                }
                Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(id));
                if (item != null) {
                    ItemStack stack = new ItemStack(item, count);
                    // Inventory.add returns TRUE as soon as ONE item was placed and mutates the stack down to whatever did
// not fit, so testing only the return value threw the overflow away whenever the player was already
// carrying a partial stack of the same item. That is why dungeon gems "voided" for players who already
// had gems in their inventory (ticket 800, 2026-09-13). Check the remainder, not the flag.
                    player.getInventory().add(stack);
                    if (!stack.isEmpty()) {
                        player.drop(stack, false);
                    }
                }
            }
            case "tp" -> {
                float amount = safeFloat(arg, 0f);
                if (amount != 0f) DmzHooks.addTrainingPoints(player, amount);
            }
            case "skill", "form" -> {
                // forms in DragonMineZ are skills, so form: is an alias of skill:
                String[] parts = arg.split(":");
                String skillId = parts[0];
                int levels = parts.length >= 2 ? safeInt(parts[1], 1) : 1;
                DmzHooks.addSkillLevel(player, skillId, levels);
            }
            default -> LOGGER.warn("[Raid] Unknown reward type '{}'", type);
        }
    }

    private static String fill(String s, ServerPlayer player, Map<String, String> placeholders) {
        String out = s.replace("%player%", player.getGameProfile().getName())
                .replace("{player}", player.getGameProfile().getName())
                .replace("%uuid%", player.getUUID().toString());
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                out = out.replace(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static int safeInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float safeFloat(String s, float def) {
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
