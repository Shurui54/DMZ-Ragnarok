package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_raid_bosses.client.gui.theme.GuiText;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_raid_bosses.client.gui.theme.ThemeRender;
import net.shurui.dev.shuruis_raid_bosses.network.RaidNet;
import net.shurui.dev.shuruis_raid_bosses.network.SignupActionPacket;
import net.shurui.dev.shuruis_raid_bosses.util.TextUtil;

/**
 * The sign-up NPC screen for a specific raid, styled to match the sibling mods: a nine-sliced
 * DragonMineZ {@code menubig} panel on a scaled virtual canvas, gold {@link DmzTextureButton}s, and
 * gold headings. Button presses are relayed to the server via {@link SignupActionPacket}.
 */
public class RaidSignupScreen extends ScaledScreen {
    private static final int UI_W = 190;
    private static final int UI_H = 190;

    private final String defId;
    private final String raidName;
    private final boolean signupOpen;
    private final boolean signedUp;
    private final int count;
    private final int stateOrdinal;

    public RaidSignupScreen(String defId, String raidName, boolean signupOpen, boolean signedUp, int count, int stateOrdinal) {
        super(Component.literal(raidName), UI_W, UI_H);
        this.defId = defId;
        this.raidName = raidName;
        this.signupOpen = signupOpen;
        this.signedUp = signedUp;
        this.count = count;
        this.stateOrdinal = stateOrdinal;
    }

    @Override
    protected void init() {
        super.init();
        int w = 130;
        int x = (UI_W - w) / 2;
        int y = 62;

        DmzTextureButton join = button(x, y, w, Component.translatable("gui.dmz_ragnarok.raid.signup.sign_up"),
                () -> { send(SignupActionPacket.JOIN); onClose(); });
        join.active = signupOpen && !signedUp;
        addRenderableWidget(join);

        DmzTextureButton leave = button(x, y + 24, w, Component.translatable("gui.dmz_ragnarok.raid.signup.withdraw"),
                () -> { send(SignupActionPacket.LEAVE); onClose(); });
        leave.active = signedUp;
        addRenderableWidget(leave);

        addRenderableWidget(button(x, y + 48, w, Component.translatable("gui.dmz_ragnarok.raid.signup.to_arena"),
                () -> { send(SignupActionPacket.TP_ARENA); onClose(); }));
        addRenderableWidget(button(x, y + 72, w, Component.translatable("gui.dmz_ragnarok.raid.signup.status"),
                () -> send(SignupActionPacket.STATUS)));
        addRenderableWidget(button(x, y + 100, w, Component.translatable("gui.dmz_ragnarok.raid.common.close"), this::onClose));
    }

    private DmzTextureButton button(int x, int y, int w, Component label, Runnable onPress) {
        return new DmzTextureButton(x, y, w, 18, label,
                DmzTextures.MENU_BIG, DmzTextures.BUTTON_U, DmzTextures.BUTTON_V,
                DmzTextures.BUTTON_W, DmzTextures.BUTTON_H, DmzTextures.ATLAS, onPress);
    }

    private void send(int action) {
        RaidNet.sendToServer(new SignupActionPacket(defId, action));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(originX(), originY(), 0);
        pose.scale((float) guiScale, (float) guiScale, 1.0f);

        // Spliced panel + the umbrella logo header, matching the shared theme. The raid is a named entity, so its
        // name sits in the panel's TOP LEFT (suite convention), fitted so it never runs under the centred logo; the
        // state / count lines stay centred below the header band so nothing overlaps the logo.
        ThemeRender.panel(g, 0, 0, UI_W, UI_H);
        ThemeRender.header(g, 0, 0, UI_W);

        String shownName = TextUtil.color(raidName).getString();
        int nameMaxW = Math.max(1, (UI_W / 2 - GuiTheme.LOGO_HALF_WIDTH) - GuiTheme.NAME_X - GuiTheme.UNIT);
        String fittedName = this.font.width(shownName) > nameMaxW
                ? GuiText.ellipsize(this.font, shownName, nameMaxW) : shownName;
        g.drawString(this.font, fittedName, GuiTheme.NAME_X, GuiTheme.NAME_Y, GuiTheme.NAME_COLOR, false);

        String state = signupOpen ? I18n.get("gui.dmz_ragnarok.raid.signup.state_open")
                : "&7" + stateLabel();
        drawCentered(g, state, UI_W / 2, GuiTheme.CONTENT_TOP);
        drawCentered(g, I18n.get("gui.dmz_ragnarok.raid.signup.count", count), UI_W / 2, GuiTheme.CONTENT_TOP + 12);

        super.render(g, (int) Math.round(toVirtualX(mouseX)), (int) Math.round(toVirtualY(mouseY)), partialTick);
        pose.popPose();
    }

    private void drawCentered(GuiGraphics g, String text, int centerX, int y) {
        Component c = TextUtil.color(text);
        g.drawString(this.font, c, centerX - this.font.width(c) / 2, y, 0xFFFFFF, false);
    }

    private String stateLabel() {
        return switch (stateOrdinal) {
            case 2 -> I18n.get("gui.dmz_ragnarok.raid.signup.state_starting");
            case 3 -> I18n.get("gui.dmz_ragnarok.raid.signup.state_progress");
            case 4 -> I18n.get("gui.dmz_ragnarok.raid.signup.state_finished");
            default -> I18n.get("gui.dmz_ragnarok.raid.signup.state_none");
        };
    }
}
