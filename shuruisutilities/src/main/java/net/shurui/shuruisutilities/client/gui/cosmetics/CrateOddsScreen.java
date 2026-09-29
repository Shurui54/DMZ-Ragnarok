package net.shurui.shuruisutilities.client.gui.cosmetics;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.shuruisutilities.client.gui.theme.GuiText;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticWire;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticQuality;

/**
 * The read-only crate odds, opened by a keyless right-click on a bound cosmetic crate.
 *
 * <p>Everything drawn here was computed on the SERVER, from the same object the roll uses, and arrived as
 * pre-formatted rows through the generic hub editor packet. The client does no arithmetic, so it cannot be shown a
 * number the server did not compute (the F5 guarantee).
 *
 * <h2>Layout, top to bottom</h2>
 * <ul>
 *   <li>The crate name, top left (the suite "named entity, top left" rule).</li>
 *   <li>The quality chances stated ONCE: Normal, Super (red) and Magic (pink), in the crate's quality colours.</li>
 *   <li>The cosmetic list: each row is the item icon, its name and its own draw chance, and nothing more, unless
 *       the cosmetic cannot roll a quality the crate otherwise offers, in which case a brief muted note says so
 *       (otherwise the header figure would read as a lie for that row).</li>
 *   <li>A total draw chance that adds up.</li>
 *   <li>The Magic effect pool, if the crate has one, so a player can read which effect they might get.</li>
 * </ul>
 *
 * <p>{@code meta = [crateDisplayName, superPercent, magicPercent, poolDisplayName, normalPercent,
 * totalDrawPercent]}. Cosmetic rows carry {@code [c, catalogId, displayName, drawPercent, magicEligible,
 * superEligible]}; effect rows carry {@code [e, effectId, name, chancePercent]}.
 */
public class CrateOddsScreen extends SagaBaseScreen
{
    private static final String KEY = "gui.dmz_ragnarok.core.crate_odds.";

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    /** The single summary row of quality chances, clear of the header logo band. */
    private static final int SUMMARY_Y = 30;
    /** A hairline under the summary that separates it from the list. */
    private static final int SEP_Y = 42;
    /** Where the scrolling list begins. */
    private static final int LIST_TOP = 46;
    /** Row pitch: tall enough for a {@link #ICON}-tall icon cell with a clear band between rows. */
    private static final int ROW_H = 16;
    /** The one icon-cell edge, so every cosmetic reads at the same size down the column. */
    private static final int ICON = 14;
    /** Gap between the icon cell and the name. */
    private static final int ICON_GAP = 4;
    /** A very light wash on alternate content rows so a long table reads as banded, not as a wall of text. */
    private static final int ROW_SHADE = 0x14FFFFFF;

    private final String crateName;
    private final String superPct;
    private final String magicPct;
    private final String normalPct;
    private final String totalPct;
    private final String poolName;
    private final boolean crateHasSuper;
    private final boolean crateHasMagic;
    private final List<List<String>> cosmeticRows = new ArrayList<>();
    private final List<List<String>> effectRows = new ArrayList<>();

    /** Icon draws staged in init() and painted in render(), inside the scaled canvas pose. */
    private final List<IconDraw> iconDraws = new ArrayList<>();
    private int scroll;

    private record IconDraw(String catalogId, String name, int x, int y, int size)
    {
    }

    /**
     * One rendered line. Meaning by kind: {@code h} a section header (name), {@code c} a cosmetic (id, name,
     * value = draw chance, note), {@code e} a pool effect (name, value = chance), {@code t} the total (name,
     * value). {@code shade} bands the content rows.
     */
    private record Line(char kind, String id, String name, String value, String note, boolean shade)
    {
    }

    public CrateOddsScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable(KEY + "title"), UI_W, UI_H, null);
        this.crateName = at(meta, 0, "");
        this.superPct = at(meta, 1, "0");
        this.magicPct = at(meta, 2, "0");
        this.poolName = at(meta, 3, "");
        this.normalPct = at(meta, 4, "0.00");
        this.totalPct = at(meta, 5, "0.00");
        this.crateHasSuper = !isZero(superPct);
        this.crateHasMagic = !isZero(magicPct);
        for (List<String> row : rows)
        {
            if (row.isEmpty())
                continue;
            if (CosmeticWire.CRATE_ODDS_COSMETIC.equals(row.get(0)))
                cosmeticRows.add(row);
            else if (CosmeticWire.CRATE_ODDS_EFFECT.equals(row.get(0)))
                effectRows.add(row);
        }
    }

    /** The combined line list: the cosmetic header, the cosmetics, the total, then the pool header and effects. */
    private List<Line> lines()
    {
        List<Line> out = new ArrayList<>();
        out.add(new Line('h', "", tr(KEY + "cosmetics_header"), "", "", false));
        int band = 0;
        for (List<String> r : cosmeticRows)
        {
            String id = r.size() > 1 ? r.get(1) : "";
            String name = r.size() > 2 && !r.get(2).isBlank() ? r.get(2) : id;
            String draw = r.size() > 3 ? r.get(3) : "";
            boolean magicElig = r.size() > 4 && "1".equals(r.get(4));
            boolean superElig = r.size() > 5 && "1".equals(r.get(5));
            out.add(new Line('c', id, name, draw, note(magicElig, superElig), (band++ & 1) == 1));
        }
        out.add(new Line('t', "", tr(KEY + "total_label"), tr(KEY + "percent", totalPct), "", false));
        if (!effectRows.isEmpty())
        {
            out.add(new Line('h', "", tr(KEY + "pool_header", poolName), "", "", false));
            for (List<String> r : effectRows)
            {
                String name = r.size() > 2 && !r.get(2).isBlank() ? r.get(2) : (r.size() > 1 ? r.get(1) : "");
                String chance = r.size() > 3 ? r.get(3) : "";
                out.add(new Line('e', "", name, chance, "", (band++ & 1) == 1));
            }
        }
        return out;
    }

    /** The brief per-row note when a cosmetic cannot roll a quality the crate otherwise offers, else blank. */
    private String note(boolean magicEligible, boolean superEligible)
    {
        List<String> parts = new ArrayList<>();
        if (crateHasSuper && !superEligible)
            parts.add(tr(KEY + "no_super"));
        if (crateHasMagic && !magicEligible)
            parts.add(tr(KEY + "no_magic"));
        return String.join(", ", parts);
    }

    @Override
    protected void init()
    {
        super.init();
        headerName = crateName;
        iconDraws.clear();

        // The quality chances, stated once, centred as one row: Normal in the ordinary row colour, Super red and
        // Magic pink (the crate's quality colours), so a screenshot reads them at a glance.
        String nrm = tr(KEY + "quality_normal", normalPct);
        String sup = tr(KEY + "quality_super", superPct);
        String mag = tr(KEY + "quality_magic", magicPct);
        int gap = 10;
        int width = width(nrm) + gap + width(sup) + gap + width(mag);
        int qx = (uiWidth - width) / 2;
        label(nrm, qx, SUMMARY_Y, CosmeticStyle.NORMAL);
        qx += width(nrm) + gap;
        label(sup, qx, SUMMARY_Y, CosmeticStyle.SUPER);
        qx += width(sup) + gap;
        label(mag, qx, SUMMARY_Y, CosmeticStyle.MAGIC);
        rect(rowBandLeft(), SEP_Y, rowBandWidth(), 1, 0x33FFFFFF);

        List<Line> lines = lines();
        int maxRows = Math.max(1, (GuiTheme.contentBottom(uiHeight) - LIST_TOP) / ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - maxRows)));
        int end = Math.min(lines.size(), scroll + maxRows);
        int y = LIST_TOP;
        for (int i = scroll; i < end; i++)
        {
            drawLine(lines.get(i), y);
            y += ROW_H;
        }
        scrollList(rowBandLeft(), rowControlRight(), LIST_TOP, ROW_H, maxRows, lines.size(), scroll, v ->
        {
            scroll = v;
            rebuildWidgets();
        });

        btn(UI_W / 2 - 40, footerY(), 80, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.close"),
                () -> net.minecraft.client.Minecraft.getInstance().setScreen(null));
    }

    private void drawLine(Line line, int y)
    {
        int ty = y + (ROW_H - 9) / 2;
        int left = rowBandLeft();
        int right = rowControlRight();
        if (line.shade())
            rect(left - 1, y, rowBandWidth() + 2, ROW_H - 1, ROW_SHADE);

        switch (line.kind())
        {
        case 'h':
            label(line.name(), left, ty, GuiTheme.COLOR_TITLE);
            return;
        case 't':
        {
            int vx = right - width(line.value());
            label(line.name(), left, ty, GuiTheme.COLOR_TITLE);
            label(line.value(), vx, ty, GuiTheme.COLOR_TITLE);
            return;
        }
        case 'e':
        {
            int nameX = left + ICON + ICON_GAP;
            int vx = right - width(line.value());
            label(line.value(), vx, ty, GuiTheme.COLOR_MUTED);
            fitted(line.name(), nameX, ty, vx - 4 - nameX, GuiTheme.COLOR_ROW);
            return;
        }
        case 'c':
        default:
        {
            iconDraws.add(new IconDraw(line.id(), line.name(), left, y + (ROW_H - ICON) / 2, ICON));
            int nameX = left + ICON + ICON_GAP;
            int vx = right - width(line.value());
            label(line.value(), vx, ty, GuiTheme.COLOR_ROW);
            int nameRight = vx - 4;
            if (!line.note().isBlank())
            {
                int noteX = vx - 4 - width(line.note());
                label(line.note(), noteX, ty, GuiTheme.COLOR_MUTED);
                nameRight = noteX - 4;
            }
            fitted(line.name(), nameX, ty, nameRight - nameX, GuiTheme.COLOR_ROW);
        }
        }
    }

    /** A left label ellipsized to an explicit width, so a long cosmetic name never runs under the percentage. */
    private void fitted(String text, int x, int y, int avail, int color)
    {
        if (avail <= 0 || text == null)
            return;
        String shown = font != null && font.width(text) > avail ? GuiText.ellipsize(font, text, avail) : text;
        label(shown, x, y, color);
    }

    private int width(String text)
    {
        return font == null || text == null ? 0 : font.width(text);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        super.render(g, mouseX, mouseY, partialTick);
        if (iconDraws.isEmpty())
            return;
        // Item icons draw through GuiGraphics#renderItem, which needs the scaled canvas pose the base render()
        // sets up and then pops. Re-enter the same transform (as the shop portrait does) and paint the staged
        // icons on top of their already-drawn rows; each cell sits to the LEFT of its name, so nothing overlaps.
        g.flush();
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(originX(), originY(), 0.0F);
        pose.scale((float) guiScale, (float) guiScale, 1.0F);
        for (IconDraw d : iconDraws)
        {
            CosmeticStyle.tile(g, d.x(), d.y(), d.size(), CosmeticQuality.NORMAL, false, false, false);
            CosmeticStyle.emblem(g, font, d.catalogId(), d.name(), d.x(), d.y(), d.size(), GuiTheme.COLOR_ROW);
        }
        pose.popPose();
    }

    private static boolean isZero(String s)
    {
        return s == null || s.isBlank() || "0".equals(s.trim());
    }

    private static String at(List<String> meta, int i, String fallback)
    {
        return meta != null && meta.size() > i ? meta.get(i) : fallback;
    }
}
