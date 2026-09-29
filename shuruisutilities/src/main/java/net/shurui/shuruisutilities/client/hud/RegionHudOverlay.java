package net.shurui.shuruisutilities.client.hud;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.DmzTextures;
import net.shurui.shuruisutilities.compat.client.NpcRegionCacheClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// player NPC-region HUD: inside a region with "Show Region HUD" on, draws a menubig-styled panel with the
// title/difficulty/description. also fires the entry title on crossing into a region with "Show Entry Title".
// every selection of a region counts as one area, so crossing between them never re-fires or flickers.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RegionHudOverlay
{
    public static final String OVERLAY_ID = "npc_region_hud";

    private static final int MIN_W = 96;
    private static final int MAX_W = 176;
    private static final int PAD = 8;

    // region the player was in last tick (null = outside all)
    private static String lastRegion;
    // last region an entry title fired for. NOT cleared on exit: stepping out and straight back in stays
    // silent; the title only re-fires after entering a DIFFERENT region (or relogging).
    private static String lastTitledRegion;

    private RegionHudOverlay() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null)
        {
            lastRegion = null;
            lastTitledRegion = null; // disconnected: a fresh session greets again
            return;
        }
        NpcRegionCacheClient.Entry e = current(mc);
        String region = e == null ? null : e.groupKey();
        boolean changed = region == null ? lastRegion != null : !region.equals(lastRegion);
        lastRegion = region;
        if (changed && e != null && !region.equals(lastTitledRegion))
        {
            // advance the re-arm regardless of preference: the toggle below gates only the drawing, so turning
            // announcements back on later still greets on the next different region.
            lastTitledRegion = region;
            // client side already (no packet), so read the preference directly. Suppressing OUR title never touches
            // DMZ's own or vanilla titles.
            if (e.showTitle && net.shurui.shuruisutilities.core.SUConfig.suiteAnnouncements)
            {
                mc.gui.setTitle(Component.literal(colors(e.displayTitle())));
                if (!e.difficulty.isBlank())
                    mc.gui.setSubtitle(Component.literal(colors("&7" + e.difficulty)));
            }
        }
    }

    // render hook, registered as a Forge GUI overlay
    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.options.renderDebug)
            return;
        NpcRegionCacheClient.Entry e = current(mc);
        if (e == null || !e.showHud)
            return;
        renderPanel(g, e.displayTitle(), e.difficulty, e.description,
                RegionHudConfig.get(), screenWidth, screenHeight);
    }

    // draw the panel (shared with HudMoveScreen's preview). returns {x,y,w,h} for the drag hit-test.
    static int[] renderPanel(GuiGraphics g, String title, String difficulty, String description,
                             RegionHudConfig cfg, int screenWidth, int screenHeight)
    {
        Minecraft mc = Minecraft.getInstance();
        var font = mc.font;

        String titleText = colors("&e" + title);
        String diffText = difficulty == null || difficulty.isBlank() ? "" : colors("&7Difficulty: &f" + difficulty);
        String descText = description == null || description.isBlank() ? "" : colors("&7" + description);

        int inner = Math.max(font.width(titleText), font.width(diffText));
        inner = Math.max(Math.min(inner, MAX_W - 2 * PAD), MIN_W - 2 * PAD);
        int w = Math.min(MAX_W, inner + 2 * PAD);

        // full description always shows: the panel grows a line at a time to fit
        List<FormattedCharSequence> desc = descText.isEmpty()
                ? List.of()
                : font.split(Component.literal(descText), w - 2 * PAD);

        // +2 headroom: the 7px nine-slice border otherwise touches the title's caps
        int h = PAD + 2 + 10 + (diffText.isEmpty() ? 0 : 10) + desc.size() * 9 + PAD - 2;

        int x = (int) Math.round(cfg.xPct * screenWidth) - w / 2;
        int y = (int) Math.round(cfg.yPct * screenHeight);
        x = Math.max(2, Math.min(screenWidth - w - 2, x));
        y = Math.max(2, Math.min(screenHeight - h - 2, y));

        // same DMZ menu texture as every other SU GUI, nine-sliced so the crop scales cleanly
        DmzTextures.panel(g, x, y, w, h);

        int ty = y + PAD;
        g.drawString(font, titleText, x + (w - font.width(titleText)) / 2, ty, 0xFFF6E27A, true);
        ty += 10;
        if (!diffText.isEmpty())
        {
            g.drawString(font, diffText, x + (w - font.width(diffText)) / 2, ty, 0xFFE0E0E0, true);
            ty += 10;
        }
        for (FormattedCharSequence line : desc)
        {
            g.drawString(font, line, x + PAD, ty, 0xFFC8C8C8, false);
            ty += 9;
        }
        return new int[] {x, y, w, h};
    }

    private static NpcRegionCacheClient.Entry current(Minecraft mc)
    {
        // NPC regions are private: keyless there is no panel and no entry title (ClientGate, the synced answer)
        if (!net.shurui.dev.sdu.api.ClientGate.key())
            return null;
        String dim = mc.level.dimension().location().toString();
        return NpcRegionCacheClient.regionAt(dim, mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }

    // &-codes to §. SU's formatter only converts valid codes, so a literal & ("Dungeons & Dragons") is left alone.
    static String colors(String s)
    {
        return net.shurui.shuruisutilities.util.output.ChatOutputHandler.formatColors(s == null ? "" : s);
    }
}
