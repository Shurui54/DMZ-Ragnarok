package net.shurui.dev.sdu.event;

import com.dragonminez.common.events.DMZEvent;
import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.Quest;
import com.dragonminez.common.quest.QuestObjective;
import com.dragonminez.common.quest.objectives.ItemObjective;
import com.dragonminez.common.stats.StatsData;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzForms;
import net.shurui.dev.sdu.quest.QuestItemConsumeConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime for the addon's "item quest takes the items on completion" toggle
 * ({@link QuestItemConsumeConfig}). DMZ's ITEM objective is a live "possess N" mirror of the player's
 * inventory that NEVER removes the items, so this consumes them addon-side.
 *
 * <p>It listens to DMZ's {@code QuestCompletedEvent}, which fires at the single atomic completion moment for
 * BOTH the auto-complete (tree) path and the NPC turn-in path, immediately before DMZ records the completion
 * in the same synchronous dispatch. Because the ITEM objective's progress is a mirror of the inventory count
 * set that same tick, {@code progress >= required} (the condition that let the quest complete) means the
 * player possesses the items right now, so a re-count here should always succeed.
 *
 * <p>Consumption is made all-or-nothing and can never break DMZ's completion:
 * <ul>
 *   <li>Two passes: verify EVERY consuming ITEM objective still has its scaled required count in the
 *       inventory BEFORE removing anything, then remove. So a partial removal can never happen.</li>
 *   <li>If the re-count comes up short (should be unreachable), nothing is removed and we log. Erring toward
 *       NOT deleting the player's property is the safe direction; this codebase has been burned by an
 *       inventory wipe before.</li>
 *   <li>The whole handler is wrapped so no exception ever escapes: {@code QuestCompletedEvent} is not
 *       cancelable, and letting an exception propagate out of the post() call would abort DMZ's own
 *       completeQuest, i.e. consume-without-completing. We swallow instead.</li>
 * </ul>
 *
 * <p>Removal is item-only (ignores NBT), deliberately matching DMZ's own {@code QuestEvents.countItems}, which
 * is how the objective was judged complete in the first place: we remove exactly what DMZ counted. The scaled
 * required count ({@code quest.getObjectiveRequired}) is used so party-scaled item objectives consume the full
 * amount the player actually had to gather.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class QuestItemConsumeHandler {

    private QuestItemConsumeHandler() {
    }

    @SubscribeEvent
    public static void onQuestCompleted(DMZEvent.QuestCompletedEvent event) {
        try {
            if (QuestItemConsumeConfig.isEmpty()) {
                return;
            }
            ServerPlayer player = event.getPlayer();
            Quest quest = event.getQuest();
            String questKey = event.getQuestKey();
            if (player == null || quest == null || questKey == null || questKey.isBlank()) {
                return;
            }
            if (!QuestItemConsumeConfig.get(questKey)) {
                return;
            }
            StatsData stats = DmzForms.stats(player);
            PlayerQuestData pqd = stats == null ? null : stats.getPlayerQuestData();

            // Pass 1: gather (item, count) to remove and verify the inventory still holds each in full.
            List<Item> items = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            List<QuestObjective> objectives = quest.getObjectives();
            for (int i = 0; i < objectives.size(); i++) {
                if (!(objectives.get(i) instanceof ItemObjective itemObjective)) {
                    continue;
                }
                Item item = resolve(itemObjective.getItemId());
                if (item == null) {
                    continue;
                }
                int required = pqd != null
                        ? quest.getObjectiveRequired(pqd, questKey, i)
                        : itemObjective.getCount();
                if (required <= 0) {
                    continue;
                }
                if (countItems(player, item) < required) {
                    // Pathological: the completing player no longer holds the full requirement. Remove nothing.
                    DmzNpc.LOGGER.warn("[{}] Quest '{}' set to consume items but {} no longer holds {}x {}; "
                                    + "removing nothing.", DmzNpc.MODID, questKey,
                            player.getGameProfile().getName(), required, itemObjective.getItemId());
                    return;
                }
                items.add(item);
                counts.add(required);
            }

            // Pass 2: everything verified present, remove exactly the required counts.
            for (int i = 0; i < items.size(); i++) {
                removeItems(player, items.get(i), counts.get(i));
            }
            if (!items.isEmpty()) {
                player.getInventory().setChanged();
            }
        } catch (Throwable t) {
            // Never let this abort DMZ's completion (the event is not cancelable).
            DmzNpc.LOGGER.warn("[{}] quest item-consume failed: {}", DmzNpc.MODID, t.toString());
        }
    }

    private static Item resolve(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        ResourceLocation loc = ResourceLocation.tryParse(itemId);
        return loc == null ? null : ForgeRegistries.ITEMS.getValue(loc);
    }

    /** Item-only count across the whole inventory, matching DMZ's QuestEvents.countItems. */
    private static int countItems(ServerPlayer player, Item item) {
        Inventory inv = player.getInventory();
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Remove exactly {@code amount} of {@code item} (item-only) from the inventory. */
    private static void removeItems(ServerPlayer player, Item item, int amount) {
        Inventory inv = player.getInventory();
        int remaining = amount;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() != item) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
    }
}
