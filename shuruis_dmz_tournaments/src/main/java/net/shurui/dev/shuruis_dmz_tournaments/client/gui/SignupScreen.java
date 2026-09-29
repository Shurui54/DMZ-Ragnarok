package net.shurui.dev.shuruis_dmz_tournaments.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_dmz_tournaments.network.SignupActionPacket;
import net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentFormat;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentInstance;
import net.shurui.dev.shuruis_dmz_tournaments.util.TextUtil;

import java.util.List;

/**
 * The sign-up NPC screen for one tournament, styled to match the sibling {@code sdu} mod. SOLO and FFA
 * show Sign Up / Withdraw / Waiting / Status. Team formats (2v2, 3v3) also let the player create, join,
 * or leave a named team; the server re-sends this screen after each team action so the roster stays current.
 */
public class SignupScreen extends ScaledScreen {
    private static final int UI_W = 190;
    private static final int GOLD = 0xFFF6E27A;
    private static final int MAX_TEAM_ROWS = 6;
    private static final int ROW_STEP = 22;
    /** Y of the tournament-name header line, clearing the fixed logo header's lower edge. */
    private static final int HEADER_TEXT_TOP = 28;
    private static final int TEAM_LIST_TOP = 88;   // y of the first team row (below the name field)

    private int teamScroll = 0;

    private final String defId;
    private final String tournamentName;
    private final boolean signupOpen;
    private final boolean signedUp;
    private final int count;
    private final int stateOrdinal;
    private final TournamentFormat format;
    private final int teamSize;
    private final List<TournamentInstance.TeamView> teams;

    private EditBox teamNameBox;

    public SignupScreen(String defId, String tournamentName, boolean signupOpen, boolean signedUp, int count,
                        int stateOrdinal, int formatOrdinal, int teamSize, List<TournamentInstance.TeamView> teams) {
        super(Component.literal(tournamentName), UI_W, heightFor(formatOrdinal, teams));
        this.defId = defId;
        this.tournamentName = tournamentName;
        this.signupOpen = signupOpen;
        this.signedUp = signedUp;
        this.count = count;
        this.stateOrdinal = stateOrdinal;
        TournamentFormat[] fmts = TournamentFormat.values();
        this.format = (formatOrdinal >= 0 && formatOrdinal < fmts.length) ? fmts[formatOrdinal] : TournamentFormat.SOLO;
        this.teamSize = teamSize;
        this.teams = teams == null ? List.of() : teams;
    }

    private static int heightFor(int formatOrdinal, List<TournamentInstance.TeamView> teams) {
        TournamentFormat[] fmts = TournamentFormat.values();
        TournamentFormat f = (formatOrdinal >= 0 && formatOrdinal < fmts.length) ? fmts[formatOrdinal] : TournamentFormat.SOLO;
        if (!f.isTeam()) return 202;
        int rows = Math.min(MAX_TEAM_ROWS, teams == null ? 0 : teams.size());
        // logo header + name/state lines + name field (top) + team rows + the four action buttons + margin.
        return 188 + ROW_STEP * rows;
    }

    @Override
    protected void init() {
        super.init();
        int w = 130;
        int x = (UI_W - w) / 2;

        if (format.isTeam()) {
            initTeam(x, w);
        } else {
            initSolo(x, w);
        }
    }

    private void initSolo(int x, int w) {
        int y = 74;
        DmzTextureButton join = button(x, y, w, Component.translatable("gui.dmz_ragnarok.tournaments.signup.sign_up"),
                () -> { send(SignupActionPacket.JOIN); onClose(); });
        join.active = signupOpen && !signedUp;
        addRenderableWidget(join);

        DmzTextureButton leave = button(x, y + 24, w, Component.translatable("gui.dmz_ragnarok.tournaments.signup.withdraw"),
                () -> { send(SignupActionPacket.LEAVE); onClose(); });
        leave.active = signedUp;
        addRenderableWidget(leave);

        addRenderableWidget(button(x, y + 48, w, Component.translatable("gui.dmz_ragnarok.tournaments.signup.to_waiting"),
                () -> { send(SignupActionPacket.TPWAIT); onClose(); }));
        addRenderableWidget(button(x, y + 72, w, Component.translatable("gui.dmz_ragnarok.tournaments.signup.status"),
                () -> send(SignupActionPacket.STATUS)));
        addRenderableWidget(button(x, y + 100, w, Component.translatable("gui.dmz_ragnarok.tournaments.common.close"), this::onClose));
    }

    private void initTeam(int x, int w) {
        int y = 66;
        teamNameBox = new EditBox(this.font, x, y, w - 46, 14, Component.translatable("gui.dmz_ragnarok.tournaments.signup.team_name"));
        teamNameBox.setMaxLength(24);
        teamNameBox.setHint(Component.translatable("gui.dmz_ragnarok.tournaments.signup.team_name_hint"));
        addRenderableWidget(teamNameBox);
        DmzTextureButton create = button(x + w - 42, y - 1, 42, Component.translatable("gui.dmz_ragnarok.tournaments.signup.create"),
                () -> send(SignupActionPacket.CREATE_TEAM, teamNameBox.getValue(), 0));
        create.active = signupOpen;
        addRenderableWidget(create);

        // existing teams: a scrollable window of Join buttons (wheel or ▲/▼)
        boolean scrollable = teams.size() > MAX_TEAM_ROWS;
        int maxScroll = Math.max(0, teams.size() - MAX_TEAM_ROWS);
        teamScroll = Math.max(0, Math.min(teamScroll, maxScroll));
        int shown = Math.min(MAX_TEAM_ROWS, teams.size());
        int listBtnW = scrollable ? w - 14 : w;
        y = TEAM_LIST_TOP;
        for (int r = 0; r < shown; r++) {
            TournamentInstance.TeamView t = teams.get(teamScroll + r);
            boolean full = t.members().size() >= teamSize;
            DmzTextureButton joinBtn = button(x, y, listBtnW, Component.translatable(
                    "gui.dmz_ragnarok.tournaments.signup.join_team", t.name(), t.members().size(), teamSize),
                    () -> send(SignupActionPacket.JOIN_TEAM, "", t.id()));
            joinBtn.active = signupOpen && !full;
            addRenderableWidget(joinBtn);
            y += ROW_STEP;
        }
        if (scrollable) {
            int ax = x + w - 12;
            DmzTextureButton up = button(ax, TEAM_LIST_TOP, 12, Component.literal("▲"), () -> scrollTeams(-1));
            up.active = teamScroll > 0;
            addRenderableWidget(up);
            DmzTextureButton down = button(ax, y - ROW_STEP, 12, Component.literal("▼"), () -> scrollTeams(1));
            down.active = teamScroll < maxScroll;
            addRenderableWidget(down);
        }
        y += 4;

        DmzTextureButton solo = button(x, y, w, Component.translatable("gui.dmz_ragnarok.tournaments.signup.sign_up_solo"),
                () -> { send(SignupActionPacket.JOIN); onClose(); });
        solo.active = signupOpen && !signedUp;
        addRenderableWidget(solo);
        y += 22;
        addRenderableWidget(button(x, y, w, Component.translatable("gui.dmz_ragnarok.tournaments.signup.leave_team"),
                () -> send(SignupActionPacket.LEAVE_TEAM, "", 0)));
        y += 22;
        addRenderableWidget(button(x, y, w, Component.translatable("gui.dmz_ragnarok.tournaments.signup.withdraw_status"),
                () -> send(SignupActionPacket.STATUS)));
        y += 22;
        addRenderableWidget(button(x, y, w, Component.translatable("gui.dmz_ragnarok.tournaments.common.close"), this::onClose));
    }

    private DmzTextureButton button(int x, int y, int w, Component label, Runnable onPress) {
        return new DmzTextureButton(x, y, w, 18, label,
                DmzTextures.MENU_BIG, DmzTextures.BUTTON_U, DmzTextures.BUTTON_V,
                DmzTextures.BUTTON_W, DmzTextures.BUTTON_H, DmzTextures.ATLAS, onPress);
    }

    private void send(int action) {
        TournamentNet.sendToServer(new SignupActionPacket(defId, action));
    }

    private void send(int action, String arg, int extra) {
        TournamentNet.sendToServer(new SignupActionPacket(defId, action, arg, extra));
    }

    private void scrollTeams(int delta) {
        int maxScroll = Math.max(0, teams.size() - MAX_TEAM_ROWS);
        int ns = Math.max(0, Math.min(maxScroll, teamScroll + delta));
        if (ns != teamScroll) { teamScroll = ns; rebuildWidgets(); }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (format.isTeam() && teams.size() > MAX_TEAM_ROWS) {
            double vy = toVirtualY(mouseY);
            if (vy >= TEAM_LIST_TOP && vy < TEAM_LIST_TOP + MAX_TEAM_ROWS * ROW_STEP) {
                scrollTeams(delta > 0 ? -1 : 1);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(originX(), originY(), 0);
        pose.scale((float) guiScale, (float) guiScale, 1.0f);

        DmzTextures.panel(g, 0, 0, uiWidth, uiHeight);
        // fixed logo header (same placement as the editor screens); name and state lines sit BELOW it
        net.shurui.dev.shuruis_dmz_tournaments.client.gui.theme.ThemeRender.header(g, 0, 0, uiWidth);
        g.drawCenteredString(this.font, TextUtil.color(tournamentName), UI_W / 2, HEADER_TEXT_TOP, GOLD);

        String state = signupOpen
                ? net.minecraft.client.resources.language.I18n.get("gui.dmz_ragnarok.tournaments.signup.state_open")
                : "&7" + stateLabel();
        drawCentered(g, state, UI_W / 2, HEADER_TEXT_TOP + 12);
        String kind = format.isTeam()
                ? net.minecraft.client.resources.language.I18n.get("gui.dmz_ragnarok.tournaments.signup.kind_team", format.label(), teamSize)
                : (format.isFfa()
                        ? net.minecraft.client.resources.language.I18n.get("gui.dmz_ragnarok.tournaments.signup.kind_ffa")
                        : net.minecraft.client.resources.language.I18n.get("gui.dmz_ragnarok.tournaments.signup.kind_solo"));
        drawCentered(g, net.minecraft.client.resources.language.I18n.get("gui.dmz_ragnarok.tournaments.signup.summary", kind, count), UI_W / 2, HEADER_TEXT_TOP + 24);

        super.render(g, (int) Math.round(toVirtualX(mouseX)), (int) Math.round(toVirtualY(mouseY)), partialTick);
        pose.popPose();
    }

    private void drawCentered(GuiGraphics g, String text, int centerX, int y) {
        Component c = TextUtil.color(text);
        g.drawString(this.font, c, centerX - this.font.width(c) / 2, y, 0xFFFFFF, false);
    }

    private String stateLabel() {
        return switch (stateOrdinal) {
            case 2 -> net.minecraft.client.resources.language.I18n.get("gui.dmz_ragnarok.tournaments.signup.state_starting");
            case 3 -> net.minecraft.client.resources.language.I18n.get("gui.dmz_ragnarok.tournaments.signup.state_in_progress");
            case 4 -> net.minecraft.client.resources.language.I18n.get("gui.dmz_ragnarok.tournaments.signup.state_between");
            case 5 -> net.minecraft.client.resources.language.I18n.get("gui.dmz_ragnarok.tournaments.signup.state_finished");
            default -> net.minecraft.client.resources.language.I18n.get("gui.dmz_ragnarok.tournaments.signup.state_none");
        };
    }
}
