package net.shurui.shuruisutilities.client.gui.editor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.cosmetics.CosmeticStyle;
import net.shurui.shuruisutilities.client.gui.cosmetics.CosmeticTile;
import net.shurui.shuruisutilities.client.gui.cosmetics.OwnedCopy;
import net.shurui.shuruisutilities.client.gui.preview.LivePlayerPreview;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticQuality;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The mounts section: the mounts a player owns, which one is equipped, whether one is out, and a Summon/Recall
 * button. Split out of the wardrobe so mounts have a dedicated place, reached from the cosmetics menu.
 *
 * <p>The same portrait-and-grid shape as {@link WardrobeScreen}, minus the slot column, because a mount is one
 * slot. One tile per COPY, click to select and click again to equip; the footer summons or recalls the equipped
 * mount through the existing {@code /cosmetic summon} path.
 *
 * <p>{@code meta = [equippedInstanceId, summoned, flyingCount, flyingCatalogId...]}. The flying set says which of
 * the shown mounts fly, so each is labelled without the client holding a copy of the mount-data table. Read
 * defensively past the end, so an older server produces a duller screen rather than a failed open.
 */
public class CosmeticMountsScreen extends SagaBaseScreen
{
    private static final int UI_W = 440;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final int PREVIEW_X = 8;
    private static final int PREVIEW_Y = 30;
    private static final int PREVIEW_W = 150;
    private static final int PREVIEW_H = 172;

    private static final int DETAIL_X = PREVIEW_X;
    private static final int DETAIL_W = PREVIEW_W;
    private static final int DETAIL_Y = 206;
    private static final int DETAIL_H = 28;

    private static final int GRID_X = 168;
    private static final int GRID_TOP = 30;
    private static final int GRID_TILE = 42;
    private static final int GRID_GAP = 5;

    private static final float SPIN_PERIOD_MS = 12000.0F;

    private final List<OwnedCopy> copies;
    private final String equippedInstance;
    private final boolean summoned;
    private final Set<String> flyingCatalogs = new LinkedHashSet<>();

    /** Remembered across the server refresh so equipping does not scroll the grid back to the top. */
    private static int stickyScroll;
    private static String stickySelected = "";

    private int scroll;
    private String selected = "";

    private float yaw = (float) Math.PI;
    private float pitch;
    private long lastFrameMs;
    private boolean dragging;

    public CosmeticMountsScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.mounts.title"), UI_W, UI_H, null);
        this.copies = OwnedCopy.parse(rows);
        this.equippedInstance = meta.size() > 0 ? meta.get(0) : "";
        this.summoned = meta.size() > 1 && Boolean.parseBoolean(meta.get(1));
        int flyingCount = meta.size() > 2 ? parseI(meta.get(2)) : 0;
        for (int i = 0; i < flyingCount && 3 + i < meta.size(); i++)
            flyingCatalogs.add(meta.get(3 + i));

        // Carry the selection through a refresh; fall back to the equipped mount, else the first owned.
        scroll = stickyScroll;
        selected = byInstance(stickySelected) != null ? stickySelected : defaultSelection();
    }

    private static int parseI(String s)
    {
        try
        {
            return Integer.parseInt(s.trim());
        }
        catch (RuntimeException e)
        {
            return 0;
        }
    }

    private OwnedCopy byInstance(String instanceId)
    {
        if (instanceId == null || instanceId.isBlank())
            return null;
        for (OwnedCopy c : copies)
            if (instanceId.equals(c.instanceId))
                return c;
        return null;
    }

    private String defaultSelection()
    {
        if (byInstance(equippedInstance) != null)
            return equippedInstance;
        return copies.isEmpty() ? "" : copies.get(0).instanceId;
    }

    private boolean isFlying(OwnedCopy c)
    {
        return c != null && flyingCatalogs.contains(c.catalogId);
    }

    @Override
    protected void init()
    {
        super.init();
        Minecraft mc = Minecraft.getInstance();
        headerName = mc.player == null ? null : mc.player.getGameProfile().getName();

        rect(PREVIEW_X, PREVIEW_Y, PREVIEW_W, PREVIEW_H, 0xFF2A2A30);
        rect(PREVIEW_X + 1, PREVIEW_Y + 1, PREVIEW_W - 2, PREVIEW_H - 2, 0xFF121216);

        buildDetail();
        buildGrid();

        OwnedCopy sel = byInstance(selected);
        int y = footerY();
        int h = footerBtnHeight();

        var equip = btn(PREVIEW_X, y, 72, h, Component.translatable("gui.dmz_ragnarok.core.mounts.equip"),
                () -> equip(byInstance(selected)));
        equip.active = sel != null && !sel.instanceId.equals(equippedInstance);

        // One toggle: it summons the equipped mount or recalls the one already out. The label follows the state
        // the server reported, and the server is the sole authority on whether anything happens.
        btn(PREVIEW_X + 76, y, 82, h, Component.translatable(summoned
                        ? "gui.dmz_ragnarok.core.mounts.recall" : "gui.dmz_ragnarok.core.mounts.summon"),
                () -> EditorScreens.act("cosmetic_mounts", "summon"));

        btn(rowControlRight() - 64, y, 64, h, Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                () -> EditorScreens.reopen("cosmetics"));
    }

    /** What the selected mount is: its name, quality, and whether it flies or walks. */
    private void buildDetail()
    {
        rect(DETAIL_X, DETAIL_Y, DETAIL_W, DETAIL_H, 0xFF1A1A20);
        OwnedCopy sel = byInstance(selected);
        if (sel == null)
        {
            labelCentered(tr(copies.isEmpty() ? "gui.dmz_ragnarok.core.mounts.none_owned"
                            : "gui.dmz_ragnarok.core.mounts.pick_hint"),
                    DETAIL_X + DETAIL_W / 2, DETAIL_Y + 10, GuiTheme.COLOR_MUTED);
            return;
        }
        label(sel.name, DETAIL_X + 6, DETAIL_Y + 5, CosmeticStyle.color(sel.quality));
        String move = tr(isFlying(sel) ? "gui.dmz_ragnarok.core.mounts.flying"
                : "gui.dmz_ragnarok.core.mounts.ground");
        label(move, DETAIL_X + 6, DETAIL_Y + 16, GuiTheme.COLOR_MUTED);
    }

    private void buildGrid()
    {
        int gridW = rowControlRight() - GRID_X;
        int pitch = GRID_TILE + GRID_GAP;
        int cols = Math.max(1, (gridW + GRID_GAP) / pitch);
        int rows = Math.max(1, (GuiTheme.contentBottom(uiHeight) - GRID_TOP) / pitch);
        int totalRows = Math.max(1, (copies.size() + cols - 1) / cols);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, totalRows - rows)));

        int first = scroll * cols;
        int last = Math.min(copies.size(), first + rows * cols);
        for (int i = first; i < last; i++)
        {
            OwnedCopy c = copies.get(i);
            int cell = i - first;
            int x = GRID_X + (cell % cols) * pitch;
            int yy = GRID_TOP + (cell / cols) * pitch;
            boolean worn = c.instanceId.equals(equippedInstance);
            CosmeticTile tile = new CosmeticTile(x, yy, GRID_TILE, c.catalogId, c.name, c.quality, () -> pick(c));
            tile.equipped(worn).selected(c.instanceId.equals(selected)).counter(c.counterLabel());
            addRenderableWidget(tile);
            tooltip(x, yy, GRID_TILE, GRID_TILE, tip(c));
        }
        if (copies.isEmpty())
            labelCentered(tr("gui.dmz_ragnarok.core.mounts.none_owned"), GRID_X + gridW / 2, GRID_TOP + 20,
                    GuiTheme.COLOR_MUTED);
        scrollList(GRID_X, GRID_X + gridW, GRID_TOP, pitch, rows, totalRows, scroll, v ->
        {
            scroll = v;
            stickyScroll = v;
            rebuildWidgets();
        });
    }

    private String tip(OwnedCopy c)
    {
        StringBuilder out = new StringBuilder(c.name);
        if (c.quality != CosmeticQuality.NORMAL)
            out.append('\n').append(tr(c.quality.langKey()));
        out.append('\n').append(tr(isFlying(c) ? "gui.dmz_ragnarok.core.mounts.flying"
                : "gui.dmz_ragnarok.core.mounts.ground"));
        return out.toString();
    }

    private void pick(OwnedCopy c)
    {
        if (c.instanceId.equals(selected))
        {
            equip(c);
            return;
        }
        selected = c.instanceId;
        stickySelected = selected;
        rebuildWidgets();
    }

    private void equip(OwnedCopy c)
    {
        if (c == null || c.instanceId.equals(equippedInstance))
            return;
        stickySelected = c.instanceId;
        EditorScreens.act("cosmetic_mounts", "equip", c.catalogId, c.instanceId);
    }

    // ---------------------------------------------------------------- the portrait

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        super.render(g, mouseX, mouseY, partialTick);
        if (!LivePlayerPreview.available())
            return;
        g.flush();
        advanceSpin();

        float infl = LivePlayerPreview.modelInflation();
        int boxH = PREVIEW_H - 8;
        int cxV = PREVIEW_X + PREVIEW_W / 2;
        int modelHV = (int) (boxH * 0.78F);
        int feetV = PREVIEW_Y + 4 + boxH / 2 + modelHV / 2;
        int scaleV = Math.max(4, (int) (modelHV / 1.9F / infl));

        int cxS = (int) Math.round(originX() + cxV * guiScale);
        int feetS = (int) Math.round(originY() + feetV * guiScale);
        int scaleS = Math.max(1, (int) Math.round(scaleV * guiScale));

        g.enableScissor((int) Math.round(originX() + (PREVIEW_X + 2) * guiScale),
                (int) Math.round(originY() + (PREVIEW_Y + 2) * guiScale),
                (int) Math.round(originX() + (PREVIEW_X + PREVIEW_W - 2) * guiScale),
                (int) Math.round(originY() + (PREVIEW_Y + PREVIEW_H - 2) * guiScale));
        LivePlayerPreview.render(g, cxS, feetS, scaleS, yaw, pitch);
        g.disableScissor();
    }

    private void advanceSpin()
    {
        long now = net.minecraft.Util.getMillis();
        long previous = lastFrameMs;
        lastFrameMs = now;
        if (dragging || previous == 0L)
            return;
        long dt = Math.max(0L, Math.min(250L, now - previous));
        yaw += (float) (dt / SPIN_PERIOD_MS * Math.PI * 2.0);
    }

    private boolean inPreview(double vx, double vy)
    {
        return vx >= PREVIEW_X && vx < PREVIEW_X + PREVIEW_W && vy >= PREVIEW_Y && vy < PREVIEW_Y + PREVIEW_H;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button)
    {
        if (super.mouseClicked(mx, my, button))
            return true;
        if (button == 0 && openDropdown == null && inPreview(toVirtualX(mx), toVirtualY(my)))
        {
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY)
    {
        if (dragging)
        {
            yaw += (float) (dragX * 0.016);
            pitch = Math.max(-1.2F, Math.min(1.2F, pitch + (float) (dragY * 0.016)));
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button)
    {
        if (dragging && button == 0)
        {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    protected int bandRight()
    {
        return uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET;
    }
}
