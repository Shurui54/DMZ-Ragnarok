package net.shurui.shuruisutilities.racing.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;

import net.shurui.dev.sdu.api.ClientGate;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.shuruisutilities.client.gui.theme.GuiText;
import net.shurui.shuruisutilities.racing.net.PacketRaceResults;

/**
 * The race results screen (packet 113, R11): the final standings with each racer's place, name, race time and best
 * lap this race, and a RECORD tag for anyone who set a new personal best (best lap or best race). It is a pure reader
 * of {@link RaceClientState#results()} and closes itself once the results window passes (the race then cleans up and
 * teleports the racer back), or on the Continue button. Built on the shared GuiTheme panel, buttons and scrollbar.
 * Not a pause screen.
 */
public final class RaceResultsScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 12;
    private static final int HEAD_Y = 30;
    private static final int LIST_TOP = 42;
    // Column x positions: place, racer name, best lap, race time; the record tag is right-aligned to the row edge.
    private static final int COL_PLACE = 14;
    private static final int COL_NAME = 30;
    private static final int COL_BEST = 150;
    private static final int COL_TIME = 204;
    private static final int GOLD = 0xFFFFD23F;
    private static final int SILVER = 0xFFC0C0C0;
    private static final int BRONZE = 0xFFCD7F32;
    private static final int DNF_RED = 0xFFFF6060;
    private static final int RECORD_GREEN = 0xFF54E060;

    private int scroll;
    // The results the widgets were last built from (they can land just after the screen opens).
    private List<PacketRaceResults.Entry> shown;

    public RaceResultsScreen()
    {
        super(Component.translatable("gui.dmz_ragnarok.core.race.results.title"), UI_W, UI_H, null);
    }

    @Override
    protected void init()
    {
        super.init();
        headerName = tr("gui.dmz_ragnarok.core.race.results.title");
        List<PacketRaceResults.Entry> entries = RaceClientState.results();
        shown = new ArrayList<>(entries);

        // Column headers.
        label("#", COL_PLACE, HEAD_Y, GuiTheme.COLOR_LABEL);
        label(tr("gui.dmz_ragnarok.core.race.results.col_racer"), COL_NAME, HEAD_Y, GuiTheme.COLOR_LABEL);
        label(tr("gui.dmz_ragnarok.core.race.results.col_best"), COL_BEST, HEAD_Y, GuiTheme.COLOR_LABEL);
        label(tr("gui.dmz_ragnarok.core.race.results.col_time"), COL_TIME, HEAD_Y, GuiTheme.COLOR_LABEL);

        // One row per racer; the legend line sits under the list, so hold a row clear for it.
        int cap = rowsThatFit(LIST_TOP, ROW_H, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, entries.size() - cap)));
        int end = Math.min(entries.size(), scroll + cap);
        String rec = tr("gui.dmz_ragnarok.core.race.results.rec");
        int recX = rowControlRight() - font.width(rec);
        for (int i = scroll; i < end; i++)
        {
            PacketRaceResults.Entry e = entries.get(i);
            int y = LIST_TOP + (i - scroll) * ROW_H + 2;
            int placeColor = e.dnf() ? DNF_RED : e.place() == 1 ? GOLD : e.place() == 2 ? SILVER
                    : e.place() == 3 ? BRONZE : GuiTheme.COLOR_ROW;
            label(e.dnf() ? "-" : String.valueOf(e.place()), COL_PLACE, y, placeColor);
            label(GuiText.ellipsize(font, e.name(), COL_BEST - 4 - COL_NAME), COL_NAME, y, placeColor);
            label(e.bestLapTicks() > 0 ? time(e.bestLapTicks()) : "--", COL_BEST, y, GuiTheme.COLOR_ROW);
            label(e.dnf() ? tr("gui.dmz_ragnarok.core.race.results.dnf") : time(e.timeTicks()), COL_TIME, y,
                    e.dnf() ? DNF_RED : GuiTheme.COLOR_ROW);
            if (e.newRecord())
                label(rec, recX, y, RECORD_GREEN);
        }
        scrollList(COL_PLACE, UI_W, LIST_TOP, ROW_H, cap, entries.size(), scroll, v ->
        {
            scroll = v;
            rebuildWidgets();
        });

        // A small legend for the record tag, just under the list.
        label(tr("gui.dmz_ragnarok.core.race.results.legend"), COL_PLACE, LIST_TOP + cap * ROW_H + 2,
                GuiTheme.COLOR_MUTED);

        btn(UI_W / 2 - 50, footerY(), 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.race.results.continue"), this::onClose);
    }

    @Override
    public void tick()
    {
        super.tick();
        // The results window is 8s; when it passes (or the feature vanished), the race cleans up, so close.
        if (!ClientGate.feature("racing") || !RaceClientState.resultsActive())
        {
            onClose();
            return;
        }
        if (!shown.equals(RaceClientState.results()))
            rebuildWidgets();
    }

    private static String time(int ticks)
    {
        int totalTenths = ticks * 10 / 20;
        int minutes = totalTenths / 600;
        int seconds = (totalTenths / 10) % 60;
        int tenths = totalTenths % 10;
        return String.format("%d:%02d.%d", minutes, seconds, tenths);
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
