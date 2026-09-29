package net.shurui.dev.shuruis_dmz_tournaments.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_dmz_tournaments.reward.Reward;

import java.util.List;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * Lists one reward category (winner / runner-up / participation) with a summary per reward and
 * Edit / Delete / Add actions. Editing opens {@link RewardEditScreen}. A drill-down child of the
 * tournament editor: changes persist only when the parent edit screen is saved.
 */
public class RewardListScreen extends BaseEditScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    private final List<String> rewards;
    private final String categoryTitleKey;
    private int scroll = 0;

    public RewardListScreen(Screen parent, String categoryTitleKey, List<String> rewards) {
        super(Component.translatable(categoryTitleKey), UI_W, UI_H, parent);
        this.categoryTitleKey = categoryTitleKey;
        this.rewards = rewards;
    }

    @Override
    protected void init() {
        super.init();
        // Named category: show its name top-left, no "Editor" subtitle (suite title convention).
        topLeftName = net.minecraft.client.resources.language.I18n.get(categoryTitleKey);
        // row cap from the gap between list top and footer, so the list fills the panel
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        int total = rewards.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final int idx = i;
            Reward r = Reward.fromToken(rewards.get(idx));
            label(r.summary(), 10, y + 5);
            // stop at rowControlRight() so the last button never sits under the scrollbar
            btn(194, y, 40, 14, Component.translatable("gui.dmz_ragnarok.tournaments.common.edit"),
                    () -> this.minecraft.setScreen(new RewardEditScreen(this, rewards, idx)));
            btn(240, y, rowControlRight() - 240, 14, Component.translatable("gui.dmz_ragnarok.tournaments.common.delete"), () -> {
                rewards.remove(idx);
                if (scroll > 0 && scroll >= rewards.size()) scroll--;
                rebuildWidgets();
            });
            y += ROW_H;
        }
        scrollList(10, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });

        int footerY = net.shurui.dev.sdu.client.gui.theme.GuiTheme.footerY(UI_H);
        btn(6, footerY, 120, 14, Component.translatable("gui.dmz_ragnarok.tournaments.reward_list.add"), () -> {
            rewards.add(new Reward().toToken());
            this.minecraft.setScreen(new RewardEditScreen(this, rewards, rewards.size() - 1));
        });
        btn(UI_W - 126, footerY, 120, 14, Component.translatable("gui.dmz_ragnarok.tournaments.common.back"), this::back);
    }
}
