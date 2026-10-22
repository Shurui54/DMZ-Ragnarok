package net.shurui.shuruisutilities.client.hud;

import java.util.List;

import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHud;
import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudElement;
import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudFrame;
import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudSlot;
import net.shurui.shuruisutilities.client.gui.DmzTextures;
import net.shurui.shuruisutilities.racing.client.RaceClientState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The accepted task, kept on screen so nobody reopens a menu to remember what they are doing. Same nine-sliced DMZ
 * panel as the region HUD.
 *
 * <p>Shows exactly when {@code PacketStaffHud} says to; the server owns visibility, nothing here decides it (see
 * {@link StaffTaskHudState}).
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StaffTaskHudOverlay
{
    public static final String OVERLAY_ID = "staff_task_hud";

    private static final int MIN_W = 96;
    private static final int MAX_W = 176;
    private static final int PAD = 8;

    private StaffTaskHudOverlay() {}

    /**
     * Drop the reminder when the world goes away.
     *
     * <p>Without this the panel would survive a disconnect and hang over the main menu, and would still be there on
     * joining a server that has never heard of the task.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        Minecraft mc = Minecraft.getInstance();
        if ((mc.level == null || mc.player == null) && StaffTaskHudState.isActive())
            StaffTaskHudState.clear();
    }

    // render hook, registered as a Forge GUI overlay: the panel is a Ragnarok HUD extra placed by DMZ's HUD layout
    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight)
    {
        RagnarokHud.draw(RagnarokHudSlot.STAFF_TASK, g, partialTick, screenWidth, screenHeight);
    }

    /** Bind the panel to its slot in DMZ's HUD layout. */
    public static void registerElement()
    {
        RagnarokHud.register(new Element());
    }

    /** The legacy placement from StaffTaskHudConfig (centre x and top y as screen fractions), clamped on screen. */
    static RegionHudOverlay.Placer screenPlacer(StaffTaskHudConfig cfg, int screenWidth, int screenHeight)
    {
        return (w, h) -> {
            int x = (int) Math.round(cfg.xPct * screenWidth) - w / 2;
            int y = (int) Math.round(cfg.yPct * screenHeight);
            return new int[] {Math.max(2, Math.min(screenWidth - w - 2, x)), Math.max(2, Math.min(screenHeight - h - 2, y))};
        };
    }

    // nominal editor box: the widest panel with a two line title and three body lines
    private static final int BOX_H = PAD + 2 + 2 * 10 + 2 + 3 * 9 + PAD - 2;

    /** The staff task reminder as a DMZ layout element, hugging the side of the box it is anchored to. */
    private static final class Element extends RagnarokHudElement
    {
        private String title = "", description = "", denial = "";

        Element()
        {
            super(RagnarokHudSlot.STAFF_TASK);
        }

        @Override
        public float width()
        {
            return MAX_W;
        }

        @Override
        public float height()
        {
            return BOX_H;
        }

        @Override
        public float[] defaultPlacement()
        {
            // the old default: left column, 42% down, below the region panel
            return new float[] {0.0f, 0.0f, 2.0f, (float) (StaffTaskHudConfig.DEFAULT_Y_PCT * 360.0), 1.0f};
        }

        @Override
        public float[] fallbackBox(int screenWidth, int screenHeight)
        {
            StaffTaskHudConfig cfg = StaffTaskHudConfig.get();
            return new float[] {(float) (cfg.xPct * screenWidth) - MAX_W / 2.0f, (float) (cfg.yPct * screenHeight), 1.0f};
        }

        @Override
        public boolean listed()
        {
            // staff tasks are private: never drawn unless the server reported the key
            return net.shurui.dev.sdu.api.ClientGate.key();
        }

        @Override
        public boolean update(boolean preview, float partialTick)
        {
            if (StaffTaskHudState.isActive() && !RaceClientState.isSessionActive())
            {
                // hidden during a race, restored when it ends
                title = StaffTaskHudState.title();
                description = StaffTaskHudState.description();
                denial = StaffTaskHudState.denialReason();
                return true;
            }
            if (!preview)
                return false;
            title = "Staff Task";
            description = "What you accepted appears here.";
            denial = "";
            return true;
        }

        @Override
        public void render(GuiGraphics g, float partialTick, RagnarokHudFrame frame)
        {
            boolean dmz = RagnarokHud.available();
            renderPanel(g, title, description, denial, (w, h) -> new int[] {
                    Math.round(dmz ? frame.alignX(w) : (MAX_W - w) / 2.0f), Math.round(frame.alignY(h))});
        }
    }

    /** Draw the panel. Returns {x,y,w,h} so a move screen can hit-test it, matching the region HUD's shape. */
    static int[] renderPanel(GuiGraphics g, String title, String description, String denial,
                             RegionHudOverlay.Placer placer)
    {
        Minecraft mc = Minecraft.getInstance();
        var font = mc.font;

        // EVERY block wraps, the title included. A task title is a sentence somebody typed ("Moderate chat and
        // get some clocked in hours please"), not a short name, so leaving it on one line and then CENTRING it
        // put the draw origin far to the left of a panel it was several times wider than, and it spilled out of
        // both sides. Wrap first, then size the panel to what the wrapping actually produced.
        int textMax = MAX_W - 2 * PAD;
        List<FormattedCharSequence> titleLines = wrap(font, colors("&e" + title), textMax);
        List<FormattedCharSequence> desc = wrap(font, colors("&7" + description), textMax);
        List<FormattedCharSequence> denialLines = denial == null || denial.isBlank()
                ? List.of()
                : wrap(font, colors("&cSent back: &f" + denial), textMax);

        int widest = 0;
        for (FormattedCharSequence line : titleLines)
            widest = Math.max(widest, font.width(line));
        for (FormattedCharSequence line : desc)
            widest = Math.max(widest, font.width(line));
        for (FormattedCharSequence line : denialLines)
            widest = Math.max(widest, font.width(line));
        int w = Math.max(MIN_W, Math.min(MAX_W, widest + 2 * PAD));

        // +2 headroom: the 7px nine-slice border otherwise touches the title's caps
        int h = PAD + 2 + titleLines.size() * 10 + desc.size() * 9 + denialLines.size() * 9 + PAD - 2;
        if (!desc.isEmpty() || !denialLines.isEmpty())
            h += 2;                       // a hair of air between the title block and the body

        int[] at = placer.place(w, h);
        int x = at[0];
        int y = at[1];

        // same DMZ menu texture as every other SU GUI, nine-sliced so the crop scales cleanly
        DmzTextures.panel(g, x, y, w, h);

        // Left aligned throughout. Centring only ever looked right while the title was one short line, and it is
        // the reason a too-wide line could be drawn at a negative offset instead of simply overflowing.
        int ty = y + PAD;
        for (FormattedCharSequence line : titleLines)
        {
            g.drawString(font, line, x + PAD, ty, 0xFFF6E27A, true);
            ty += 10;
        }
        if (!desc.isEmpty() || !denialLines.isEmpty())
            ty += 2;
        for (FormattedCharSequence line : desc)
        {
            g.drawString(font, line, x + PAD, ty, 0xFFC8C8C8, false);
            ty += 9;
        }
        for (FormattedCharSequence line : denialLines)
        {
            g.drawString(font, line, x + PAD, ty, 0xFFE08080, false);
            ty += 9;
        }
        return new int[] {x, y, w, h};
    }

    /** Split to a pixel width, or nothing at all for blank text so it takes up no height. */
    private static List<FormattedCharSequence> wrap(net.minecraft.client.gui.Font font, String text, int width)
    {
        String body = text == null ? "" : text;
        // A colour prefix on its own is not content; without this an absent description still cost a blank line.
        if (body.isBlank() || net.minecraft.ChatFormatting.stripFormatting(body) == null
                || net.minecraft.ChatFormatting.stripFormatting(body).isBlank())
            return List.of();
        return font.split(Component.literal(body), Math.max(16, width));
    }

    // &-codes to §. SU's formatter only converts valid codes, so a literal & is left alone.
    static String colors(String s)
    {
        return net.shurui.shuruisutilities.util.output.ChatOutputHandler.formatColors(s == null ? "" : s);
    }
}
