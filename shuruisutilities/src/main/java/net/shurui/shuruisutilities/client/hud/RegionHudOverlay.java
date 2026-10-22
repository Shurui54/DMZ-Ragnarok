package net.shurui.shuruisutilities.client.hud;

import java.util.List;

import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHud;
import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudElement;
import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudFrame;
import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudSlot;
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
// The panel is placed, scaled and hidden through DragonMineZ's HUD editor (a Ragnarok HUD extra).
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

    // render hook, registered as a Forge GUI overlay: the panel is a Ragnarok HUD extra placed by DMZ's HUD layout
    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight)
    {
        RagnarokHud.draw(RagnarokHudSlot.REGION, g, partialTick, screenWidth, screenHeight);
    }

    /** Bind the panel to its slot in DMZ's HUD layout. */
    public static void registerElement()
    {
        RagnarokHud.register(new Element());
    }

    /** Where a panel of a given size goes: {x, y} in the coordinates it is drawn in. */
    interface Placer
    {
        int[] place(int w, int h);
    }

    /** The legacy placement from RegionHudConfig (centre x and top y as screen fractions), clamped on screen. */
    static Placer screenPlacer(RegionHudConfig cfg, int screenWidth, int screenHeight)
    {
        return (w, h) -> {
            int x = (int) Math.round(cfg.xPct * screenWidth) - w / 2;
            int y = (int) Math.round(cfg.yPct * screenHeight);
            return new int[] {Math.max(2, Math.min(screenWidth - w - 2, x)), Math.max(2, Math.min(screenHeight - h - 2, y))};
        };
    }

    // nominal editor box: the widest panel and a title, difficulty and three description lines
    private static final int BOX_H = PAD + 2 + 10 + 10 + 3 * 9 + PAD - 2;

    /** The region panel as a DMZ layout element. The panel hugs the side of the box it is anchored to. */
    private static final class Element extends RagnarokHudElement
    {
        private String title = "", difficulty = "", description = "";

        Element()
        {
            super(RagnarokHudSlot.REGION);
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
            // the old default: left column, 17.9% down (a 360 tall layout unit makes the fraction exact)
            return new float[] {0.0f, 0.0f, 2.0f, (float) (RegionHudConfig.DEFAULT_Y_PCT * 360.0), 1.0f};
        }

        @Override
        public float[] fallbackBox(int screenWidth, int screenHeight)
        {
            RegionHudConfig cfg = RegionHudConfig.get();
            return new float[] {(float) (cfg.xPct * screenWidth) - MAX_W / 2.0f, (float) (cfg.yPct * screenHeight), 1.0f};
        }

        @Override
        public boolean listed()
        {
            // NPC regions are private: keyless there is no panel at all (ClientGate, the synced answer)
            return net.shurui.dev.sdu.api.ClientGate.key();
        }

        @Override
        public boolean update(boolean preview, float partialTick)
        {
            Minecraft mc = Minecraft.getInstance();
            NpcRegionCacheClient.Entry e = mc.player == null || mc.level == null ? null : current(mc);
            if (e != null && e.showHud)
            {
                title = e.displayTitle();
                difficulty = e.difficulty;
                description = e.description;
                return true;
            }
            if (!preview)
                return false;
            title = "Region Name";
            difficulty = "&6Hard";
            description = "Region description appears here.";
            return true;
        }

        @Override
        public void render(GuiGraphics g, float partialTick, RagnarokHudFrame frame)
        {
            // without DMZ's layout the box is the legacy one, centred on the stored x like the old panel
            boolean dmz = RagnarokHud.available();
            renderPanel(g, title, difficulty, description, (w, h) -> new int[] {
                    Math.round(dmz ? frame.alignX(w) : (MAX_W - w) / 2.0f), Math.round(frame.alignY(h))});
        }
    }

    // draw the panel (shared with HudMoveScreen's preview). returns {x,y,w,h} for the drag hit-test.
    static int[] renderPanel(GuiGraphics g, String title, String difficulty, String description, Placer placer)
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

        int[] at = placer.place(w, h);
        int x = at[0];
        int y = at[1];

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
