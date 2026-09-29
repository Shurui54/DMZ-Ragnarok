package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_raid_bosses.raid.BossStage;

import java.util.List;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * Lists a Boss Rush raid's ordered stages (each a boss + pre-spawn delay) with Edit / Delete / Add and
 * Up / Down reordering. Editing opens {@link BossStageEditScreen}. Operates on the def's token list
 * directly; changes persist when the parent {@link RaidEditScreen} is saved.
 */
public class BossStageListScreen extends BaseEditScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    private final List<String> stages;
    private int scroll = 0;

    public BossStageListScreen(Screen parent, List<String> stages) {
        super(Component.translatable("gui.dmz_ragnarok.raid.stage_list.title"), UI_W, UI_H, parent);
        this.stages = stages;
    }

    @Override
    protected void init() {
        super.init();
        // Generic list screen: no descriptive header label (suite convention drops it); only the logo heads it.

        // Derive the row cap from the space between the list top and the footer so the list fills the panel.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        int total = stages.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final int idx = i;
            BossStage s = BossStage.fromToken(stages.get(idx));
            label(I18n.get("gui.dmz_ragnarok.raid.stage_list.row", idx + 1, s.summary()), 12, y + 5);
            // Move arrows are round icon buttons (the uniform icon pill); Edit/Delete pulled in so the trailing
            // Delete ends at rowControlRight() and nothing sits under the scrollbar column.
            iconBtnAt(146, y, ROW_H, Component.literal("▲"), () -> move(idx, -1));
            iconBtnAt(168, y, ROW_H, Component.literal("▼"), () -> move(idx, 1));
            btn(190, y, 40, 14, Component.translatable("gui.dmz_ragnarok.raid.common.edit"),
                    () -> this.minecraft.setScreen(new BossStageEditScreen(this, stages, idx)));
            btn(234, y, 44, 14, Component.translatable("gui.dmz_ragnarok.raid.common.delete_plain"), () -> {
                stages.remove(idx);
                if (scroll > 0 && scroll >= stages.size()) scroll--;
                rebuildWidgets();
            });
            y += ROW_H;
        }
        scrollList(12, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });

        btn(6, footerY(), 120, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.stage_list.add"), () -> {
            stages.add(new BossStage().toToken());
            this.minecraft.setScreen(new BossStageEditScreen(this, stages, stages.size() - 1));
        });
        btn(UI_W - 106, footerY(), 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.common.back"), this::back);
    }

    private void move(int idx, int dir) {
        int j = idx + dir;
        if (j < 0 || j >= stages.size()) return;
        String tmp = stages.get(idx);
        stages.set(idx, stages.get(j));
        stages.set(j, tmp);
        rebuildWidgets();
    }
}
