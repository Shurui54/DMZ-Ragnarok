package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.shuruisutilities.client.gui.task.TaskArt;
import net.shurui.shuruisutilities.client.gui.task.TaskPillButton;
import net.shurui.shuruisutilities.client.gui.task.TaskRerollButton;
import net.shurui.shuruisutilities.client.gui.task.TaskTabButton;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The player's task board: three tabs, three rows each, on the commissioned panel art.
 *
 * <p>All nine rows arrive at once, so the tabs switch with no round trip; only a button press goes to the server,
 * and the server answers with the whole board again.
 *
 * <p>Row layout follows the commissioned mock-up left to right: a dragon-ball bullet numbering the row, the task's
 * name and progress, then the reroll glyph and the action pills. Which buttons appear is entirely the slot's
 * state, so the row never offers something that would be refused:
 *
 * <ul>
 *   <li>OFFERED - Accept (green pill) and Reroll (glyph)</li>
 *   <li>ACTIVE - Abandon (red pill) only. Rerolling the task you are working is what the reroll price exists to stop.</li>
 *   <li>COMPLETE - Claim (yellow pill), which is the same slot the Accept pill occupied</li>
 *   <li>CLAIMED - nothing; the row is spent until the board turns over</li>
 * </ul>
 *
 * <p>The virtual canvas IS the art: 421x415, panel.png's own size. Everything therefore draws 1:1 and every
 * layout constant is the reference mock-up's own pixel position minus the panel origin, with no scale factor
 * in between to round away the detail.
 */
public class TaskBoardScreen extends SagaBaseScreen
{
    // THE CANVAS IS THE ART. panel.png is 421x415 and every other piece on this board was authored against it
    // at that size, so the virtual canvas is that size too and everything draws 1:1.
    //
    // This replaced a 300x296 canvas, and the two faults it caused are worth remembering. The panel had to be
    // squashed into it, and every sprite then had to choose between being drawn 1:1 (correct pixels, but ~40%
    // too big relative to a shrunken panel, which is what made the tabs look oversized) or scaled to match the
    // panel (right proportion, but nearest-neighbour sampling eats the dragon balls). At native size there is no
    // trade: pixels are exact AND proportions match the reference.
    //
    // Every constant below is the reference mock-up's own pixel position minus the panel origin (45,48), so
    // they can be re-measured off the art directly rather than back-calculated through a scale factor.
    private static final int UI_W = TaskArt.PANEL_W;   // 421
    private static final int UI_H = TaskArt.PANEL_H;   // 415

    // Tabs: three plaques, native 81x27, 109 apart.
    private static final int TAB_W = net.shurui.shuruisutilities.client.gui.task.TaskArt.TAB_W;
    private static final int TAB_H = net.shurui.shuruisutilities.client.gui.task.TaskArt.TAB_H;
    private static final int TAB_PITCH = 109;
    private static final int TAB_Y = 48;
    private static final int TAB_X = 61;

    // Rows.
    private static final int ROW_TOP = 129;
    private static final int ROW_H = 59;
    private static final int BALL_X = 55;
    private static final int BALL_SIZE = 15;
    private static final int BALL_DY = 3;
    private static final int TEXT_X = 78;
    private static final int REROLL_X = 238;
    private static final int REROLL_SIZE = 18;
    private static final int PILL_W = 55;
    private static final int PILL_H = 24;
    private static final int PILL_PRIMARY_X = 272;
    private static final int PILL_SECONDARY_X = 335;

    /** Zeni readout, bottom right, drawn by the shared HUD readout so it matches the scouter exactly. */
    private static final int ZENI_RIGHT = 395;
    private static final int ZENI_X = ZENI_RIGHT - net.shurui.shuruisutilities.client.hud.ZeniReadout.WIDTH;
    private static final int ZENI_Y = UI_H - 12 - net.shurui.shuruisutilities.client.hud.ZeniReadout.HEIGHT;

    /** The main menu's logo art: same mark as the theme's, at 910x548 instead of 108x64. */
    private static final net.minecraft.resources.ResourceLocation LOGO =
            new net.minecraft.resources.ResourceLocation("dmz_ragnarok", "textures/gui/mainmenu/logo.png");
    private static final int LOGO_TEX_W = 910;
    private static final int LOGO_TEX_H = 548;
    private static final int LOGO_TOP = 2;
    /** Clear space kept between the logo's bottom and the tab row. */
    private static final int LOGO_GAP = 4;

    /** Board keys in the order the server sends their prices. Must match TaskPeriod's declaration order. */
    private static final String[] PERIODS = { "daily", "weekly", "monthly" };

    private final List<String> meta;
    private final List<List<String>> rows;
    private int tab = 0;

    public TaskBoardScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.tasks.title"), UI_W, UI_H, null);
        this.meta = meta;
        this.rows = rows;
    }

    private String metaAt(int i, String fallback)
    {
        return meta.size() > i ? meta.get(i) : fallback;
    }

    /** The three rows of the tab being looked at, in slot order. */
    private List<List<String>> visibleRows()
    {
        List<List<String>> out = new ArrayList<>();
        for (List<String> r : rows)
            if (!r.isEmpty() && PERIODS[tab].equals(r.get(0)))
                out.add(r);
        out.sort((a, b) -> Integer.parseInt(a.get(1)) - Integer.parseInt(b.get(1)));
        return out;
    }

    /**
     * The logo at its texture's NATIVE size, 108x64, drawn 1:1.
     *
     * <p>It is NOT drawn at the reference's 160x55. That is the size of the reference's OWN logo art, which is a
     * wider image than {@code GuiTheme.LOGO} (108x64, aspect 1.69 against the reference's 2.91). Forcing our
     * texture into those dimensions is what stretched it flat and wide on screen. Matching a reference means
     * matching what it does, not copying a number measured off different art.
     *
     * <p>Sat so it overhangs the panel's top edge the way the reference does, with its bottom clear of the tab
     * row at y=48.
     */
    /**
     * The header logo, from the HIGH-RESOLUTION main-menu texture and sized to its own aspect.
     *
     * <p>Two things were wrong before. It used {@code GuiTheme.LOGO}, which is 108x64 and looks soft the moment
     * it is drawn at any size; the main menu's own logo is 910x548 of the same mark and downscales cleanly. And
     * it was placed at a negative y to "overhang" the panel the way the reference does, which simply cut the top
     * off. It now sits fully inside the panel, above the tab row.</p>
     *
     * <p>Height is chosen to clear the tabs at {@link #TAB_Y}; width FOLLOWS from the texture's aspect so the
     * mark can never be stretched, which is the mistake that flattened it earlier.</p>
     */
    @Override
    protected void renderHeader(GuiGraphics g)
    {
        int h = TAB_Y - LOGO_TOP - LOGO_GAP;
        int w = Math.round(h * (LOGO_TEX_W / (float) LOGO_TEX_H));
        int x = (uiWidth - w) / 2;
        g.blit(LOGO, x, LOGO_TOP, w, h, 0.0F, 0.0F, LOGO_TEX_W, LOGO_TEX_H, LOGO_TEX_W, LOGO_TEX_H);
    }

    /**
     * The board's own panel instead of the shared theme one, plus the row bullets, which are decoration
     * behind the row text. Drawn 1:1 now that the canvas is the art's own size.
     *
     * <p>The zeni readout is NOT drawn here: it draws its own number, so it comes after the widgets.</p>
     */
    @Override
    protected void renderPanel(GuiGraphics g)
    {
        TaskArt.panel(g, 0, 0, uiWidth, uiHeight);

        int count = visibleRows().size();
        for (int i = 0; i < count; i++)
            TaskArt.ball(g, i, BALL_X, ROW_TOP + i * ROW_H + BALL_DY, BALL_SIZE);
    }

    /**
     * The shared zeni readout, on top of everything else. The balance comes from the board's own meta rather
     * than {@code ZeniClientCache}, because the server sends it with the board and it is authoritative at the
     * moment the board was built.
     */
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        super.render(g, mouseX, mouseY, partialTick);

        var pose = g.pose();
        pose.pushPose();
        pose.translate(originX(), originY(), 0);
        pose.scale((float) guiScale, (float) guiScale, 1.0f);
        long balance;
        try
        {
            balance = Long.parseLong(metaAt(0, "0").trim());
        }
        catch (NumberFormatException e)
        {
            balance = 0L;
        }
        net.shurui.shuruisutilities.client.hud.ZeniReadout.draw(g, this.font, ZENI_X, ZENI_Y, balance, true);
        pose.popPose();
    }

    @Override
    protected void init()
    {
        super.init();
        // CAP AT 1:1. The canvas is the art's own 421x415, so the shared screen scale (up to 2x) drew the
        // board at 842x830 on screen, which is too big to read. 1.0 shows the art at the size it was drawn,
        // which is also the only ratio where every sprite maps pixel-for-pixel. min() keeps shrink-to-fit
        // for windows too small for 421x415.
        this.guiScale = Math.min(1.0D, this.guiScale);

        for (int i = 0; i < PERIODS.length; i++)
        {
            final int index = i;
            addRenderableWidget(new TaskTabButton(TAB_X + i * TAB_PITCH, TAB_Y, TAB_W, TAB_H,
                    Component.literal(tr("gui.dmz_ragnarok.tasks.period." + PERIODS[i])),
                    i == tab,
                    () -> { tab = index; rebuildWidgets(); }));
        }

        List<List<String>> visible = visibleRows();
        for (int i = 0; i < visible.size(); i++)
            drawRow(visible.get(i), i);

        // Menu goes bottom LEFT: the mock-up puts the zeni readout in the bottom right corner, so the footer
        // button cannot live in its usual place without sitting on top of it.
        btn(14, footerY(), 48, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.btn.menu"), EditorScreens::openPlayerHub);
    }

    private void drawRow(List<String> row, int visualIndex)
    {
        final String period = row.get(0);
        final String slot = row.get(1);
        String name = row.size() > 2 ? row.get(2) : "";
        String desc = row.size() > 3 ? row.get(3) : "";
        String goal = row.size() > 4 ? row.get(4) : "";
        int progress = parse(row, 5);
        int amount = parse(row, 6);
        String state = row.size() > 7 ? row.get(7) : "OFFERED";
        int zeni = parse(row, 8);
        int tp = parse(row, 9);

        int y = ROW_TOP + visualIndex * ROW_H;

        if (name.isBlank())
        {
            // A board whose pool is empty. Said plainly rather than left blank, or an admin who has not written
            // any tasks yet reads it as the panel being broken.
            label("§8" + tr("gui.dmz_ragnarok.tasks.empty_slot"), TEXT_X, y + 8, 0xFFFFFFFF);
            return;
        }

        label("§f" + name, TEXT_X, y, 0xFFFFFFFF);
        label("§7" + goal + "  §8(" + progress + "/" + amount + ")", TEXT_X, y + 10, 0xFFFFFFFF);
        if (!desc.isBlank())
            label("§8" + desc, TEXT_X, y + 20, 0xFFFFFFFF);
        if (zeni > 0 || tp > 0)
            label("§e" + zeni + "z §b" + tp + "tp", TEXT_X, y + 30, 0xFFFFFFFF);

        int pillY = y + 2;
        switch (state)
        {
            case "OFFERED" ->
            {
                addRenderableWidget(new TaskRerollButton(REROLL_X, pillY + 2, REROLL_SIZE,
                        Component.translatable("gui.dmz_ragnarok.tasks.reroll", rerollCost()),
                        () -> act("reroll", period, slot)));
                addRenderableWidget(new TaskPillButton(PILL_PRIMARY_X, pillY, PILL_W, PILL_H,
                        Component.literal(tr("gui.dmz_ragnarok.tasks.accept")),
                        TaskArt.Pill.GREEN, () -> act("accept", period, slot)));
            }
            // Abandon deliberately sits in the SECOND pill slot, not the first. Accept and Claim both occupy the
            // primary slot, so putting the one destructive action there too would mean a player who clicks the
            // same spot twice out of habit accepts a task and then throws it away.
            case "ACTIVE" -> addRenderableWidget(new TaskPillButton(PILL_SECONDARY_X, pillY, PILL_W, PILL_H,
                    Component.literal(tr("gui.dmz_ragnarok.tasks.abandon")),
                    TaskArt.Pill.RED, () -> act("abandon", period, slot)));
            case "COMPLETE" -> addRenderableWidget(new TaskPillButton(PILL_PRIMARY_X, pillY, PILL_W, PILL_H,
                    Component.literal(tr("gui.dmz_ragnarok.tasks.claim")),
                    TaskArt.Pill.YELLOW, () -> act("claim", period, slot)));
            default -> label("§8" + tr("gui.dmz_ragnarok.tasks.claimed"), PILL_PRIMARY_X, y + 6, 0xFFFFFFFF);
        }
    }

    /** This tab's reroll price, from meta [zeni, currency, daily, weekly, monthly]. */
    private String rerollCost()
    {
        return metaAt(2 + tab, "0");
    }

    private static int parse(List<String> row, int index)
    {
        try
        {
            return row.size() > index ? Integer.parseInt(row.get(index)) : 0;
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    private static void act(String action, String period, String slot)
    {
        EditorScreens.act("taskboard", action, period, slot);
    }
}
