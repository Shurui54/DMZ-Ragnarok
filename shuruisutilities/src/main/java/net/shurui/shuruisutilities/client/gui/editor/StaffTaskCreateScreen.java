package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.DmzDropdown;
import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.staff.StaffTask;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Writing a task: a title, a description, and who it is for.
 *
 * <p>The role list comes from the server in {@code meta}, so this screen never has its own copy of what the roles
 * are and cannot fall out of step with the roster.
 *
 * <p>One role or Any, chosen from a dropdown. Assigning a single job to several roles at once is possible but rare,
 * and the command form ({@code /staff addtask builder,mod ...}) covers it without putting a multi-select in the way
 * of the common case.
 */
public class StaffTaskCreateScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final String ANY = "Any";

    private final List<String> meta;
    private final List<String> roleOptions = new ArrayList<>();
    private EditBox titleBox;
    private EditBox descBox;
    private DmzDropdown roleDd;
    private String feedback = null;

    public StaffTaskCreateScreen(List<String> meta, Screen parent)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.stafftasks.new"), UI_W, UI_H, parent);
        this.meta = meta == null ? new ArrayList<>() : meta;

        // meta[4] is how many role names follow it, so the list end is stated rather than inferred.
        roleOptions.add(ANY);
        int count = parseInt(StaffTaskScreen.col(this.meta, 4));
        for (int i = 0; i < count; i++)
        {
            String role = StaffTaskScreen.col(this.meta, 5 + i);
            if (!role.isEmpty())
                roleOptions.add(role);
        }
    }

    @Override
    protected void init()
    {
        super.init();
        int y = 40;
        label("§7" + tr("gui.dmz_ragnarok.core.stafftasks.title_label"), 14, y, 0xFFCFE8B0);
        titleBox = field(14, y + 10, UI_W - 28, titleBox == null ? "" : titleBox.getValue());
        titleBox.setMaxLength(80);

        y += 36;
        label("§7" + tr("gui.dmz_ragnarok.core.stafftasks.desc_label"), 14, y, 0xFFCFE8B0);
        descBox = field(14, y + 10, UI_W - 28, descBox == null ? "" : descBox.getValue());
        descBox.setMaxLength(400);

        y += 36;
        label("§7" + tr("gui.dmz_ragnarok.core.stafftasks.role_label"), 14, y, 0xFFCFE8B0);
        roleDd = dropdown(14, y + 10, 120,
                options(roleOptions.toArray(new String[0])), roleDd == null ? 0 : roleDd.getIndex());

        if (feedback != null)
            labelCentered("§c" + tr(feedback), UI_W / 2, UI_H - 40, 0xFFFFFFFF);

        int backW = 48;
        int backX = UI_W - GuiTheme.CONTENT_PADDING - backW;
        btn(backX, footerY(), backW, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.btn.back"), this::back);
        int createW = 62;
        int createX = backX - GuiTheme.BUTTON_GAP_X - createW;
        commitBtn(createX, footerY(), createW, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.stafftasks.create"), this::submit);
    }

    private void submit()
    {
        String title = titleBox.getValue().trim();
        if (title.isEmpty())
        {
            feedback = "gui.dmz_ragnarok.core.stafftasks.err_title";
            rebuildWidgets();
            return;
        }
        int idx = roleDd == null ? 0 : roleDd.getIndex();
        String role = idx <= 0 || idx >= roleOptions.size() ? "" : roleOptions.get(idx);
        // The first argument slot is the task id everywhere else on this editor, so it is sent empty here to keep
        // the argument positions the same for every action.
        EditorScreens.act(StaffTask.EDITOR, "create", "", title, descBox.getValue().trim(), role);
    }

    private static int parseInt(String s)
    {
        try
        {
            return Integer.parseInt(s.trim());
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }
}
