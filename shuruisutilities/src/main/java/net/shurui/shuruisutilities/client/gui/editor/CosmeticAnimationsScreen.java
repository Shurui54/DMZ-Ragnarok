package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

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
 * The animations section: the triggered animations a player owns, grouped by the trigger they play on, with equip
 * and a Preview that plays the animation on yourself through the existing preview path. Split out of the wardrobe
 * so animations have a dedicated place, reached from the cosmetics menu.
 *
 * <p>The tab bar and the equipped state are built ENTIRELY from what the server sent in {@code meta} (flat
 * {@code [slotKey, equippedInstanceId]} pairs, one per triggered slot), never from a client-side list of triggers,
 * so restructuring the trigger set on the server flows through here with no edit. Each owned copy's own slot key
 * (row field 2) is the trigger it belongs to.
 */
public class CosmeticAnimationsScreen extends SagaBaseScreen
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

    private final List<String> triggerKeys = new ArrayList<>();
    private final List<String> triggerEquipped = new ArrayList<>();
    private final List<OwnedCopy> copies;

    /** Remembered across the server refresh so equipping or previewing does not reset the tab or scroll. */
    private static String stickyTriggerKey = "";
    private static int stickyScroll;
    private static String stickySelected = "";

    private int tab;
    private int scroll;
    private String selected = "";

    private float yaw = (float) Math.PI;
    private float pitch;
    private long lastFrameMs;
    private boolean dragging;

    public CosmeticAnimationsScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.animations.title"), UI_W, UI_H, null);
        this.copies = OwnedCopy.parse(rows);
        for (int i = 0; i + 1 < meta.size(); i += 2)
        {
            triggerKeys.add(meta.get(i));
            triggerEquipped.add(meta.get(i + 1));
        }
        // Carry the tab through a refresh; fall back to the first trigger holding something, else the first.
        int restored = stickyTriggerKey.isBlank() ? -1 : triggerKeys.indexOf(stickyTriggerKey);
        if (restored >= 0)
            tab = restored;
        else
            for (int i = 0; i < triggerEquipped.size(); i++)
                if (!triggerEquipped.get(i).isBlank())
                {
                    tab = i;
                    break;
                }
        scroll = stickyScroll;
        selected = byInstance(stickySelected) != null ? stickySelected : defaultSelectionFor(currentTriggerKey());
    }

    private String currentTriggerKey()
    {
        return tab >= 0 && tab < triggerKeys.size() ? triggerKeys.get(tab) : "";
    }

    private String equippedInstance(String triggerKey)
    {
        int i = triggerKeys.indexOf(triggerKey);
        return i < 0 ? "" : triggerEquipped.get(i);
    }

    private List<OwnedCopy> forTrigger(String triggerKey)
    {
        List<OwnedCopy> out = new ArrayList<>();
        // Every owned animation can play on every trigger (one equip covers a whole in/out pair, and any animation
        // may go in either pair), so each tab lists them all; the tab decides only WHERE an equip lands.
        out.addAll(copies);
        return out;
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

    private String defaultSelectionFor(String triggerKey)
    {
        String worn = equippedInstance(triggerKey);
        if (byInstance(worn) != null)
            return worn;
        return copies.isEmpty() ? "" : copies.get(0).instanceId;
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
        String triggerKey = currentTriggerKey();
        int y = footerY();
        int h = footerBtnHeight();

        var equip = btn(PREVIEW_X, y, 64, h, Component.translatable("gui.dmz_ragnarok.core.animations.equip"),
                () -> equip(byInstance(selected)));
        equip.active = sel != null && !sel.instanceId.equals(equippedInstance(triggerKey));

        var off = btn(PREVIEW_X + 68, y, 44, h,
                Component.translatable("gui.dmz_ragnarok.core.animations.take_off"),
                () -> EditorScreens.act("cosmetic_animations", "unequip", triggerKey));
        off.active = !equippedInstance(triggerKey).isBlank();

        // Preview plays the selected animation on the player. With none selected it previews whatever is equipped
        // in this trigger, matching /cosmetic preview.
        var preview = btn(PREVIEW_X + 116, y, 42, h,
                Component.translatable("gui.dmz_ragnarok.core.animations.preview"),
                () ->
                {
                    OwnedCopy c = byInstance(selected);
                    if (c != null)
                        EditorScreens.act("cosmetic_animations", "preview", triggerKey, c.catalogId);
                    else
                        EditorScreens.act("cosmetic_animations", "preview", triggerKey);
                });
        preview.active = sel != null || !equippedInstance(triggerKey).isBlank();

        btn(rowControlRight() - 64, y, 64, h, Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                () -> EditorScreens.reopen("cosmetics"));
    }

    private void buildDetail()
    {
        rect(DETAIL_X, DETAIL_Y, DETAIL_W, DETAIL_H, 0xFF1A1A20);
        OwnedCopy sel = byInstance(selected);
        if (sel == null)
        {
            labelCentered(tr(copies.isEmpty() ? "gui.dmz_ragnarok.core.animations.none_owned"
                            : "gui.dmz_ragnarok.core.animations.pick_hint"),
                    DETAIL_X + DETAIL_W / 2, DETAIL_Y + 10, GuiTheme.COLOR_MUTED);
            return;
        }
        label(sel.name, DETAIL_X + 6, DETAIL_Y + 5, CosmeticStyle.color(sel.quality));
        String trigger = tr("gui.dmz_ragnarok.cosmetics.slot." + sel.slotKey);
        label(tr("gui.dmz_ragnarok.core.animations.plays_on", trigger), DETAIL_X + 6, DETAIL_Y + 16,
                GuiTheme.COLOR_MUTED);
    }

    private void buildGrid()
    {
        int gridW = rowControlRight() - GRID_X;
        String[] captions = new String[triggerKeys.size()];
        for (int i = 0; i < triggerKeys.size(); i++)
            captions[i] = tr("gui.dmz_ragnarok.cosmetics.slot." + triggerKeys.get(i));
        int top = triggerKeys.isEmpty() ? GRID_TOP : tabs(GRID_X, GRID_TOP, gridW, captions, tab, i ->
        {
            tab = i;
            scroll = 0;
            stickyTriggerKey = triggerKeys.get(i);
            stickyScroll = 0;
            selected = defaultSelectionFor(triggerKeys.get(i));
            stickySelected = selected;
            rebuildWidgets();
        });

        List<OwnedCopy> shown = forTrigger(currentTriggerKey());
        int pitch = GRID_TILE + GRID_GAP;
        int cols = Math.max(1, (gridW + GRID_GAP) / pitch);
        int rows = Math.max(1, (GuiTheme.contentBottom(uiHeight) - top) / pitch);
        int totalRows = Math.max(1, (shown.size() + cols - 1) / cols);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, totalRows - rows)));

        int first = scroll * cols;
        int last = Math.min(shown.size(), first + rows * cols);
        for (int i = first; i < last; i++)
        {
            OwnedCopy c = shown.get(i);
            int cell = i - first;
            int x = GRID_X + (cell % cols) * pitch;
            int yy = top + (cell / cols) * pitch;
            boolean worn = c.instanceId.equals(equippedInstance(currentTriggerKey()));
            CosmeticTile tile = new CosmeticTile(x, yy, GRID_TILE, c.catalogId, c.name, c.quality, () -> pick(c));
            tile.equipped(worn).selected(c.instanceId.equals(selected)).counter(c.counterLabel());
            addRenderableWidget(tile);
            tooltip(x, yy, GRID_TILE, GRID_TILE, tip(c));
        }
        if (shown.isEmpty())
            labelCentered(tr("gui.dmz_ragnarok.core.animations.none_in_trigger"), GRID_X + gridW / 2, top + 20,
                    GuiTheme.COLOR_MUTED);
        scrollList(GRID_X, GRID_X + gridW, top, pitch, rows, totalRows, scroll, v ->
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
        out.append('\n').append(tr("gui.dmz_ragnarok.core.animations.plays_on",
                tr("gui.dmz_ragnarok.cosmetics.slot." + currentTriggerKey())));
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
        // Equip into the tab being viewed, not the animation's home slot, so the Teleport tab can take any animation.
        String triggerKey = currentTriggerKey();
        if (c == null || triggerKey.isBlank() || c.instanceId.equals(equippedInstance(triggerKey)))
            return;
        stickyTriggerKey = triggerKey;
        stickySelected = c.instanceId;
        EditorScreens.act("cosmetic_animations", "equip", triggerKey, c.catalogId, c.instanceId);
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
