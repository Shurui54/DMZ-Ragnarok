package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

/**
 * One tracker on one cosmetic, reached by drilling into a row of the definition's Trackers tab.
 *
 * <p>A drill-down rather than a routed screen of its own, the same way every other sub-record editor in the hub
 * framework works: the list row opens it, and Back re-opens the record.
 *
 * <p>{@code meta} is {@code [cosmeticId, index, id, label, goal, target, cap, format, goalCount, goal keys...]}.
 * The goal keys come from the SERVER's copy of the enum rather than a client-side array, which is the mistake
 * the NPC config editor made: its parallel arrays carry a comment saying they must stay in step with another
 * tree's enums forever, and a new goal kind there is two edits in two trees instead of one.
 *
 * <p>Saving REPLACES the tracker at the index it was opened at, rather than appending, which is what makes an
 * in-place edit an edit. The crate editor's reward edit does the same and for the same reason.
 */
public class CosmeticTrackerEditScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final List<String> meta;
    private final List<String> goals = new ArrayList<>();

    private String trackerId;
    private String label;
    private String goal;
    private String target;
    private String cap;
    private String format;

    public CosmeticTrackerEditScreen(List<String> meta)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.cosmetics.tracker_title"), UI_W, UI_H, null);
        this.meta = meta;
        this.trackerId = at(2, "");
        this.label = at(3, "");
        this.goal = at(4, "kill");
        this.target = at(5, "");
        this.cap = at(6, "0");
        this.format = at(7, "%s");
        int count = parse(at(8, "0"));
        for (int i = 0; i < count && 9 + i < meta.size(); i++)
            goals.add(meta.get(9 + i));
        if (goals.isEmpty())
            goals.add(goal);
    }

    private String at(int index, String fallback)
    {
        return meta.size() > index ? meta.get(index) : fallback;
    }

    private static int parse(String s)
    {
        try
        {
            return Integer.parseInt(s.trim());
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    private String cosmeticId()
    {
        return at(0, "");
    }

    private String index()
    {
        return at(1, "-1");
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        headerName = trackerId;
        rowY = 34;

        // The tracker ID is editable here, unlike a cosmetic id: it is referenced only by the wearer's chosen
        // tracker field, so renaming one costs that choice and nothing else. The field is still worth having,
        // because a tracker added with a typo would otherwise have to be deleted and rebuilt.
        tf(tr("gui.dmz_ragnarok.core.cosmetics.tracker_id"), trackerId, v -> trackerId = v);
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_tracker_id"));
        tf(tr("gui.dmz_ragnarok.core.cosmetics.tracker_label"), label, v -> label = v);
        df(tr("gui.dmz_ragnarok.core.cosmetics.tracker_goal"), goals, goal, v -> goal = v.isBlank() ? goal : v);
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_tracker_goal"));
        tf(tr("gui.dmz_ragnarok.core.cosmetics.tracker_target"), target, v -> target = v);
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_tracker_target"));
        tf(tr("gui.dmz_ragnarok.core.cosmetics.tracker_cap"), cap, v -> cap = v);
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_tracker_cap"));
        tf(tr("gui.dmz_ragnarok.core.cosmetics.tracker_format"), format, v -> format = v);
        tip(tr("gui.dmz_ragnarok.core.cosmetics.tip_tracker_format"));

        // Said out loud rather than left to be discovered: the condition model is real and persisted, but
        // nothing increments it until the counter lands in a later milestone.
        label(tr("gui.dmz_ragnarok.core.cosmetics.tracker_note"), 14, rowY + 6, 0xFFB0B0B0);

        btn(UI_W / 2 - 104, footerY(), 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.btn.save"), this::save);
        btn(UI_W / 2 + 4, footerY(), 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                () -> EditorScreens.act("cosmetics_admin", "open", cosmeticId()));
    }

    private void save()
    {
        applyFields();
        EditorScreens.act("cosmetics_admin", "savetracker", cosmeticId(), index(), trackerId, label, goal, target,
                cap, format);
    }
}
