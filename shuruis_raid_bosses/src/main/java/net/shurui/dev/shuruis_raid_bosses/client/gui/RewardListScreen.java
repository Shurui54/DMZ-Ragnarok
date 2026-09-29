package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.shurui.dev.shuruis_raid_bosses.reward.Reward;

import java.util.List;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * Lists one reward category (participant / top-damage) under a two-line header, with a human summary
 * per reward and Edit / Delete / Add actions. Editing opens the structured {@link RewardEditScreen}.
 * Operates on the def's token list directly; changes persist when the parent edit screen is saved.
 */
public class RewardListScreen extends BaseEditScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    private final List<String> rewards;

    private int scroll = 0;

    public RewardListScreen(Screen parent, String categoryTitleKey, List<String> rewards) {
        super(Component.translatable(categoryTitleKey), UI_W, UI_H, parent);
        this.rewards = rewards;
    }

    @Override
    protected void init() {
        super.init();
        // Generic list screen: no descriptive header label (suite convention drops it); only the logo heads it.

        // Derive the row cap from the space between the list top and the footer so the list fills the panel.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        int total = rewards.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final int idx = i;
            Reward r = Reward.fromToken(rewards.get(idx));
            label(display(r), 12, y + 5);
            // Row controls: Edit + Delete with the standard BUTTON_GAP_X (4px) between them; the trailing Delete
            // ends at rowControlRight() (278), never under the scrollbar column.
            btn(186, y, 44, 14, Component.translatable("gui.dmz_ragnarok.raid.common.edit"),
                    () -> this.minecraft.setScreen(new RewardEditScreen(this, rewards, idx)));
            btn(234, y, 44, 14, Component.translatable("gui.dmz_ragnarok.raid.common.delete_plain"), () -> {
                rewards.remove(idx);
                if (scroll > 0 && scroll >= rewards.size()) scroll--;
                rebuildWidgets();
            });
            y += ROW_H;
        }
        scrollList(12, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });

        btn(6, footerY(), 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.reward_list.add"), () -> {
            rewards.add(new Reward().toToken());
            this.minecraft.setScreen(new RewardEditScreen(this, rewards, rewards.size() - 1));
        });
        btn(UI_W - 106, footerY(), 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.common.back"), this::back);
    }

    /** Human summary, resolving item ids to their real display name via I18n (custom text passes through). */
    private static String display(Reward r) {
        return switch (r.type) {
            case "ITEM" -> I18n.get("gui.dmz_ragnarok.raid.reward.summary_item", r.count, itemName(r.item));
            case "TP" -> I18n.get("gui.dmz_ragnarok.raid.reward.summary_tp", r.tp);
            case "SKILL" -> I18n.get("gui.dmz_ragnarok.raid.reward.summary_skill", r.skill, r.level);
            case "MESSAGE" -> I18n.get("gui.dmz_ragnarok.raid.reward.summary_message", r.message);
            default -> I18n.get("gui.dmz_ragnarok.raid.reward.summary_command", r.command);
        };
    }

    private static String itemName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        var item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
        return item == null ? id : I18n.get(item.getDescriptionId());
    }
}
