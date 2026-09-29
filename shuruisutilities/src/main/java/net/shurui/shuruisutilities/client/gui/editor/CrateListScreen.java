package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

// crates list: each crate + reward count; click to edit, delete per row, or create. rows: [name, rewardCount].
public class CrateListScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;

    private final List<List<String>> rows;
    private int scroll = 0;
    private EditBox newBox;

    public CrateListScreen(List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.crates"), UI_W, UI_H, null);
        this.rows = rows;
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.core.crate.subtitle", rows.size());
        // the new-name field sits on the footer row (UI_H - 24 = contentBottom), so the list already stops above it.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            final String name = rows.get(i).get(0);
            String count = rows.get(i).size() > 1 ? rows.get(i).get(1) : "0";
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            // trailing Delete right-aligned to the scrollbar-reserved column edge; the click row stops one gap
            // short of it so the reward-count value and the delete button both clear the scrollbar column.
            int delW = 52;
            int delX = rowControlRight() - delW;
            rowBtn(14, ry, delX - 4 - 14, GuiTheme.ROW_HEIGHT, Component.literal(name), () -> EditorScreens.act("crates", "open", name))
                    .color(0xFFF6E27A).right(Component.translatable("gui.dmz_ragnarok.core.crate.reward_count", count), 0xFFB0B0B0);
            btn(delX, ry, delW, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.delete"),
                    () -> EditorScreens.act("crates", "delete", name));
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        newBox = field(14, UI_H - 24, 150, "");
        newBox.setHint(Component.translatable("gui.dmz_ragnarok.core.crate.new_hint"));
        newBox.setMaxLength(64);
        btn(168, footerY(), 56, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.new"), () -> {
            String v = newBox.getValue().trim();
            if (!v.isBlank())
                EditorScreens.act("crates", "new", v);
        });
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }
}
