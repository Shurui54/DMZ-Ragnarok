package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.shurui.shuruisutilities.client.gui.DmzDropdown;
import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.cosmetics.CosmeticTile;
import net.shurui.shuruisutilities.client.gui.cosmetics.OwnedCopy;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticDef;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticQuality;
import net.shurui.shuruisutilities.cosmetics.wardrobe.client.CosmeticClientStore;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Pick what goes in ONE slot, shown as a wall of tiles.
 *
 * <p>Opened by clicking a slot box on {@link WardrobeScreen}. It is built entirely from the rows that screen
 * already holds, so opening it costs no packet and no round trip; only the pick itself talks to the server, and
 * the server's refresh is what puts the wardrobe back on screen afterwards.
 *
 * <h2>Owned only, and one tile per copy</h2>
 * The picker lists what the player OWNS and nothing else. A wardrobe showing things you cannot wear is a shop,
 * and there is a real shop coming with prices, windows and stock; advertising unowned entries here would also
 * put unreleased catalogue names in front of everybody, because the client already holds the whole catalogue for
 * rendering. So: owned only.
 *
 * <p>Each COPY is its own tile, because a Super copy with 412 kills on it and a plain copy of the same hat are
 * not the same item to the person who owns them. {@code CosmeticLedgerData.bestInstance} still decides the
 * default when nobody says which copy, and this screen is the place a player says otherwise.
 *
 * <p>The first tile is always "wear nothing", so taking a slot off is part of choosing rather than a separate
 * control somewhere else.
 */
public class CosmeticPickerScreen extends SagaBaseScreen
{
    /** The wardrobe's canvas, so opening the picker does not resize the panel under the cursor. */
    private static final int UI_W = 580;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final int GRID_X = 8;
    private static final int GRID_TOP = 32;
    private static final int GRID_TILE = 42;
    private static final int GRID_GAP = 6;

    /** The event-filter value that means "only cosmetics with no event". A char no event name can hold. */
    private static final String NO_EVENT = "\u0000none";

    private final String slotKey;
    private final List<OwnedCopy> copies;
    private final String wornInstance;

    private int scroll;

    /** "" is All; {@link #NO_EVENT} is "No event"; otherwise an event name. */
    private String eventFilter = "";
    private DmzDropdown eventDropdown;
    private final List<String> eventValues = new ArrayList<>();

    public CosmeticPickerScreen(Screen parent, String slotKey, List<OwnedCopy> copies, String wornInstance)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.wardrobe.pick_title"), UI_W, UI_H, parent);
        this.slotKey = slotKey == null ? "" : slotKey;
        this.copies = copies;
        this.wornInstance = wornInstance == null ? "" : wornInstance;
    }

    /** The event on a copy, resolved through the client catalogue, or "" when unknown. */
    private static String eventOf(OwnedCopy c)
    {
        CosmeticDef def = c == null ? null : CosmeticClientStore.def(c.catalogId);
        return def == null || def.event == null ? "" : def.event.trim();
    }

    /** The copies passing the event filter. Type is implied by the slot, so only event is filtered here. */
    private List<OwnedCopy> filteredCopies()
    {
        if (eventFilter.isBlank())
            return copies;
        List<OwnedCopy> out = new ArrayList<>();
        for (OwnedCopy c : copies)
        {
            String ev = eventOf(c);
            if (NO_EVENT.equals(eventFilter) ? ev.isBlank() : eventFilter.equals(ev))
                out.add(c);
        }
        return out;
    }

    /** The distinct events across the owned copies in this slot, first-seen order, for the filter dropdown. */
    private List<String> presentEvents()
    {
        Map<String, String> seen = new LinkedHashMap<>();
        for (OwnedCopy c : copies)
        {
            String ev = eventOf(c);
            if (!ev.isBlank())
                seen.putIfAbsent(ev, ev);
        }
        return new ArrayList<>(seen.values());
    }

    @Override
    protected void init()
    {
        super.init();
        headerName = tr("gui.dmz_ragnarok.cosmetics.slot." + slotKey);

        buildEventFilter();
        List<OwnedCopy> shown = filteredCopies();

        int gridW = rowControlRight() - GRID_X;
        int pitch = GRID_TILE + GRID_GAP;
        int cols = Math.max(1, (gridW + GRID_GAP) / pitch);
        int rows = Math.max(1, (GuiTheme.contentBottom(uiHeight) - GRID_TOP) / pitch);

        // The "wear nothing" tile is cell zero, so the page arithmetic treats it as an ordinary entry and it
        // scrolls off the top with everything else rather than floating.
        int total = shown.size() + 1;
        int totalRows = Math.max(1, (total + cols - 1) / cols);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, totalRows - rows)));

        int first = scroll * cols;
        int last = Math.min(total, first + rows * cols);
        for (int i = first; i < last; i++)
        {
            int cell = i - first;
            int x = GRID_X + (cell % cols) * pitch;
            int y = GRID_TOP + (cell / cols) * pitch;
            if (i == 0)
            {
                CosmeticTile empty = new CosmeticTile(x, y, GRID_TILE, "",
                        tr("gui.dmz_ragnarok.core.wardrobe.empty"), CosmeticQuality.NORMAL,
                        () -> EditorScreens.act("wardrobe", "unequip", slotKey));
                empty.empty(true).selected(wornInstance.isBlank());
                addRenderableWidget(empty);
                tooltip(x, y, GRID_TILE, GRID_TILE, tr("gui.dmz_ragnarok.core.wardrobe.wear_nothing"));
                continue;
            }
            OwnedCopy c = shown.get(i - 1);
            boolean worn = c.instanceId.equals(wornInstance);
            CosmeticTile tile = new CosmeticTile(x, y, GRID_TILE, c.catalogId, c.name, c.quality,
                    () -> EditorScreens.act("wardrobe", "equip", c.slotKey, c.catalogId, c.instanceId));
            tile.equipped(worn).selected(worn).counter(c.counterLabel());
            addRenderableWidget(tile);
            tooltip(x, y, GRID_TILE, GRID_TILE, tip(c));
        }
        if (shown.isEmpty())
            labelCentered(tr("gui.dmz_ragnarok.core.wardrobe.none_in_slot"), uiWidth / 2, GRID_TOP + GRID_TILE + 12,
                    GuiTheme.COLOR_MUTED);

        scrollList(GRID_X, GRID_X + gridW, GRID_TOP, pitch, rows, totalRows, scroll, v ->
        {
            scroll = v;
            rebuildWidgets();
        });

        btn(uiWidth / 2 - 45, footerY(), 90, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.btn.back"), this::back);
    }

    /** The Event filter, top-right, drawn only when the owned copies in this slot span more than one event. */
    private void buildEventFilter()
    {
        eventValues.clear();
        eventDropdown = null;
        List<String> events = presentEvents();
        if (events.isEmpty())
            return;
        List<Component> opts = new ArrayList<>();
        eventValues.add("");
        opts.add(Component.literal(tr("gui.dmz_ragnarok.core.cosmetics.filter_all")));
        for (String ev : events)
        {
            eventValues.add(ev);
            opts.add(Component.literal(ev));
        }
        eventValues.add(NO_EVENT);
        opts.add(Component.literal(tr("gui.dmz_ragnarok.core.cosmetics.filter_no_event")));
        int idx = Math.max(0, eventValues.indexOf(eventFilter));
        int w = 120;
        int x = rowControlRight() - w;
        int labelW = font.width(tr("gui.dmz_ragnarok.core.cosmetics.filter_event")) + 4;
        label(tr("gui.dmz_ragnarok.core.cosmetics.filter_event"), x - labelW, 18, 0xFFB0B0B0);
        eventDropdown = dropdown(x, 16, w, opts, idx);
    }

    @Override
    protected void onDropdownSelect(DmzDropdown dropdown, int row)
    {
        if (dropdown == eventDropdown)
        {
            eventFilter = row >= 0 && row < eventValues.size() ? eventValues.get(row) : "";
            scroll = 0;
            rebuildWidgets();
        }
    }

    /** What a tile says on hover: the name, what makes it special, and the admin's description if there is one. */
    private String tip(OwnedCopy c)
    {
        StringBuilder out = new StringBuilder(c.name);
        if (c.quality != CosmeticQuality.NORMAL)
            out.append('\n').append(tr(c.quality.langKey()));
        if (c.quality.usesEffect() && !c.effectId.isBlank())
            out.append('\n').append(tr("gui.dmz_ragnarok.core.wardrobe.effect", c.effectLabel()));
        CosmeticDef def = CosmeticClientStore.def(c.catalogId);
        if (c.quality.usesCounters() && c.trackerCount > 0)
        {
            net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticTracker t =
                    def == null || c.chosenTracker.isBlank() ? null : def.tracker(c.chosenTracker);
            String label = t == null || t.label == null || t.label.isBlank()
                    ? tr("gui.dmz_ragnarok.core.wardrobe.tracker_off") : t.label;
            out.append('\n').append(tr("gui.dmz_ragnarok.core.wardrobe.count", label, c.count));
        }
        if (def != null && def.description != null && !def.description.isBlank())
            out.append('\n').append(def.description);
        return out.toString();
    }
}
