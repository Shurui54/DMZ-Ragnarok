package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.cosmetics.CosmeticRenderOptions;
import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.cosmetics.CosmeticStyle;
import net.shurui.shuruisutilities.client.gui.cosmetics.CosmeticTile;
import net.shurui.shuruisutilities.client.gui.cosmetics.OwnedCopy;
import net.shurui.shuruisutilities.client.gui.preview.LivePlayerPreview;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticDef;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticQuality;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticTracker;
import net.shurui.shuruisutilities.cosmetics.wardrobe.client.CosmeticClientStore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The wardrobe: your character, what you have on, and everything you own.
 *
 * <h2>The layout, and where the idea came from</h2>
 * The shape is Team Fortress 2's loadout screen read as an IDEA rather than copied: a large character you
 * recognise as yourself, the slots for what you are wearing gathered beside it, a browsable grid of what you own,
 * and quality readable without reading. Everything drawn is ours: the suite's panel, slot and button art, the
 * suite's colours, and DragonMineZ's own race model for the character. No Valve asset, sprite, layout metric or
 * string is involved.
 *
 * <p>Left: the live portrait (see {@link LivePlayerPreview}), turning slowly, draggable. Beside it a column of
 * slot boxes, each showing the copy worn in that slot; clicking one opens {@link CosmeticPickerScreen}, the
 * full-width visual picker for that slot. Under both, the detail strip for whatever is selected. Right: the owned
 * grid, one tile per COPY, with a slot tab bar above it. The footer wears, takes off and cycles the displayed
 * counter, so nothing on this screen needs a second screen to finish.
 *
 * <h2>One row per copy</h2>
 * {@code rows} are copies, not cosmetics: {@code [catalogId, displayName, slotKey, qualityKey, trackerCount,
 * chosenTrackerId, rarity, instanceId, effectId, shownCount, effectName]}. A player holding a Super bat hat with 412 kills
 * and a plain one sees two tiles and can wear either, because those are not the same item to anybody who owns
 * them. {@code meta} is flat pairs of {@code slotKey, wornInstanceIdOrBlank}, so the screen never has to know
 * which slots exist and PET appeared here without this file changing.
 *
 * <p>Both come from the server. The client catalogue cache is consulted only to name a tracker and to read a
 * description, never to decide what may be worn: an equip is re-validated against the ownership ledger server
 * side, so a forged row here buys a refusal.
 *
 * <h2>NOTHING IS DRAWN ON THE CHARACTER YET</h2>
 * The portrait is real and is the player's actual race, form, hair and colours, but the cosmetic itself is not
 * rendered on it, because the render layer is a later milestone. The wardrobe is honest about that by showing
 * what is worn in the slot boxes rather than by pretending the model has it on.
 */
public class WardrobeScreen extends SagaBaseScreen
{
    /** Wider than the shared canvas, exactly as {@code FormEditScreen} is, because a portrait and a grid have to
     *  sit side by side. The HEIGHT is the shared one, so the panel only ever grows sideways. */
    private static final int UI_W = 580;
    private static final int UI_H = GuiTheme.SCREEN_H;

    // The loadout column, 8..300.
    private static final int PREVIEW_X = 8;
    private static final int PREVIEW_Y = 30;
    private static final int PREVIEW_W = 150;
    private static final int PREVIEW_H = 172;

    /** Slot boxes: a column beside the portrait, each a tile plus a two-line name plate. */
    private static final int SLOT_X = 164;
    private static final int SLOT_Y = 32;
    private static final int SLOT_CELL_W = 136;
    private static final int SLOT_TILE_MAX = 36;
    private static final int SLOT_PITCH_MAX = 44;

    // The detail strip under the loadout column.
    private static final int DETAIL_X = PREVIEW_X;
    private static final int DETAIL_W = SLOT_X + SLOT_CELL_W - PREVIEW_X;
    private static final int DETAIL_Y = 206;
    private static final int DETAIL_H = 28;

    // The owned grid, 308..558 (the column rowControlRight() reserves to).
    private static final int GRID_X = 308;
    private static final int GRID_TOP = 30;
    private static final int GRID_W = 250;
    private static final int GRID_TILE = 42;
    private static final int GRID_GAP = 5;
    private static final int GRID_COLS = (GRID_W + GRID_GAP) / (GRID_TILE + GRID_GAP);

    /** A full turn of the portrait, in milliseconds. Slow enough to read as alive rather than as a spin. */
    private static final float SPIN_PERIOD_MS = 12000.0F;

    private final List<String> slotKeys = new ArrayList<>();
    private final List<String> slotWornInstance = new ArrayList<>();
    private final List<OwnedCopy> copies;

    /**
     * The slot, scroll and selection the player last had, remembered ACROSS the server refresh that rebuilds this
     * screen from a fresh packet. Static because every equip round-trips to the server, which sends a brand new
     * {@code WardrobeScreen}; without this the new instance defaulted to the first slot every time, which is the
     * "it keeps jumping back to the hat tab" the owner reported. Keyed by slot KEY, never index, so it survives a
     * slot being added or removed between opens.
     */
    private static String stickySlotKey = "";
    private static int stickyScroll;
    private static String stickySelected = "";

    /** Which slot tab the grid is showing. Always a valid index once there is at least one slot. */
    private int tab;

    /** Scroll position of the grid, in ROWS. */
    private int scroll;

    /** The selected copy's instance id, or blank. Selection drives the detail strip and the footer. */
    private String selected = "";

    // Portrait state. The turn is wall-clock driven so it keeps moving while a singleplayer world is paused.
    private float yaw = (float) Math.PI;
    private float pitch;
    private long lastFrameMs;
    private boolean dragging;

    public WardrobeScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.wardrobe.title"), UI_W, UI_H, null);
        this.copies = OwnedCopy.parse(rows);
        for (int i = 0; i + 1 < meta.size(); i += 2)
        {
            slotKeys.add(meta.get(i));
            slotWornInstance.add(meta.get(i + 1));
        }
        // Open on the slot the player is most likely to be here for: the first one holding something, else the
        // first slot at all. Picking a tab for them beats opening on an empty one.
        for (int i = 0; i < slotWornInstance.size(); i++)
            if (!slotWornInstance.get(i).isBlank())
            {
                tab = i;
                break;
            }
        // But a refresh from the server (an equip, an unequip, a tracker change) must land the player back where
        // they were, not on the first slot. Restore the remembered slot and scroll when they still exist.
        int restored = stickySlotKey.isBlank() ? -1 : slotKeys.indexOf(stickySlotKey);
        if (restored >= 0)
            tab = restored;
        scroll = stickyScroll;
        selected = byInstance(stickySelected) != null ? stickySelected : defaultSelectionFor(currentSlotKey());
        remember();
    }

    /** Store the slot, scroll and selection so the next server refresh reopens on the same place. */
    private void remember()
    {
        stickySlotKey = currentSlotKey();
        stickyScroll = scroll;
        stickySelected = selected;
    }

    private String currentSlotKey()
    {
        return tab >= 0 && tab < slotKeys.size() ? slotKeys.get(tab) : "";
    }

    /** Every copy this player owns that fits a slot, in the order the server sent them (best copy first). */
    private List<OwnedCopy> forSlot(String slotKey)
    {
        List<OwnedCopy> out = new ArrayList<>();
        for (OwnedCopy c : copies)
            if (c.slotKey.equals(slotKey))
                out.add(c);
        return out;
    }

    private OwnedCopy byInstance(String instanceId)
    {
        if (instanceId == null || instanceId.isBlank())
            return null;
        for (OwnedCopy c : copies)
            if (instanceId.equals(c.instanceId))
                return c;
        // Version skew, and the only reason this fallback exists: a server built before the wardrobe sent
        // instances puts a CATALOGUE id in this field. Matching on that shows the right cosmetic in the slot
        // box, possibly not the right copy, which beats reporting an empty slot the player is visibly wearing.
        for (OwnedCopy c : copies)
            if (instanceId.equals(c.catalogId))
                return c;
        return null;
    }

    private String wornInstance(String slotKey)
    {
        int i = slotKeys.indexOf(slotKey);
        return i < 0 ? "" : slotWornInstance.get(i);
    }

    /** The copy to put in the detail strip when the grid moves to a slot: what is worn there, else the first. */
    private String defaultSelectionFor(String slotKey)
    {
        String worn = wornInstance(slotKey);
        if (byInstance(worn) != null)
            return worn;
        for (OwnedCopy c : copies)
            if (c.slotKey.equals(slotKey))
                return c.instanceId;
        return "";
    }

    @Override
    protected void init()
    {
        super.init();
        Minecraft mc = Minecraft.getInstance();
        // The "named entity goes top left" house rule: the character on screen is the player, so say who.
        headerName = mc.player == null ? null : mc.player.getGameProfile().getName();

        buildLoadout();
        buildDetail();
        buildGrid();

        OwnedCopy sel = byInstance(selected);
        boolean wearable = sel != null && !sel.instanceId.equals(wornInstance(sel.slotKey));
        boolean removable = sel != null && !wornInstance(sel.slotKey).isBlank();

        int y = footerY();
        int h = footerBtnHeight();
        var wear = btn(PREVIEW_X, y, 64, h, Component.translatable("gui.dmz_ragnarok.core.wardrobe.wear"),
                () -> equip(byInstance(selected)));
        wear.active = wearable;
        var off = btn(PREVIEW_X + 68, y, 64, h, Component.translatable("gui.dmz_ragnarok.core.wardrobe.take_off"),
                () ->
                {
                    OwnedCopy c = byInstance(selected);
                    if (c != null)
                        EditorScreens.act("wardrobe", "unequip", c.slotKey);
                });
        off.active = removable;
        var counter = btn(PREVIEW_X + 136, y, 118, h,
                Component.literal(tr("gui.dmz_ragnarok.core.wardrobe.counter", trackerLabel(sel))),
                () ->
                {
                    OwnedCopy c = byInstance(selected);
                    if (c != null)
                        EditorScreens.act("wardrobe", "settracker", c.catalogId,
                                nextTracker(c.catalogId, c.chosenTracker));
                });
        counter.active = sel != null && sel.trackerCount > 0;

        // Hide-hair-under-helmets: a client display preference the worn-cosmetic renderer reads. A plain on/off
        // toggle whose label carries its own state, so there is no separate status line. The switch itself lives in
        // CosmeticRenderOptions; if the render layer ships a richer version of the same get/set pair the button
        // keeps working unchanged.
        int menuX = rowControlRight() - 64;
        int hairX = PREVIEW_X + 258;
        int hairW = Math.max(60, menuX - 4 - hairX);
        boolean hideHair = CosmeticRenderOptions.hideHairUnderHelmets();
        btn(hairX, y, hairW, h, Component.translatable(hideHair
                        ? "gui.dmz_ragnarok.core.wardrobe.hair_hide_on"
                        : "gui.dmz_ragnarok.core.wardrobe.hair_hide_off"),
                () ->
                {
                    CosmeticRenderOptions.setHideHairUnderHelmets(!hideHair);
                    rebuildWidgets();
                });

        // Back to the cosmetics menu, the front door the wardrobe now hangs off, rather than all the way out to
        // the player hub: mounts, animations and the shop are one hop away from there.
        btn(menuX, y, 64, h, Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                () -> EditorScreens.reopen("cosmetics"));
    }

    /** The portrait frame and the column of slot boxes beside it. */
    private void buildLoadout()
    {
        rect(PREVIEW_X, PREVIEW_Y, PREVIEW_W, PREVIEW_H, 0xFF2A2A30);
        rect(PREVIEW_X + 1, PREVIEW_Y + 1, PREVIEW_W - 2, PREVIEW_H - 2, 0xFF121216);

        // Derived, never hardcoded: BODY switching on or a sixth slot arriving has to shrink the boxes rather
        // than run them over the detail strip. The enum is explicitly built to make adding a slot free, and this
        // is the screen's half of that promise.
        int count = Math.max(1, slotKeys.size());
        int pitch = Math.max(14, Math.min(SLOT_PITCH_MAX, (DETAIL_Y - 4 - SLOT_Y) / count));
        int size = Math.max(10, Math.min(SLOT_TILE_MAX, pitch - 4));

        for (int i = 0; i < slotKeys.size(); i++)
        {
            final String key = slotKeys.get(i);
            int y = SLOT_Y + i * pitch;
            OwnedCopy worn = byInstance(wornInstance(key));
            CosmeticQuality q = worn == null ? CosmeticQuality.NORMAL : worn.quality;

            CosmeticTile tile = new CosmeticTile(SLOT_X, y, size, worn == null ? "" : worn.catalogId,
                    worn == null ? "" : worn.name, q, () -> openPicker(key));
            tile.empty(worn == null).equipped(worn != null).counter(worn == null ? "" : worn.counterLabel());
            addRenderableWidget(tile);

            // The plate beside the tile opens the same picker, so the whole box is one target rather than a
            // small square the player has to find.
            int plateX = SLOT_X + size + 4;
            int plateW = SLOT_CELL_W - size - 8;
            rowBtn(plateX, y, plateW, size, Component.empty(), () -> openPicker(key));
            label(tr("gui.dmz_ragnarok.cosmetics.slot." + key), plateX + 2, y + size / 2 - 9,
                    GuiTheme.COLOR_MUTED);
            label(worn == null ? tr("gui.dmz_ragnarok.core.wardrobe.empty") : worn.name, plateX + 2,
                    y + size / 2 + 1, worn == null ? GuiTheme.COLOR_DISABLED : CosmeticStyle.color(q));
            tooltip(SLOT_X, y, size + 4 + plateW, size, slotTip(key, worn));
        }
    }

    /** The two-line strip under the loadout column: what is selected, and what makes it special. */
    private void buildDetail()
    {
        rect(DETAIL_X, DETAIL_Y, DETAIL_W, DETAIL_H, 0xFF1A1A20);
        OwnedCopy sel = byInstance(selected);
        if (sel == null)
        {
            if (copies.isEmpty())
                labelCentered(tr("gui.dmz_ragnarok.core.wardrobe.none_owned"), DETAIL_X + DETAIL_W / 2,
                        DETAIL_Y + 10, GuiTheme.COLOR_MUTED);
            return;
        }
        String right = sel.quality == CosmeticQuality.NORMAL ? sel.rarity : tr(sel.quality.langKey());
        label(sel.name, DETAIL_X + 6, DETAIL_Y + 5, CosmeticStyle.color(sel.quality));
        if (!right.isBlank())
            label(right, DETAIL_X + DETAIL_W - 6 - font.width(right), DETAIL_Y + 5,
                    CosmeticStyle.color(sel.quality));
        String line = detailLine(sel);
        if (!line.isBlank())
            label(line, DETAIL_X + 6, DETAIL_Y + 16, GuiTheme.COLOR_MUTED);
    }

    /** The slot tab bar and the page of tiles under it. */
    private void buildGrid()
    {
        String[] captions = new String[slotKeys.size()];
        for (int i = 0; i < slotKeys.size(); i++)
            captions[i] = tr("gui.dmz_ragnarok.cosmetics.slot." + slotKeys.get(i));
        int top = slotKeys.isEmpty() ? GRID_TOP : tabs(GRID_X, GRID_TOP, GRID_W, captions, tab, i ->
        {
            tab = i;
            scroll = 0;
            // Move the selection with the tab, so the detail strip and the footer buttons always describe
            // something the grid in front of the player is actually showing.
            selected = defaultSelectionFor(slotKeys.get(i));
            remember();
            rebuildWidgets();
        });

        List<OwnedCopy> shown = forSlot(currentSlotKey());
        int pitch = GRID_TILE + GRID_GAP;
        int rows = Math.max(1, (GuiTheme.contentBottom(uiHeight) - top) / pitch);
        int totalRows = Math.max(1, (shown.size() + GRID_COLS - 1) / GRID_COLS);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, totalRows - rows)));

        int first = scroll * GRID_COLS;
        int last = Math.min(shown.size(), first + rows * GRID_COLS);
        for (int i = first; i < last; i++)
        {
            OwnedCopy c = shown.get(i);
            int cell = i - first;
            int x = GRID_X + (cell % GRID_COLS) * pitch;
            int y = top + (cell / GRID_COLS) * pitch;
            boolean worn = c.instanceId.equals(wornInstance(c.slotKey));
            CosmeticTile tile = new CosmeticTile(x, y, GRID_TILE, c.catalogId, c.name, c.quality,
                    () -> pick(c));
            tile.equipped(worn).selected(c.instanceId.equals(selected)).counter(c.counterLabel());
            addRenderableWidget(tile);
            tooltip(x, y, GRID_TILE, GRID_TILE, copyTip(c));
        }
        if (shown.isEmpty())
            labelCentered(tr("gui.dmz_ragnarok.core.wardrobe.none_in_slot"), GRID_X + GRID_W / 2, top + 20,
                    GuiTheme.COLOR_MUTED);
        scrollList(GRID_X, GRID_X + GRID_W, top, pitch, rows, totalRows, scroll, v ->
        {
            scroll = v;
            remember();
            rebuildWidgets();
        });
    }

    /**
     * Click to select, click again to wear.
     *
     * <p>A single click equipping would make browsing a grid of forty items forty equip packets; a select-only
     * grid would need the eye to travel to the footer for every try. Click-then-confirm on the same tile is the
     * shortest gesture that is still undoable by looking away.
     */
    private void pick(OwnedCopy c)
    {
        if (c.instanceId.equals(selected))
        {
            equip(c);
            return;
        }
        selected = c.instanceId;
        remember();
        rebuildWidgets();
    }

    private void equip(OwnedCopy c)
    {
        if (c == null || c.instanceId.equals(wornInstance(c.slotKey)))
            return;
        EditorScreens.act("wardrobe", "equip", c.slotKey, c.catalogId, c.instanceId);
    }

    private void openPicker(String slotKey)
    {
        // Remember the slot the picker is for, so the equip it sends brings the wardrobe back to THIS slot rather
        // than to the current tab, which may be a different one.
        stickySlotKey = slotKey;
        Minecraft.getInstance().setScreen(
                new CosmeticPickerScreen(this, slotKey, forSlot(slotKey), wornInstance(slotKey)));
    }

    // ---------------------------------------------------------------- the portrait

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        super.render(g, mouseX, mouseY, partialTick);
        if (!LivePlayerPreview.available())
            return;
        // renderEntityInInventory wants REAL screen coordinates, so this runs after super.render has popped the
        // scaled pose and converts the virtual box itself. Same idiom as FormCosmeticsScreen.
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

        int clipX0 = (int) Math.round(originX() + (PREVIEW_X + 2) * guiScale);
        int clipY0 = (int) Math.round(originY() + (PREVIEW_Y + 2) * guiScale);
        int clipX1 = (int) Math.round(originX() + (PREVIEW_X + PREVIEW_W - 2) * guiScale);
        int clipY1 = (int) Math.round(originY() + (PREVIEW_Y + PREVIEW_H - 2) * guiScale);
        g.enableScissor(clipX0, clipY0, clipX1, clipY1);
        LivePlayerPreview.render(g, cxS, feetS, scaleS, yaw, pitch);
        g.disableScissor();
    }

    /**
     * Turn the portrait, unless the player is turning it themselves.
     *
     * <p>Driven from the wall clock rather than from a tick count, so the turn is the same speed at any frame
     * rate and keeps going while a singleplayer world is paused behind the screen. Minecraft exposes no
     * reduced-motion preference in 1.20.1, so there is nothing to honour here; a drag stops it, and the angle a
     * drag leaves it at is where the turn resumes from.
     */
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
        // Widgets first, so a control that happens to sit over the portrait still gets its click.
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
            // Raw screen-pixel deltas here (ScaledScreen divides only on the way to super), and ~0.9 degrees per
            // pixel, which is what the form editor's drag feels like.
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

    /** Keep the grid's scrollbar in its own column rather than over the loadout side. */
    @Override
    protected int bandRight()
    {
        return uiWidth - GuiTheme.SCROLLBAR_PANEL_INSET;
    }

    // ---------------------------------------------------------------- text

    private String slotTip(String key, OwnedCopy worn)
    {
        String name = tr("gui.dmz_ragnarok.cosmetics.slot." + key);
        if (worn == null)
            return name + "\n" + tr("gui.dmz_ragnarok.core.wardrobe.pick_hint");
        return name + "\n" + worn.name + "\n" + tr("gui.dmz_ragnarok.core.wardrobe.pick_hint");
    }

    private String copyTip(OwnedCopy c)
    {
        StringBuilder out = new StringBuilder(c.name);
        if (c.quality != CosmeticQuality.NORMAL)
            out.append('\n').append(tr(c.quality.langKey()));
        String line = detailLine(c);
        if (!line.isBlank())
            out.append('\n').append(line);
        CosmeticDef def = CosmeticClientStore.def(c.catalogId);
        if (def != null && def.description != null && !def.description.isBlank())
            out.append('\n').append(def.description);
        return out.toString();
    }

    /** The one line that says what makes this copy interesting: its effect, or its count, or its rarity. */
    private String detailLine(OwnedCopy c)
    {
        if (c.quality.usesEffect() && !c.effectId.isBlank())
            return tr("gui.dmz_ragnarok.core.wardrobe.effect", c.effectLabel());
        if (c.quality.usesCounters() && c.trackerCount > 0)
            return tr("gui.dmz_ragnarok.core.wardrobe.count", trackerLabel(c), c.count);
        return c.rarity;
    }

    /** The chosen tracker's label, or "no counter" when none is chosen. Read from the client catalogue cache. */
    private String trackerLabel(OwnedCopy c)
    {
        if (c == null || c.chosenTracker.isBlank())
            return tr("gui.dmz_ragnarok.core.wardrobe.tracker_off");
        CosmeticDef def = CosmeticClientStore.def(c.catalogId);
        CosmeticTracker t = def == null ? null : def.tracker(c.chosenTracker);
        return t == null || t.label == null || t.label.isBlank() ? c.chosenTracker : t.label;
    }

    /**
     * The next tracker in the cycle, with a blank (off) step between the last and the first.
     *
     * <p>Falls back to blank when this client does not have the definition: sending a tracker id the server
     * would refuse is worse than turning the display off, which it will always accept.
     */
    private String nextTracker(String cosmeticId, String current)
    {
        CosmeticDef def = CosmeticClientStore.def(cosmeticId);
        if (def == null || def.trackers.isEmpty())
            return "";
        List<String> cycle = new ArrayList<>();
        cycle.add("");
        for (CosmeticTracker t : def.trackers)
            cycle.add(t.id);
        int at = cycle.indexOf(current == null ? "" : current);
        return cycle.get((Math.max(at, 0) + 1) % cycle.size());
    }
}
