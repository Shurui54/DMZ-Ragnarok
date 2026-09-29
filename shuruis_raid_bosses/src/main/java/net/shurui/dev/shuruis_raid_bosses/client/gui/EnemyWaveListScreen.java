package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_raid_bosses.raid.EnemyWave;

import java.util.List;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * Lists a Parallel Quest raid's enemy waves (entity + amount + per-enemy stats) with Edit / Delete / Add
 * actions. Editing opens {@link EnemyWaveEditScreen}. Operates on the def's token list directly; changes
 * persist when the parent {@link RaidEditScreen} is saved.
 */
public class EnemyWaveListScreen extends BaseEditScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    private final List<String> waves;
    private int scroll = 0;

    public EnemyWaveListScreen(Screen parent, List<String> waves) {
        super(Component.translatable("gui.dmz_ragnarok.raid.enemy_list.title"), UI_W, UI_H, parent);
        this.waves = waves;
    }

    @Override
    protected void init() {
        super.init();
        // Generic list screen: no descriptive header label (suite convention drops it); only the logo heads it.

        // Derive the row cap from the space between the list top and the footer so the list fills the panel.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        int total = waves.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final int idx = i;
            EnemyWave w = EnemyWave.fromToken(waves.get(idx));
            label(w.summary(), 12, y + 5);
            // Row controls: Edit + Delete with the standard BUTTON_GAP_X (4px) between them; the trailing Delete
            // ends at rowControlRight() (278), never under the scrollbar column.
            btn(186, y, 44, 14, Component.translatable("gui.dmz_ragnarok.raid.common.edit"),
                    () -> this.minecraft.setScreen(new EnemyWaveEditScreen(this, waves, idx)));
            btn(234, y, 44, 14, Component.translatable("gui.dmz_ragnarok.raid.common.delete_plain"), () -> {
                waves.remove(idx);
                if (scroll > 0 && scroll >= waves.size()) scroll--;
                rebuildWidgets();
            });
            y += ROW_H;
        }
        scrollList(12, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });

        btn(6, footerY(), 120, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.enemy_list.add"), () -> {
            waves.add(new EnemyWave().toToken());
            this.minecraft.setScreen(new EnemyWaveEditScreen(this, waves, waves.size() - 1));
        });
        btn(UI_W - 106, footerY(), 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.common.back"), this::back);
    }
}
