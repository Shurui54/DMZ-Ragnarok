package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_raid_bosses.raid.KiMove;

import java.util.List;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * Lists the boss's configured ki moves under a two-line header, with a human summary per move and
 * Edit / Delete / Add actions. Editing opens {@link KiMoveEditScreen}. Operates on the def's token list
 * directly; changes persist when the parent {@link RaidEditScreen} is saved. An empty list means the
 * boss keeps its own defaults.
 */
public class KiMoveListScreen extends BaseEditScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    private final List<String> moves;

    private int scroll = 0;

    public KiMoveListScreen(Screen parent, List<String> moves) {
        super(Component.translatable("gui.dmz_ragnarok.raid.ki_list.title"), UI_W, UI_H, parent);
        this.moves = moves;
    }

    @Override
    protected void init() {
        super.init();
        // Generic list screen: no descriptive header label (suite convention drops it); only the logo heads it.

        // Derive the row cap from the space between the list top and the footer so the list fills the panel.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        int total = moves.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final int idx = i;
            KiMove m = KiMove.fromToken(moves.get(idx));
            label(m.summary(), 12, y + 5);
            // Row controls: Edit + Delete with the standard BUTTON_GAP_X (4px) between them; the trailing Delete
            // ends at rowControlRight() (278), never under the scrollbar column.
            btn(186, y, 44, 14, Component.translatable("gui.dmz_ragnarok.raid.common.edit"),
                    () -> this.minecraft.setScreen(new KiMoveEditScreen(this, moves, idx)));
            btn(234, y, 44, 14, Component.translatable("gui.dmz_ragnarok.raid.common.delete_plain"), () -> {
                moves.remove(idx);
                if (scroll > 0 && scroll >= moves.size()) scroll--;
                rebuildWidgets();
            });
            y += ROW_H;
        }
        scrollList(12, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });

        btn(6, footerY(), 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.ki_list.add"), () -> {
            moves.add(new KiMove().toToken());
            this.minecraft.setScreen(new KiMoveEditScreen(this, moves, moves.size() - 1));
        });
        btn(UI_W - 106, footerY(), 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.common.back"), this::back);
    }
}
