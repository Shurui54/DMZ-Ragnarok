package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * The admin event list (E11): one row per event definition with its live status, an Edit click and a Delete
 * button, plus a create field. It is the entry the {@link net.shurui.shuruisutilities.events.network.PacketEventEditorOpen}
 * client sink opens for the "list" payload; a row edit sends {@code EditorScreens.act("events", "open", id)} which
 * the server answers with a single-event payload that opens {@link EventEditScreen}.
 *
 * <p>Pure core screen, on the shared {@link GuiTheme} via {@link SagaBaseScreen}: no home-made chrome, no subtitle
 * counters beyond the standard header line. It is only ever opened when the connected server reports the private
 * {@code events} feature (the {@code PacketEventEditorOpen} sink gates on {@code ClientGate.feature("events")}).
 *
 * <p>Rows are {@code [id, name, status]}.
 */
public class EventListScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;

    private final List<List<String>> rows;
    private int scroll = 0;
    private EditBox newBox;

    public EventListScreen(List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.events"), UI_W, UI_H, null);
        this.rows = rows;
    }

    private static String col(List<String> row, int i)
    {
        return row.size() > i && row.get(i) != null ? row.get(i) : "";
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.core.event.subtitle", rows.size());
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            final String id = col(rows.get(i), 0);
            String name = col(rows.get(i), 1);
            String status = col(rows.get(i), 2);
            if (name.isBlank())
                name = id;
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            int delW = 52;
            int delX = rowControlRight() - delW;
            rowBtn(14, ry, delX - 4 - 14, GuiTheme.ROW_HEIGHT, Component.literal(name.replace('&', '§')),
                    () -> EditorScreens.act("events", "open", id))
                    .color(0xFFF6E27A)
                    .right(Component.literal("§7" + status), 0xFFB0B0B0);
            btn(delX, ry, delW, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.delete"),
                    () -> EditorScreens.act("events", "delete", id));
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        newBox = field(14, UI_H - 24, 150, "");
        newBox.setHint(Component.translatable("gui.dmz_ragnarok.core.event.new_hint"));
        newBox.setMaxLength(64);
        btn(168, footerY(), 56, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.new"), () -> {
            String v = newBox.getValue().trim().toLowerCase(java.util.Locale.ROOT);
            if (!v.isBlank())
                EditorScreens.act("events", "new", v);
        });
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }
}
