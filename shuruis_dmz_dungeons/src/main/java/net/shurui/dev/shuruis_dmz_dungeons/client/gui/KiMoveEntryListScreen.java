package net.shurui.dev.shuruis_dmz_dungeons.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_dmz_dungeons.block.KiMoveEntry;

import java.util.List;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

// lists a spawner's ki blasts (one summary per row) with Edit/Delete/Add. edits open KiMoveEntryEditScreen.
// works on the config token list directly; changes persist when the parent AdvancedSpawnerScreen saves.
// empty list = the fighter falls back to the Moveset/Ki-Enabled path instead of an explicit loadout.
public class KiMoveEntryListScreen extends BaseEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    private final List<String> moves;
    private int scroll = 0;

    public KiMoveEntryListScreen(Screen parent, String subtitle, List<String> moves) {
        super(Component.translatable("gui.dmz_ragnarok.dungeons.ki_list.title"), UI_W, UI_H, parent);
        this.moves = moves;
        this.pendingSubtitle = subtitle;
    }

    private final String pendingSubtitle;

    @Override
    protected void init() {
        super.init();
        headerSubtitle = pendingSubtitle;

        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        int total = moves.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final int idx = i;
            KiMoveEntry m = KiMoveEntry.fromToken(moves.get(idx));
            // colour swatch preview next to the summary
            rect(12, y + 2, 10, 10, 0xFF000000 | (m.colorMain & 0xFFFFFF));
            label(net.minecraft.client.resources.language.I18n.get(
                    "gui.dmz_ragnarok.dungeons.ki_list.summary", m.type, m.rangeLabel(), m.sizeLabel(), m.hex6()), 26, y + 4);
            // Anchored to rowControlRight() rather than a literal X. At the old 244 the Delete button reached 288,
            // past the panel's control edge and INTO the scrollbar column; the band tests the scroll thumb before any
            // button, so once the list overflowed Delete simply stopped responding.
            int delX = rowControlRight() - 44;
            btn(delX - 48, y, 44, 14, Component.translatable("gui.dmz_ragnarok.dungeons.common.edit"),
                    () -> this.minecraft.setScreen(new KiMoveEntryEditScreen(this, moves, idx)));
            btn(delX, y, 44, 14, Component.translatable("gui.dmz_ragnarok.dungeons.common.delete"), () -> {
                moves.remove(idx);
                if (scroll > 0 && scroll >= moves.size()) {
                    scroll--;
                }
                rebuildWidgets();
            });
            y += ROW_H;
        }
        scrollList(12, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });

        btn(6, footerY(), 120, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.dungeons.ki_list.add"), () -> {
            moves.add(new KiMoveEntry().toToken());
            this.minecraft.setScreen(new KiMoveEntryEditScreen(this, moves, moves.size() - 1));
        });
        btn(UI_W - 106, footerY(), 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.dungeons.common.back"), this::back);
    }
}
