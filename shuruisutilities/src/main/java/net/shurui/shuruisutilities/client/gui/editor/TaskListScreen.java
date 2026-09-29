package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * The task pool: every task an admin has written, across all three boards.
 *
 * <p>Rows are {@code [id, period, name, goalSummary, weight]} and arrive already sorted by period then id, so the
 * three boards read as three grouped runs. That grouping is the point of the sort: an admin comes here asking
 * "what is on the weekly board", and interleaving by id would make that impossible to see at a glance.
 *
 * <p>The three reroll prices ride in on {@code meta}, in period order, and are edited from the footer rather than
 * from a screen of their own: there are only three numbers and they belong next to the pool they price.
 */
public class TaskListScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;
    /** Left/right margin the footer row keeps from the panel edge, matching the list rows above it. */
    private static final int FOOTER_INSET = 14;

    /** Board keys in the order the server sends their prices. Must match TaskPeriod's declaration order. */
    private static final String[] PERIODS = { "daily", "weekly", "monthly" };

    private final List<String> meta;
    private final List<List<String>> rows;
    private int scroll = 0;
    private EditBox newBox;
    private int newPeriod = 0;

    public TaskListScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.tasks"), UI_W, UI_H, null);
        this.meta = meta;
        this.rows = rows;
    }

    private String cost(int index)
    {
        return meta.size() > index ? meta.get(index) : "0";
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.core.tasks.subtitle", rows.size());
        // Reserve holds the reroll-price row (label at UI_H - 53, fields at UI_H - 44) clear of the list.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H, 34);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            List<String> row = rows.get(i);
            final String id = row.get(0);
            String period = row.size() > 1 ? row.get(1) : "daily";
            String name = row.size() > 2 ? row.get(2) : id;
            String goal = row.size() > 3 ? row.get(3) : "";
            String weight = row.size() > 4 ? row.get(4) : "0";
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            int delW = 52;
            int delX = rowControlRight() - delW;
            // The row reads "<name>  <goal> x<weight>": the name is what an admin recognises, the goal is what
            // they came to check, and the weight is the one number that silently decides whether it ever appears.
            rowBtn(14, ry, delX - 4 - 14, ROW_H,
                    Component.literal(tr("gui.dmz_ragnarok.core.tasks.row", periodLabel(period), name)),
                    () -> EditorScreens.act("tasks", "open", id))
                    .color(0xFFF6E27A)
                    .right(Component.literal(goal + "  x" + weight), 0xFFB0B0B0);
            btn(delX, ry, delW, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.delete"),
                    () -> EditorScreens.act("tasks", "delete", id));
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        // Reroll prices, one per board, edited in place.
        int costY = UI_H - 44;
        for (int i = 0; i < PERIODS.length; i++)
        {
            final int index = i;
            final String key = PERIODS[i];
            EditBox box = field(14 + i * 94, costY, 60, cost(i));
            box.setMaxLength(12);
            btn(78 + i * 94, costY, GuiTheme.ICON_BUTTON_SIZE, GuiTheme.ICON_BUTTON_SIZE, Component.literal("✔"),
                    () -> EditorScreens.act("tasks", "cost", key, box.getValue().trim()));
            label(tr("gui.dmz_ragnarok.core.tasks.cost", periodLabel(key)), 14 + index * 94, costY - 9, 0xFFB0B0B0);
        }

        // New task: an id and which board it starts on. The board is a cycle button rather than a dropdown
        // because there are exactly three and it saves a whole widget's worth of room in the footer.
        //
        // The footer is laid out RIGHT TO LEFT from the panel's inner edge, each control placed against the one
        // after it with a fixed gap, rather than from hand-picked absolute x values. The absolute version had
        // New at 202..246 and Menu at 238..286, so the two drew ON TOP of each other; deriving each position
        // from its neighbour makes that impossible to reintroduce by changing one width.
        final int gap = 4;
        int menuW = 48;
        int newW = 44;
        int periodW = 60;
        int menuX = UI_W - FOOTER_INSET - menuW;
        int newX = menuX - gap - newW;
        int periodX = newX - gap - periodW;
        int boxRight = periodX - gap;

        newBox = field(14, footerY() + 2, boxRight - 14, "");
        newBox.setHint(Component.translatable("gui.dmz_ragnarok.core.tasks.new_hint"));
        newBox.setMaxLength(48);
        btn(periodX, footerY(), periodW, footerBtnHeight(),
                Component.literal(periodLabel(PERIODS[newPeriod])),
                () -> { newPeriod = (newPeriod + 1) % PERIODS.length; rebuildWidgets(); });
        btn(newX, footerY(), newW, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.new"), () ->
        {
            String v = newBox.getValue().trim();
            if (!v.isBlank())
                EditorScreens.act("tasks", "new", v, PERIODS[newPeriod]);
        });
        btn(menuX, footerY(), menuW, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private static String periodLabel(String key)
    {
        return tr("gui.dmz_ragnarok.tasks.period." + key);
    }
}
