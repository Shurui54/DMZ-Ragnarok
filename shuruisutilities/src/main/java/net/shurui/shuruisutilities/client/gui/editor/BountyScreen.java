package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.DmzDropdown;
import net.shurui.shuruisutilities.client.gui.DmzTextureButton;
import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

// bounty board: scrollable list of open bounties (name, pooled Zeni, hunter count), each with an Accept button
// (becomes disabled "Accepted" once joined; hidden for your own bounty). footer posts one via /bounty set. no
// authority here (actions dispatch as commands, server re-validates).
// rows: [targetName, formattedPool, hunterCount, viewerAccepted, viewerIsTarget].
// meta: [currency, onlineName1, ...] where 0 is the currency and the rest are online players (viewer excluded),
// which populate the target dropdown so a bounty can only be placed on someone online.
public class BountyScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;

    private final List<List<String>> rows;
    // online player names (viewer excluded), from meta[1..]; may be empty
    private final List<String> onlineNames;
    private int scroll = 0;
    private DmzDropdown targetDd;
    private EditBox amountBox;
    private String feedback = null;

    public BountyScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.menu.bounty"), UI_W, UI_H, null);
        this.rows = rows;
        // meta index 0 is the currency name (matched to how HubRowBounty.openBounty ordered it); every
        // entry after it is an online player name and becomes a target-dropdown option.
        this.onlineNames = new ArrayList<>();
        if (meta != null)
        {
            for (int i = 1; i < meta.size(); i++)
                onlineNames.add(meta.get(i));
        }
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.core.bounty.subtitle", rows.size());
        // stop the list above the feedback line (UI_H - 40) that error messages use; the target selector / amount
        // row below it sits on the footer band (UI_H - 24) and is already clear.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H, 20);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            List<String> row = rows.get(i);
            final String name = col(row, 0);
            String pool = col(row, 1);
            String hunters = col(row, 2);
            boolean accepted = Boolean.parseBoolean(col(row, 3));
            boolean isTarget = Boolean.parseBoolean(col(row, 4));
            int ry = LIST_TOP + (i - scroll) * ROW_H;

            int hc = parseInt(hunters);
            String hunterLabel = tr(hc == 1 ? "gui.dmz_ragnarok.core.bounty.hunter" : "gui.dmz_ragnarok.core.bounty.hunters", hc);
            label("§f" + name + " §7: §e" + pool + " §7(" + hunterLabel + ")",
                    14, ry + 4, 0xFFFFFFFF);

            // trailing accept control right-aligned to the scrollbar-reserved column edge
            int acceptW = 60;
            int acceptX = rowControlRight() - acceptW;
            if (isTarget)
            {
                // The viewer's own bounty: nothing to accept.
                label("§8" + tr("gui.dmz_ragnarok.core.bounty.you"), acceptX + 2, ry + 4, 0xFF808080);
            }
            else if (accepted)
            {
                btn(acceptX, ry, acceptW, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.bounty.accepted"), () -> {});
            }
            else
            {
                btn(acceptX, ry, acceptW, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.bounty.accept"), () -> {
                    EditorScreens.runCommand("bounty accept " + name);
                    EditorScreens.reopen("bounty");
                });
            }
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        if (feedback != null)
            labelCentered("§c" + tr(feedback), UI_W / 2, UI_H - 40, 0xFFFFFFFF);

        if (onlineNames.isEmpty())
        {
            // No other players are online, so there is nothing valid to target. Show a label in place
            // of the selector and leave submit() a no-op (the button below is disabled).
            targetDd = null;
            label("§7" + tr("gui.dmz_ragnarok.core.bounty.no_players"), 14, UI_H - 23, 0xFF808080);
        }
        else
        {
            // Searchable dropdown of online player names (occupies the old nameBox footprint).
            targetDd = dropdown(14, UI_H - 24, 110, options(onlineNames.toArray(new String[0])), 0).searchable();
        }
        // Footer row, laid left-to-right with no overlap and all inside the panel: target selector, amount box,
        // Set button, Menu button. Menu is pinned to the panel's inner-right edge; Set sits just left of it with a
        // gap (the reviewer's "Set bounty and Menu overlap" defect); the amount box fills the space up to Set.
        int menuW = 48;
        int menuX = UI_W - GuiTheme.CONTENT_PADDING - menuW;
        int setW = 50;
        int setX = menuX - GuiTheme.BUTTON_GAP_X - setW;
        int amountX = 128;
        int amountW = Math.max(1, setX - GuiTheme.BUTTON_GAP_X - amountX);
        amountBox = field(amountX, UI_H - 24, amountW, "");
        amountBox.setHint(Component.translatable("gui.dmz_ragnarok.core.bounty.amount_hint"));
        amountBox.setMaxLength(19);
        DmzTextureButton setBtn = btn(setX, footerY(), setW, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.bounty.set"), this::submit);
        setBtn.active = !onlineNames.isEmpty();

        btn(menuX, footerY(), menuW, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openPlayerHub);
    }

    private void submit()
    {
        // No selectable targets means the button is disabled; guard anyway so submit() is a safe no-op.
        if (targetDd == null || onlineNames.isEmpty())
            return;
        int idx = targetDd.getIndex();
        if (idx < 0 || idx >= onlineNames.size())
        {
            feedback = "gui.dmz_ragnarok.core.bounty.err_select";
            rebuildWidgets();
            return;
        }
        String name = onlineNames.get(idx);
        String amount = amountBox.getValue().trim();
        long value;
        try
        {
            value = Long.parseLong(amount);
        }
        catch (NumberFormatException e)
        {
            feedback = "gui.dmz_ragnarok.core.bounty.err_nan";
            rebuildWidgets();
            return;
        }
        if (value <= 0)
        {
            feedback = "gui.dmz_ragnarok.core.bounty.err_positive";
            rebuildWidgets();
            return;
        }
        EditorScreens.runCommand("bounty set " + name + " " + value);
        EditorScreens.reopen("bounty");
    }

    private static String col(List<String> row, int i)
    {
        return i < row.size() ? row.get(i) : "";
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
