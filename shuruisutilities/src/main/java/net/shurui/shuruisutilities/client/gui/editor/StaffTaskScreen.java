package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

/**
 * The staff task board: every job this person can see, one per row.
 *
 * <p>The row is the job, not a summary of it. Clicking one opens {@link StaffTaskDetailScreen}, which is where the
 * description and the buttons live, so the list stays scannable at a glance and the detail has room to be read.
 *
 * <p>No authority here. Every row arrives with the flags saying which actions it may offer, and each action is
 * re-checked server-side when it is sent, so this only decides what to draw.
 *
 * <p>rows: [shortId, title, description, status, acceptedByName, roles, canAccept, canComplete, canJudge, denial].
 * meta: [roleDisplay, clockedIn, canReview, canAssign, roleCount, role...].
 */
public class StaffTaskScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;

    private final List<String> meta;
    private final List<List<String>> rows;
    private final boolean canAssign;
    private int scroll = 0;

    public StaffTaskScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.menu.stafftasks"), UI_W, UI_H, null);
        this.meta = meta == null ? new ArrayList<>() : meta;
        this.rows = rows == null ? new ArrayList<>() : rows;
        this.canAssign = Boolean.parseBoolean(col(this.meta, 3));
    }

    @Override
    protected void init()
    {
        super.init();
        // Fill the shared canvas rather than the old fixed cap; nothing but the footer buttons sits below the list.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            List<String> row = rows.get(i);
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            String status = col(row, 3);
            String title = col(row, 1);

            // Status is carried by the title's colour rather than by a second column of words: the four states are
            // few enough to learn, and a words column would push the titles into a width nobody can read.
            label(statusColor(status) + title + " §8" + col(row, 5), 14, ry + 4, 0xFFFFFFFF);

            int openW = 52;
            int openX = rowControlRight() - openW;
            final List<String> selected = row;
            btn(openX, ry, openW, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.stafftasks.open"),
                    () -> minecraft.setScreen(new StaffTaskDetailScreen(meta, selected, this)));
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll,
                v -> { scroll = v; rebuildWidgets(); });

        int menuW = 48;
        int menuX = UI_W - GuiTheme.CONTENT_PADDING - menuW;
        // Moving the on-screen reminder belongs here, on the screen that produced it, rather than only on a
        // keybind nobody has any reason to know about. Opens the shared mover, so the region panel is visible
        // alongside it and the two can be placed against each other in one go.
        btn(6, footerY(), 74, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.stafftasks.move_hud"),
                () -> minecraft.setScreen(new net.shurui.shuruisutilities.client.hud.HudMoveScreen()));
        if (canAssign)
        {
            int newW = 62;
            int newX = menuX - GuiTheme.BUTTON_GAP_X - newW;
            btn(newX, footerY(), newW, footerBtnHeight(),
                    Component.translatable("gui.dmz_ragnarok.core.stafftasks.new"),
                    () -> minecraft.setScreen(new StaffTaskCreateScreen(meta, this)));
        }
        btn(menuX, footerY(), menuW, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openPlayerHub);
    }

    /** Open is grey, taken is white, submitted is yellow, approved is green. */
    static String statusColor(String status)
    {
        return switch (status)
        {
            case "ACCEPTED" -> "§f";
            case "COMPLETED" -> "§e";
            case "APPROVED" -> "§a";
            default -> "§7";
        };
    }

    static String col(List<String> row, int i)
    {
        return row != null && i < row.size() ? row.get(i) : "";
    }
}
