package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.shuruisutilities.tasks.TaskGoal;
import net.shurui.shuruisutilities.tasks.TaskTargets;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * One task definition.
 *
 * <p>{@code meta} arrives in the order the server writes it and is sent back in the same order, so the two halves
 * are one contract: id, period, name, description, goal, target, amount, zeni, tp, weight. Reward commands follow
 * as {@code rows}, one per entry.
 *
 * <p>The ID IS NOT EDITABLE. It is the key every player's slot references, so renaming one here would silently
 * orphan every row in the world that had rolled it. Changing an id is deliberately a delete plus a new task, which
 * at least makes the loss visible.
 */
public class TaskEditScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    /** Board keys, cycled by the period button. Must match TaskPeriod's declaration order. */
    private static final String[] PERIODS = { "daily", "weekly", "monthly" };

    /** Goal keys, cycled by the goal button. Must match TaskGoal's keys. */
    private static final String[] GOALS = { "kill", "collect", "mine", "craft", "visit", "earn_zeni" };

    private final List<String> meta;
    private final List<List<String>> commandRows;

    private String period;
    private String goal;
    private EditBox nameBox;
    private EditBox descBox;
    private EditBox targetBox;
    /** Ids parallel to the target dropdown's rows: ANY first, curated ids, then the CUSTOM sentinel. */
    private List<String> targetChoices = new ArrayList<>();
    private net.shurui.shuruisutilities.client.gui.DmzDropdown targetDropdown;
    /** True while the CUSTOM row is chosen, which is what makes the free-text box exist. */
    private boolean targetCustom;
    /** The value being edited, kept here so it survives the rebuild a dropdown selection triggers. */
    private String targetValue = "";
    private EditBox amountBox;
    private EditBox zeniBox;
    private EditBox tpBox;
    private EditBox weightBox;
    private EditBox commandBox;

    public TaskEditScreen(List<String> meta, List<List<String>> commandRows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.tasks.edit_title"), UI_W, UI_H, null);
        this.meta = meta;
        this.commandRows = commandRows;
        this.period = at(1, "daily");
        this.goal = at(4, "kill");
        this.targetValue = at(5, "");
    }

    private String at(int index, String fallback)
    {
        return meta.size() > index ? meta.get(index) : fallback;
    }

    /** The target as it stands: the box when custom, otherwise the dropdown's id. Never the CUSTOM sentinel. */
    private String currentTarget()
    {
        if (targetBox != null)
        {
            return targetBox.getValue().trim();
        }
        return targetValue == null || TaskTargets.CUSTOM.equals(targetValue) ? "" : targetValue.trim();
    }

    @Override
    protected void onDropdownSelect(net.shurui.shuruisutilities.client.gui.DmzDropdown dropdown, int row)
    {
        if (dropdown != targetDropdown || row < 0 || row >= targetChoices.size())
        {
            super.onDropdownSelect(dropdown, row);
            return;
        }
        String picked = targetChoices.get(row);
        if (TaskTargets.CUSTOM.equals(picked))
        {
            // Keep whatever was already typed rather than blanking it, so switching to custom to make a small
            // edit does not throw the existing id away.
            targetCustom = true;
        }
        else
        {
            targetCustom = false;
            targetValue = picked;
        }
        rebuildWidgets();
    }

    private String id()
    {
        return at(0, "");
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = id();

        int y = 34;
        label(tr("gui.dmz_ragnarok.core.tasks.field_period"), 14, y + 2, 0xFFB0B0B0);
        btn(150, y, 132, GuiTheme.BUTTON_HEIGHT, Component.literal(tr("gui.dmz_ragnarok.tasks.period." + period)),
                () -> { period = next(PERIODS, period); rebuildWidgets(); });
        y += 18;

        label(tr("gui.dmz_ragnarok.core.tasks.field_name"), 14, y + 2, 0xFFB0B0B0);
        nameBox = field(150, y, 132, at(2, ""));
        nameBox.setMaxLength(64);
        y += 18;

        label(tr("gui.dmz_ragnarok.core.tasks.field_desc"), 14, y + 2, 0xFFB0B0B0);
        descBox = field(150, y, 132, at(3, ""));
        descBox.setMaxLength(128);
        y += 18;

        label(tr("gui.dmz_ragnarok.core.tasks.field_goal"), 14, y + 2, 0xFFB0B0B0);
        btn(150, y, 132, GuiTheme.BUTTON_HEIGHT, Component.literal(tr("gui.dmz_ragnarok.tasks.goal." + goal)),
                () -> { goal = next(GOALS, goal); rebuildWidgets(); });
        y += 18;

        // Target and amount are greyed by MEANING, not by taste: earn_zeni has nothing to name and visit happens
        // once, so leaving those boxes live would invite an admin to fill in a number that is then ignored.
        boolean usesTarget = !"earn_zeni".equals(goal);
        boolean usesAmount = !"visit".equals(goal);

        // Target is a DROPDOWN of the ids tasks are actually written against, with "any" at the top and a
        // "custom" row at the bottom that hands the value back to the free-text field. The list is curated
        // rather than the whole registry (see TaskTargets): a thousand-entry block dropdown would be worse than
        // the text box it replaced, and nothing is locked out because "custom" always takes anything.
        label(tr("gui.dmz_ragnarok.core.tasks.field_target"), 14, y + 2, usesTarget ? 0xFFB0B0B0 : 0xFF5A5A5A);
        TaskGoal goalKind = TaskGoal.byKey(goal);
        targetChoices = new ArrayList<>();
        List<Component> targetLabels = new ArrayList<>();
        if (usesTarget)
        {
            targetChoices.add(TaskTargets.ANY);
            targetLabels.add(Component.literal(tr("gui.dmz_ragnarok.core.tasks.target_any")));
            for (String id : TaskTargets.optionsFor(goalKind))
            {
                targetChoices.add(id);
                targetLabels.add(Component.literal(id));
            }
            targetChoices.add(TaskTargets.CUSTOM);
            targetLabels.add(Component.literal(tr("gui.dmz_ragnarok.core.tasks.target_custom")));

            // A value that is not on the list is not an error, it is somebody's custom id: land on "custom" and
            // show it in the box rather than silently snapping their task to the first entry.
            int idx = targetChoices.indexOf(targetValue);
            if (idx < 0)
            {
                idx = targetChoices.size() - 1;
                targetCustom = true;
            }
            targetDropdown = dropdown(150, y, 132, targetLabels, idx);
            y += 14;
        }

        // The free-text box only exists while "custom" is chosen, so the row never shows two ways to set one
        // value at once.
        if (usesTarget && targetCustom)
        {
            targetBox = field(150, y, 132, targetValue);
            targetBox.setMaxLength(96);
            y += 18;
        }
        else
        {
            targetBox = null;
            y += 4;
        }

        label(tr("gui.dmz_ragnarok.core.tasks.field_amount"), 14, y + 2, usesAmount ? 0xFFB0B0B0 : 0xFF5A5A5A);
        amountBox = field(150, y, 60, at(6, "1"));
        amountBox.setMaxLength(9);
        amountBox.setEditable(usesAmount);
        y += 18;

        label(tr("gui.dmz_ragnarok.core.tasks.field_zeni"), 14, y + 2, 0xFFB0B0B0);
        zeniBox = field(150, y, 60, at(7, "0"));
        zeniBox.setMaxLength(12);
        label(tr("gui.dmz_ragnarok.core.tasks.field_tp"), 214, y + 2, 0xFFB0B0B0);
        tpBox = field(246, y, 36, at(8, "0"));
        tpBox.setMaxLength(9);
        y += 18;

        label(tr("gui.dmz_ragnarok.core.tasks.field_weight"), 14, y + 2, 0xFFB0B0B0);
        weightBox = field(150, y, 60, at(9, "10"));
        weightBox.setMaxLength(6);
        y += 20;

        // Reward commands. One box adds; each existing one gets a remove. Kept to a summary line plus an add box
        // rather than a scrolling sub-list, because a task with more than a handful of commands is better served
        // by one command that runs a function.
        label(tr("gui.dmz_ragnarok.core.tasks.field_commands", commandRows.size()), 14, y + 2, 0xFFB0B0B0);
        commandBox = field(150, y, 100, "");
        commandBox.setHint(Component.translatable("gui.dmz_ragnarok.core.tasks.command_hint"));
        commandBox.setMaxLength(200);
        btn(254, y, 28, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.add"), () ->
        {
            String v = commandBox.getValue().trim();
            if (!v.isBlank())
            {
                commandRows.add(List.of(v));
                save();
            }
        });
        y += 16;
        if (!commandRows.isEmpty())
        {
            // Only the last one is offered for removal, for the same reason as above: the list is meant to stay
            // short, and a full editor for it would cost more room than the feature is worth.
            String last = commandRows.get(commandRows.size() - 1).get(0);
            btn(150, y, 132, GuiTheme.BUTTON_HEIGHT,
                    Component.literal(tr("gui.dmz_ragnarok.core.tasks.command_drop", trim(last, 22))), () ->
                    {
                        commandRows.remove(commandRows.size() - 1);
                        save();
                    });
        }

        btn(UI_W / 2 - 104, footerY(), 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.btn.save"), this::save);
        btn(UI_W / 2 + 4, footerY(), 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                () -> EditorScreens.act("tasks", "back"));
    }

    private static String next(String[] values, String current)
    {
        for (int i = 0; i < values.length; i++)
            if (values[i].equals(current))
                return values[(i + 1) % values.length];
        return values[0];
    }

    private static String trim(String s, int max)
    {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Send every field back in the order the server reads them, commands last. */
    private void save()
    {
        List<String> args = new ArrayList<>();
        args.add(id());
        args.add(period);
        args.add(nameBox.getValue());
        args.add(descBox.getValue());
        args.add(goal);
        args.add(currentTarget());
        args.add(amountBox.getValue().trim());
        args.add(zeniBox.getValue().trim());
        args.add(tpBox.getValue().trim());
        args.add(weightBox.getValue().trim());
        for (List<String> row : commandRows)
            if (!row.isEmpty())
                args.add(row.get(0));
        EditorScreens.act("tasks", "save", args);
    }
}
