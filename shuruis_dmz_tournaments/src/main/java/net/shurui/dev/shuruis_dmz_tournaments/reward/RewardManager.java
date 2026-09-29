package net.shurui.dev.shuruis_dmz_tournaments.reward;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.shurui.dev.shuruis_dmz_tournaments.dmz.DmzHooks;
import net.shurui.dev.shuruis_dmz_tournaments.util.TextUtil;
import org.slf4j.Logger;

import java.util.List;

// applies reward tokens to a player. supported:
//   command:<cmd>          run by console; {player} -> name
//   item:<id>:<count>      give a stack
//   tp:<amount>            grant DMZ training points
//   skill:<dmzSkillId>:<n> add n levels to a DMZ skill
//   title:<titleId>        grant a transferable title
//   message:<text>         colored chat message
public final class RewardManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private RewardManager() {}

    public static void giveAll(MinecraftServer server, ServerPlayer player, List<? extends String> rewards) {
        if (player == null || rewards == null) return;
        for (String token : rewards) {
            try {
                apply(server, player, token.trim());
            } catch (Exception e) {
                LOGGER.warn("[Tournament] Failed to apply reward '{}': {}", token, e.toString());
            }
        }
    }

    private static void apply(MinecraftServer server, ServerPlayer player, String token) {
        int colon = token.indexOf(':');
        if (colon < 0) return;
        String type = token.substring(0, colon).toLowerCase();
        String arg = token.substring(colon + 1);

        switch (type) {
            case "command" -> {
                String cmd = arg.replace("{player}", player.getGameProfile().getName())
                        .replace("{uuid}", player.getUUID().toString());
                CommandSourceStack src = server.createCommandSourceStack()
                        .withPermission(4).withSuppressedOutput();
                server.getCommands().performPrefixedCommand(src, cmd);
            }
            case "message" -> player.sendSystemMessage(TextUtil.color(arg));
            case "item" -> {
                String[] parts = arg.split(":");
                // "minecraft:diamond:10" -> namespace:path:count
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
            case "tp" -> DmzHooks.addTrainingPoints(player, safeFloat(arg, 0f));
            case "skill" -> {
                String[] parts = arg.split(":");
                String skillId = parts[0];
                int levels = parts.length >= 2 ? safeInt(parts[1], 1) : 1;
                DmzHooks.addSkillLevel(player, skillId, levels);
            }
            case "title" -> TitleManager.grant(server, arg.trim(), player);
            default -> LOGGER.warn("[Tournament] Unknown reward type '{}'", type);
        }
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
