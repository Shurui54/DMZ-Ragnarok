package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.staff.StaffTask;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * One task, in full: what it says, who holds it, and the buttons this viewer has earned.
 *
 * <p>Built entirely from the row the list already had, so opening a task costs no round trip. Acting on one does
 * go to the server, which re-checks the viewer's role, clock and ownership before applying anything and then
 * re-sends the board.
 */
public class StaffTaskDetailScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final List<String> meta;
    private final List<String> row;
    private EditBox denyReason;

    public StaffTaskDetailScreen(List<String> meta, List<String> row, net.minecraft.client.gui.screens.Screen parent)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.menu.stafftasks"), UI_W, UI_H, parent);
        this.meta = meta;
        this.row = row;
    }

    @Override
    protected void init()
    {
        super.init();
        String id = StaffTaskScreen.col(row, 0);
        String title = StaffTaskScreen.col(row, 1);
        String description = StaffTaskScreen.col(row, 2);
        String status = StaffTaskScreen.col(row, 3);
        String holder = StaffTaskScreen.col(row, 4);
        String roles = StaffTaskScreen.col(row, 5);
        boolean canAccept = Boolean.parseBoolean(StaffTaskScreen.col(row, 6));
        boolean canComplete = Boolean.parseBoolean(StaffTaskScreen.col(row, 7));
        boolean canJudge = Boolean.parseBoolean(StaffTaskScreen.col(row, 8));
        String denial = StaffTaskScreen.col(row, 9);
        boolean canAssign = Boolean.parseBoolean(StaffTaskScreen.col(meta, 3));

        int y = 34;
        label(StaffTaskScreen.statusColor(status) + title, 14, y, 0xFFFFFFFF);
        y += 12;
        label("§8" + roles + (holder.isEmpty() ? "" : " §7- " + holder), 14, y, 0xFFFFFFFF);
        y += 14;

        // The description is the point of this screen, so it wraps to as many lines as it needs rather than
        // being clipped to one. Wrapped into plain strings and handed to label(), because that is the pipeline
        // the scaled panel draws through; drawing directly would have to redo the scale transform by hand.
        for (String line : wrap(description, UI_W - 32))
        {
            label("§7" + line, 14, y, 0xFFC8C8C8);
            y += 9;
        }

        if (!denial.isEmpty())
        {
            y += 4;
            for (String line : wrap(denial, UI_W - 44))
            {
                label("§c" + line, 14, y, 0xFFE08080);
                y += 9;
            }
        }

        // Actions, right to left along the footer so the destructive one is never where Back was a moment ago.
        int backW = 48;
        int backX = UI_W - GuiTheme.CONTENT_PADDING - backW;
        btn(backX, footerY(), backW, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.btn.back"), this::back);

        int nextX = backX;
        if (canAccept)
            nextX = actionBtn(nextX, "gui.dmz_ragnarok.core.stafftasks.accept", () -> act("accept", id));
        if (canComplete)
        {
            nextX = actionBtn(nextX, "gui.dmz_ragnarok.core.stafftasks.complete", () -> act("complete", id));
            nextX = actionBtn(nextX, "gui.dmz_ragnarok.core.stafftasks.drop", () -> act("drop", id));
        }
        if (canJudge)
        {
            nextX = actionBtn(nextX, "gui.dmz_ragnarok.core.stafftasks.approve", () -> act("approve", id));
            nextX = actionBtn(nextX, "gui.dmz_ragnarok.core.stafftasks.deny",
                    () -> act("deny", id, denyReason == null ? "" : denyReason.getValue().trim()));
            // The reason sits above the row it belongs to, because a denial without one tells the person nothing.
            denyReason = field(14, footerY() - 20, 150, "");
            denyReason.setHint(Component.translatable("gui.dmz_ragnarok.core.stafftasks.deny_hint"));
            denyReason.setMaxLength(200);
        }
        if (canAssign)
            actionBtn(nextX, "gui.dmz_ragnarok.core.stafftasks.delete", () -> act("delete", id));
    }

    private int actionBtn(int rightOf, String key, Runnable onPress)
    {
        int w = 58;
        int x = rightOf - GuiTheme.BUTTON_GAP_X - w;
        btn(x, footerY(), w, footerBtnHeight(), Component.translatable(key), onPress);
        return x;
    }

    private void act(String action, String... args)
    {
        EditorScreens.act(StaffTask.EDITOR, action, args);
    }

    /** Greedy word wrap to a pixel width, so a long description reads as paragraphs instead of running off. */
    private List<String> wrap(String text, int width)
    {
        List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isBlank())
            return out;
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+"))
        {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (font.width(candidate) > width && line.length() > 0)
            {
                out.add(line.toString());
                line = new StringBuilder(word);
            }
            else
            {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0)
            out.add(line.toString());
        return out;
    }
}
