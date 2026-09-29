package net.shurui.shuruisutilities.client.ocarina;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.ocarina.OcarinaSong;
import net.shurui.shuruisutilities.ocarina.PacketOcarina;

/**
 * The ocarina itself: pick a song, then play it.
 *
 * <p>Deliberately small. The instrument is meant to be raised, played and put away in the middle of whatever else is
 * happening, so the menu is a short list of what this player has actually learned, with the phrase written under each
 * name so the notes can be learned by eye rather than by trial.
 *
 * <p>Opened only by the server ({@code PacketOcarinaState}), because which songs are known is the server's answer.
 * Closing it tells the server so, which is what puts the instrument away again.
 */
public class OcarinaScreen extends Screen
{
    /** Lane names, in the chart's own order: up, left, down, right. */
    private static final String[] LANES = { "↑", "←", "↓", "→" };

    private final List<OcarinaSong> songs;
    private final int level;
    /** Set when a song has been chosen, so closing does not report the instrument as put away twice. */
    private boolean chosen;

    private OcarinaScreen(List<OcarinaSong> songs, int level)
    {
        super(Component.literal("Ocarina"));
        this.songs = songs;
        this.level = level;
    }

    /** Server says the instrument is out. Show what can be played on it. */
    public static void open(int songMask, int level)
    {
        List<OcarinaSong> known = new ArrayList<>();
        for (OcarinaSong song : OcarinaSong.values())
            if ((songMask & (1 << song.ordinal())) != 0)
                known.add(song);
        Minecraft.getInstance().setScreen(new OcarinaScreen(known, level));
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
                seed, OcarinaSong.byId(songId).displayName, songId);
    }

    @Override
    protected void init()
    {
        int y = this.height / 2 - (songs.size() * 26) / 2;
        for (OcarinaSong song : songs)
        {
            final int id = song.ordinal();
            this.addRenderableWidget(Button.builder(
                            Component.literal(song.displayName + "  " + phrase(song)),
                            b -> NetworkUtils.sendToServer(new PacketOcarina(PacketOcarina.PLAY, id, 0)))
                    .bounds(this.width / 2 - 110, y, 220, 20)
                    .build());
            y += 26;
        }
    }

    private static String phrase(OcarinaSong song)
    {
        StringBuilder sb = new StringBuilder();
        for (int lane : song.phrase)
            sb.append(LANES[Math.max(0, Math.min(LANES.length - 1, lane))]).append(' ');
        return sb.toString().trim();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial)
    {
        this.renderBackground(graphics);
        graphics.drawCenteredString(this.font, "Ocarina  ·  level " + level,
                this.width / 2, this.height / 2 - (songs.size() * 26) / 2 - 24, 0xFFE8C86A);
        super.render(graphics, mouseX, mouseY, partial);
    }

    @Override
    public void onClose()
    {
        // Tell the server the instrument is going away, unless a song is starting - in which case it stays out for
        // the performance and is put away when the score is reported.
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
