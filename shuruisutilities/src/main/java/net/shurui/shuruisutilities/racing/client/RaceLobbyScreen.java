package net.shurui.shuruisutilities.racing.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import net.minecraft.network.chat.Component;

import net.shurui.dev.sdu.api.ClientGate;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.client.gui.DmzTextureButton;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.shuruisutilities.client.gui.theme.GuiText;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.racing.net.PacketRaceLobbyAction;
import net.shurui.shuruisutilities.racing.net.PacketRaceLobbyOpen;

/**
 * The race lobby screen (packet 117, R11). It shows the track, lap count, the seconds left before the lobby
 * auto-starts, and the live roster (each member's name, a READY tag and a bot marker), and offers Ready / Leave /
 * Close buttons. It is a pure reader of {@link RaceClientState}, which the server refreshes about once a second, so the
 * countdown and roster stay live: the screen rebuilds whenever that state changes. Built on the shared GuiTheme panel,
 * buttons and scrollbar (no home-made chrome). Not a pause screen: the world keeps ticking while the lobby is up.
 */
public final class RaceLobbyScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 12;
    private static final int LIST_TOP = 68;
    // Left edge of the content column, matching the other GuiTheme editors.
    private static final int LEFT = 14;
    // Where the bot tag sits: clear of a 16 character name plus its "24. " prefix.
    private static final int BOT_TAG_X = 190;
    private static final int READY_GREEN = 0xFF54E060;
    private static final int COUNTDOWN_RED = 0xFFFF6060;

    private DmzTextureButton readyButton;
    private boolean selfReady;
    private int scroll;
    // The lobby state the widgets were last built from; a change in any of it rebuilds the screen.
    private Object shownState;

    public RaceLobbyScreen()
    {
        super(Component.translatable("gui.dmz_ragnarok.core.race.lobby.title"), UI_W, UI_H, null);
    }

    @Override
    protected void init()
    {
        super.init();
        headerName = tr("gui.dmz_ragnarok.core.race.lobby.title");
        shownState = currentState();

        String track = RaceClientState.lobbyTrackId();
        int laps = RaceClientState.lobbyLaps();
        int max = RaceClientState.lobbyMaxRacers();
        List<PacketRaceLobbyOpen.Member> members = RaceClientState.lobbyMembers();

        // Track on the left, laps right-aligned on the same line; the track id is fitted so it never meets the laps.
        int right = UI_W - LEFT;
        String lapStr = tr("gui.dmz_ragnarok.core.race.lobby.laps", laps);
        int lapW = font.width(lapStr);
        String trackStr = tr("gui.dmz_ragnarok.core.race.lobby.track", track);
        trackStr = GuiText.ellipsize(font, trackStr, Math.max(1, right - lapW - 8 - LEFT));
        label(trackStr, LEFT, 30, GuiTheme.COLOR_ROW);
        label(lapStr, right - lapW, 30, GuiTheme.COLOR_ROW);

        int secs = RaceClientState.lobbySecondsRemaining();
        String countdown = secs < 0 ? tr("gui.dmz_ragnarok.core.race.lobby.waiting")
                : secs == 0 ? tr("gui.dmz_ragnarok.core.race.lobby.starting")
                : tr("gui.dmz_ragnarok.core.race.lobby.starts_in", secs);
        labelCentered(countdown, UI_W / 2, 43, secs >= 0 && secs <= 3 ? COUNTDOWN_RED : GuiTheme.COLOR_VALUE);

        label(tr("gui.dmz_ragnarok.core.race.lobby.racers", members.size(), max), LEFT, 56, GuiTheme.COLOR_LABEL);

        // Keep the local toggle in step with the authoritative roster.
        String selfName = minecraft != null && minecraft.player != null
                ? minecraft.player.getGameProfile().getName() : "";
        boolean sawSelf = false;
        for (PacketRaceLobbyOpen.Member m : members)
            if (!m.bot() && m.name().equals(selfName))
            {
                sawSelf = true;
                selfReady = m.ready();
            }

        // The roster: one row per member, scrolling once it outgrows the space above the footer.
        int cap = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, members.size() - cap)));
        int end = Math.min(members.size(), scroll + cap);
        String readyTag = tr("gui.dmz_ragnarok.core.race.lobby.ready_tag");
        String botTag = tr("gui.dmz_ragnarok.core.race.lobby.bot_tag");
        int tagRight = rowControlRight();
        for (int i = scroll; i < end; i++)
        {
            PacketRaceLobbyOpen.Member m = members.get(i);
            int y = LIST_TOP + (i - scroll) * ROW_H + 2;
            boolean self = !m.bot() && m.name().equals(selfName);
            int nameColor = self ? GuiTheme.COLOR_TITLE : m.bot() ? GuiTheme.COLOR_MUTED : GuiTheme.COLOR_ROW;
            String name = GuiText.ellipsize(font, (i + 1) + ". " + m.name(), BOT_TAG_X - 4 - LEFT);
            label(name, LEFT, y, nameColor);
            if (m.bot())
                label(botTag, BOT_TAG_X, y, GuiTheme.COLOR_MUTED);
            if (m.ready())
                label(readyTag, tagRight - font.width(readyTag), y, READY_GREEN);
            else if (!m.bot())
                label("...", tagRight - font.width("..."), y, GuiTheme.COLOR_MUTED);
        }
        scrollList(LEFT, UI_W, LIST_TOP, ROW_H, cap, members.size(), scroll, v ->
        {
            scroll = v;
            rebuildWidgets();
        });

        // Ready / Leave / Close across the footer, centred as one group.
        int bw = 84;
        int gap = 6;
        int bx = (UI_W - (3 * bw + 2 * gap)) / 2;
        int by = footerY();
        readyButton = commitBtn(bx, by, bw, footerBtnHeight(), readyCaption(), this::toggleReady);
        readyButton.active = sawSelf;
        btn(bx + bw + gap, by, bw, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.race.lobby.leave"), () ->
        {
            NetworkUtils.sendToServer(new PacketRaceLobbyAction(PacketRaceLobbyAction.LEAVE, 0));
            onClose();
        });
        btn(bx + 2 * (bw + gap), by, bw, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.close"),
                this::onClose);
    }

    private Component readyCaption()
    {
        return Component.translatable(selfReady ? "gui.dmz_ragnarok.core.race.lobby.unready"
                : "gui.dmz_ragnarok.core.race.lobby.ready");
    }

    private void toggleReady()
    {
        selfReady = !selfReady;
        NetworkUtils.sendToServer(new PacketRaceLobbyAction(PacketRaceLobbyAction.READY, selfReady ? 1 : 0));
        if (readyButton != null)
            readyButton.setMessage(readyCaption());
    }

    // Everything the screen shows, as one comparable value (the member records compare by value).
    private static Object currentState()
    {
        return Arrays.asList(RaceClientState.lobbyTrackId(), RaceClientState.lobbyLaps(),
                RaceClientState.lobbyMaxRacers(), RaceClientState.lobbySecondsRemaining(),
                new ArrayList<>(RaceClientState.lobbyMembers()));
    }

    @Override
    public void tick()
    {
        super.tick();
        // Close if the feature vanished (disconnect) or the race left the lobby (session started clears the lobby id).
        if (!ClientGate.feature("racing"))
        {
            onClose();
            return;
        }
        // The server refreshes the lobby about once a second; rebuild only when something shown actually changed.
        if (!Objects.equals(shownState, currentState()))
            rebuildWidgets();
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
