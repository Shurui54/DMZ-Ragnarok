package net.shurui.shuruisutilities.client.ocarina;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import net.shurui.dev.sdu.client.gui.theme.GuiText;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.client.gui.DmzTextureButton;
import net.shurui.shuruisutilities.client.gui.ScaledScreen;
import net.shurui.shuruisutilities.client.gui.theme.ThemeRender;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.ocarina.OcarinaSong;
import net.shurui.shuruisutilities.ocarina.PacketOcarina;

/**
 * The ocarina: every song, its level and what it does at that level, and a Play button for each one learned.
 *
 * <p>Opened only by the server ({@code PacketOcarinaState}), because which songs are known (the DMZ skill levels
 * bought at Tapion) and what they do (the server's Ocarina.toml) are the server's answer. Songs not learned yet are
 * listed greyed with where to learn them. Closing tells the server, which puts the instrument away and ends the
 * meditation pose.
 */
public class OcarinaScreen extends ScaledScreen
{
    /** Lane names, in the chart's own order: up, left, down, right. */
    private static final String[] LANES = { "↑", "←", "↓", "→" };

    private static final int UI_W = 390;
    private static final int ROW_H = 30;
    private static final int PLAY_W = 64;

    private final int[] levels;
    private final int[] cooldowns;
    private final float[] stats;
    /** Set when a song has been chosen, so closing does not report the instrument as put away twice. */
    private boolean chosen;

    private OcarinaScreen(int[] levels, int[] cooldowns, float[] stats)
    {
        super(Component.translatable("gui.dmz_ragnarok.ocarina.title"), UI_W,
                GuiTheme.CONTENT_TOP + 14 + OcarinaSong.values().length * ROW_H + GuiTheme.FOOTER_BAND);
        this.levels = levels;
        this.cooldowns = cooldowns;
        this.stats = stats;
    }

    /** Server says the instrument is out. Show what can be played on it. */
    public static void open(int[] levels, int[] cooldowns, float[] stats)
    {
        Minecraft.getInstance().setScreen(new OcarinaScreen(levels, cooldowns, stats));
    }

    /** Server says a song has begun. Close the menu and hand over to the rhythm overlay. */
    public static void startSong(int songId, long seed)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof OcarinaScreen ocarina)
        {
            ocarina.chosen = true;
            mc.setScreen(null);
        }
        net.shurui.shuruisutilities.client.combat.ClashRhythmOverlay.beginOcarina(
                seed, I18n.get(OcarinaSong.byId(songId).nameKey()), songId);
    }

    private int level(OcarinaSong s)
    {
        return s.ordinal() < levels.length ? levels[s.ordinal()] : 0;
    }

    private int cooldown(OcarinaSong s)
    {
        return s.ordinal() < cooldowns.length ? cooldowns[s.ordinal()] : 0;
    }

    private float stat(OcarinaSong s, int i)
    {
        int at = s.ordinal() * 4 + i;
        return at < stats.length ? stats[at] : 0F;
    }

    private int rowY(int index)
    {
        return GuiTheme.CONTENT_TOP + 14 + index * ROW_H;
    }

    @Override
    protected void init()
    {
        super.init();
        for (OcarinaSong song : OcarinaSong.values())
        {
            if (level(song) <= 0)
                continue;
            final int id = song.ordinal();
            int cd = cooldown(song);
            DmzTextureButton play = DmzTextureButton.of(UI_W - 14 - PLAY_W, rowY(id) + 6, PLAY_W, GuiTheme.BUTTON_HEIGHT,
                    cd > 0 ? Component.literal(cd + "s") : Component.translatable("gui.dmz_ragnarok.ocarina.play"),
                    () -> NetworkUtils.sendToServer(new PacketOcarina(PacketOcarina.PLAY, id, 0)));
            play.active = cd <= 0;
            addRenderableWidget(play.commits());
        }
        int closeW = 70;
        addRenderableWidget(DmzTextureButton.of((UI_W - closeW) / 2, GuiTheme.footerY(uiHeight), closeW,
                GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.ocarina.close"), this::onClose));
    }

    private static String phrase(OcarinaSong song)
    {
        StringBuilder sb = new StringBuilder();
        for (int lane : song.phrase)
            sb.append(LANES[Math.max(0, Math.min(LANES.length - 1, lane))]).append(' ');
        return sb.toString().trim();
    }

    /** What the song does at this player's level, in the server's numbers. */
    private String effectLine(OcarinaSong s)
    {
        return I18n.get("gui.dmz_ragnarok.ocarina.fx." + s.skillId, num(stat(s, 0)), num(stat(s, 1)),
                num(stat(s, 2)), num(stat(s, 3)));
    }

    private static String num(float f)
    {
        return Math.abs(f - Math.round(f)) < 0.05F ? Integer.toString(Math.round(f))
                : String.format(java.util.Locale.ROOT, "%.1f", f);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial)
    {
        renderBackground(g);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(originX(), originY(), 0);
        pose.scale((float) guiScale, (float) guiScale, 1.0F);

        ThemeRender.panel(g, 0, 0, uiWidth, uiHeight);
        ThemeRender.header(g, 0, 0, uiWidth);
        g.drawString(font, title, GuiTheme.NAME_X, GuiTheme.NAME_Y, GuiTheme.NAME_COLOR, false);
        String hint = I18n.get("gui.dmz_ragnarok.ocarina.hint");
        g.drawString(font, hint, (uiWidth - font.width(hint)) / 2, GuiTheme.CONTENT_TOP, GuiTheme.COLOR_MUTED, false);

        int textW = UI_W - 14 - PLAY_W - 8 - 16;
        for (OcarinaSong s : OcarinaSong.values())
        {
            int y = rowY(s.ordinal());
            int lv = level(s);
            g.fill(12, y + 1, UI_W - 12, y + ROW_H - 2, 0x33000000);
            String name = I18n.get(s.nameKey());
            if (lv > 0)
            {
                String head = name + "  " + I18n.get("gui.dmz_ragnarok.ocarina.level", lv) + "   " + phrase(s);
                g.drawString(font, GuiText.ellipsize(font, head, textW), 16, y + 4, GuiTheme.COLOR_TITLE, false);
                g.drawString(font, GuiText.ellipsize(font, effectLine(s), textW), 16, y + 16, GuiTheme.COLOR_ROW,
                        false);
            }
            else
            {
                g.drawString(font, GuiText.ellipsize(font, name, textW), 16, y + 4, GuiTheme.COLOR_DISABLED, false);
                g.drawString(font, GuiText.ellipsize(font, I18n.get("gui.dmz_ragnarok.ocarina.unlearned"), textW),
                        16, y + 16, GuiTheme.COLOR_MUTED, false);
            }
        }

        super.render(g, (int) Math.round(toVirtualX(mouseX)), (int) Math.round(toVirtualY(mouseY)), partial);
        pose.popPose();
    }

    @Override
    public void onClose()
    {
        // Tell the server the instrument is going away, unless a song is starting: then it stays out for the
        // performance and is put away when the score is reported.
        if (!chosen)
            NetworkUtils.sendToServer(new PacketOcarina(PacketOcarina.CLOSE, 0, 0));
        super.onClose();
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
