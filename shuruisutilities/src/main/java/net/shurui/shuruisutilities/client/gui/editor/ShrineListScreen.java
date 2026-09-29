package net.shurui.shuruisutilities.client.gui.editor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

// shrines list: each shrine + type, stat/TP, percent; click to edit, copy/delete per row, or create. delete is
// a two-click confirm (first click arms, second commits). rows: [name, type, statOrTP, percent, duration, cooldown].
public class ShrineListScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;

    private final List<List<String>> rows;
    private int scroll = 0;
    private EditBox newBox;
    // row whose Delete is armed (needs a second click), or null
    private String confirmDelete;

    public ShrineListScreen(List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.shrines"), UI_W, UI_H, null);
        this.rows = rows;
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.core.shrine.subtitle", rows.size());
        // Reserve holds the footer-level "new shrine name" field (drawn at UI_H - 24) clear of the list.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H, 10);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            final List<String> r = rows.get(i);
            final String name = r.get(0);
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            String type = r.size() > 1 ? r.get(1) : "STAT";
            String what = r.size() > 2 ? r.get(2) : "";
            String pct = r.size() > 3 ? r.get(3) : "0";
            String summary = ("TP".equals(type) ? "TP" : what) + " +" + trimNum(pct) + "%";
            // trailing Copy + Delete pair right-aligned to the scrollbar-reserved column edge
            int delW = 42, copyW = 40;
            int delX = rowControlRight() - delW;
            int copyX = delX - 4 - copyW;
            rowBtn(14, ry, copyX - 4 - 14, ROW_H, Component.literal(name),
                    () -> EditorScreens.act("shrines", "open", name)).color(0xFF9BD0F6)
                    .right(Component.literal(summary), 0xFFB0B0B0);
            btn(copyX, ry, copyW, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.copy"),
                    () -> EditorScreens.act("shrines", "copy", name, uniqueCopyName(name)));
            boolean armed = name.equals(confirmDelete);
            btn(delX, ry, delW, GuiTheme.BUTTON_HEIGHT,
                    Component.translatable(armed ? "gui.dmz_ragnarok.core.btn.confirm" : "gui.dmz_ragnarok.core.btn.delete"),
                    () -> {
                        if (armed)
                            EditorScreens.act("shrines", "delete", name);
                        else
                        {
                            confirmDelete = name;
                            rebuildWidgets();
                        }
                    });
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        newBox = field(14, UI_H - 24, 150, "");
        newBox.setHint(Component.translatable("gui.dmz_ragnarok.core.shrine.new_hint"));
        newBox.setMaxLength(64);
        btn(168, footerY(), 56, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.new"), () -> {
            String v = newBox.getValue().trim();
            if (!v.isBlank())
                EditorScreens.act("shrines", "new", v);
        });
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    // a "<name>_copy" (or _copy2, _copy3...) not already in the list
    private String uniqueCopyName(String base)
    {
        Set<String> taken = new HashSet<>();
        for (List<String> r : rows)
            taken.add(r.get(0));
        String candidate = base + "_copy";
        int n = 2;
        while (taken.contains(candidate))
            candidate = base + "_copy" + (n++);
        return candidate;
    }

    // drop a trailing ".0" from a whole-number percent
    private static String trimNum(String d)
    {
        try
        {
            double v = Double.parseDouble(d);
            return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
        }
        catch (NumberFormatException e)
        {
            return d;
        }
    }
}
