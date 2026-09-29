package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.shurui.shuruisutilities.client.gui.DmzDropdown;
import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * The cosmetic catalogue: every cosmetic an admin has defined.
 *
 * <p>Rows are {@code [id, slotKey, qualityKeysCsv, tradeable, enabled, trackerCount, displayName, event]} and
 * arrive sorted by slot then id, so the slots read as grouped runs. That grouping is drawn out here with a header
 * per slot, and two dropdowns at the top filter by Type (slot) and Event. The event on each row is shown on the
 * right so an admin can read a cosmetic's collection without opening it.
 *
 * <p>{@code meta} carries the server's own slot and quality key lists, so the New control offers exactly the slots
 * the SERVER considers live. The client never reads the enums directly for this, which is what keeps a hidden
 * slot hidden on a client whose jar is a version ahead.
 *
 * <p>The row right-hand text says BOUND or TRADEABLE in plain words rather than a tick. Whether a cosmetic can
 * leave its owner is the single most consequential field on the record, so it is readable from the list without
 * opening anything.
 */
public class CosmeticListScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int FILTER_Y = 20;
    private static final int LIST_TOP = 40;
    private static final int ROW_H = 16;
    private static final int HEADER_H = 12;
    private static final int FOOTER_INSET = 14;

    /** The event-filter value that means "only cosmetics with no event". A char no event name can hold. */
    private static final String NO_EVENT = "\u0000none";

    private final List<String> slots = new ArrayList<>();
    private final List<List<String>> rows;
    private int scroll = 0;
    private EditBox newBox;
    private int newSlot = 0;

    /** "" is All; otherwise a slot key. */
    private String typeFilter = "";
    /** "" is All; {@link #NO_EVENT} is "No event"; otherwise an event name. */
    private String eventFilter = "";
    private DmzDropdown typeDropdown;
    private DmzDropdown eventDropdown;
    private final List<String> typeValues = new ArrayList<>();
    private final List<String> eventValues = new ArrayList<>();

    public CosmeticListScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.cosmetics_admin"), UI_W, UI_H, null);
        this.rows = rows;
        // meta = [slotCount, slot keys..., qualityCount, quality keys...]. Read defensively: an older server
        // shorter meta leaves the fallback below rather than throwing on a screen open.
        int i = 0;
        int slotCount = meta.size() > i ? parse(meta.get(i++), 0) : 0;
        for (int n = 0; n < slotCount && i < meta.size(); n++)
            slots.add(meta.get(i++));
        if (slots.isEmpty())
            slots.add("head");
    }

    /** The event on a row, or "" when the server (or an older one) did not send it. Column 7. */
    private static String rowEvent(List<String> row)
    {
        return at(row, 7, "");
    }

    /** Whether a row passes both filters. */
    private boolean passes(List<String> row)
    {
        if (!typeFilter.isBlank() && !typeFilter.equals(at(row, 1, "head")))
            return false;
        if (eventFilter.isBlank())
            return true;
        String ev = rowEvent(row);
        if (NO_EVENT.equals(eventFilter))
            return ev.isBlank();
        return eventFilter.equals(ev);
    }

    /** The distinct events present across all rows, in first-seen order, for the event filter dropdown. */
    private List<String> presentEvents()
    {
        Map<String, String> seen = new LinkedHashMap<>();
        for (List<String> row : rows)
        {
            String ev = rowEvent(row);
            if (!ev.isBlank())
                seen.putIfAbsent(ev, ev);
        }
        return new ArrayList<>(seen.values());
    }

    /** The rows that pass the filters, in the order the server sent them (already sorted by slot then id). */
    private List<List<String>> filteredRows()
    {
        List<List<String>> out = new ArrayList<>();
        for (List<String> row : rows)
            if (passes(row))
                out.add(row);
        return out;
    }

    private static int parse(String s, int fallback)
    {
        try
        {
            return Integer.parseInt(s.trim());
        }
        catch (Exception e)
        {
            return fallback;
        }
    }

    @Override
    protected void init()
    {
        super.init();
        List<List<String>> shown = filteredRows();
        headerSubtitle = tr("gui.dmz_ragnarok.core.cosmetics.subtitle", shown.size());

        buildFilters();

        // The display list interleaves group HEADERS (a slot key) with cosmetic rows (the row itself), so grouping
        // and paging share one index space and headers scroll with their group. Rows arrive already sorted by slot
        // then id, so a header is emitted whenever the slot changes.
        List<Object> items = new ArrayList<>();
        String lastSlot = null;
        for (List<String> row : shown)
        {
            String slot = at(row, 1, "head");
            if (!slot.equals(lastSlot))
            {
                items.add(slot); // a header
                lastSlot = slot;
            }
            items.add(row);
        }

        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, items.size() - maxRows)));
        int end = Math.min(items.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            Object item = items.get(i);
            if (item instanceof String slotKey)
            {
                // A group header: the slot's friendly name, drawn as a muted heading above its run.
                label(tr("gui.dmz_ragnarok.cosmetics.slot." + slotKey), 14, ry + (ROW_H - HEADER_H) + 2,
                        0xFF9AA0B0);
                continue;
            }
            @SuppressWarnings("unchecked")
            List<String> row = (List<String>) item;
            final String id = row.get(0);
            String qualities = at(row, 2, "normal");
            boolean tradeable = Boolean.parseBoolean(at(row, 3, "false"));
            boolean enabled = Boolean.parseBoolean(at(row, 4, "true"));
            String name = at(row, 6, id);
            String event = rowEvent(row);
            int delW = 52;
            int delX = rowControlRight() - delW;
            // The event is shown on the row so an admin can read it without opening the entry. Blank events add
            // nothing, keeping a no-event row uncluttered.
            String right = (event.isBlank() ? "" : event + "  ") + qualityLabel(qualities) + "  "
                    + tr(tradeable ? "gui.dmz_ragnarok.core.cosmetics.tradeable"
                            : "gui.dmz_ragnarok.core.cosmetics.bound");
            rowBtn(24, ry, delX - 4 - 24, GuiTheme.ROW_HEIGHT, Component.literal(name),
                    () -> EditorScreens.act("cosmetics_admin", "open", id))
                    // A parked entry is dimmed rather than hidden: it is still owned by everybody who has it, and
                    // an admin looking for "why can nobody wear this" needs to see it in the list.
                    .color(enabled ? 0xFFF6E27A : 0xFF7A7A5A)
                    .right(Component.literal(right), 0xFFB0B0B0);
            btn(delX, ry, delW, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.delete"),
                    () -> EditorScreens.act("cosmetics_admin", "delete", id));
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, items.size(), scroll, v ->
        {
            scroll = v;
            rebuildWidgets();
        });

        // Footer laid out RIGHT TO LEFT from the panel's inner edge, each control placed against the one after
        // it, rather than from hand-picked absolute x values. The absolute version is what let two footer
        // buttons draw on top of each other in the task list; deriving each position from its neighbour makes
        // that impossible to reintroduce by changing one width.
        final int gap = 4;
        int menuW = 48;
        int newW = 44;
        int slotW = 64;
        int menuX = UI_W - FOOTER_INSET - menuW;
        int newX = menuX - gap - newW;
        int slotX = newX - gap - slotW;
        int boxRight = slotX - gap;

        newBox = field(14, footerY() + 2, boxRight - 14, "");
        newBox.setHint(Component.translatable("gui.dmz_ragnarok.core.cosmetics.new_hint"));
        newBox.setMaxLength(48);
        // A cycle button rather than a dropdown: there is a handful of live slots, and it saves a whole widget's
        // worth of room in the footer. The list is whatever the server sent, so a slot added to the enum turns
        // up here with no edit.
        btn(slotX, footerY(), slotW, footerBtnHeight(),
                Component.literal(tr("gui.dmz_ragnarok.cosmetics.slot." + slots.get(newSlot))), () ->
                {
                    newSlot = (newSlot + 1) % slots.size();
                    rebuildWidgets();
                });
        btn(newX, footerY(), newW, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.new"),
                () ->
                {
                    String v = newBox.getValue().trim();
                    if (!v.isBlank())
                        EditorScreens.act("cosmetics_admin", "new", v, slots.get(newSlot));
                });
        btn(menuX, footerY(), menuW, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    /** The Type and Event filter dropdowns across the top of the list. Rebuilt every init so options stay live. */
    private void buildFilters()
    {
        typeValues.clear();
        eventValues.clear();

        List<Component> typeOpts = new ArrayList<>();
        typeValues.add("");
        typeOpts.add(Component.literal(tr("gui.dmz_ragnarok.core.cosmetics.filter_all")));
        for (String slot : slots)
        {
            typeValues.add(slot);
            typeOpts.add(Component.literal(tr("gui.dmz_ragnarok.cosmetics.slot." + slot)));
        }
        int typeIdx = Math.max(0, typeValues.indexOf(typeFilter));

        List<Component> eventOpts = new ArrayList<>();
        eventValues.add("");
        eventOpts.add(Component.literal(tr("gui.dmz_ragnarok.core.cosmetics.filter_all")));
        for (String ev : presentEvents())
        {
            eventValues.add(ev);
            eventOpts.add(Component.literal(ev));
        }
        eventValues.add(NO_EVENT);
        eventOpts.add(Component.literal(tr("gui.dmz_ragnarok.core.cosmetics.filter_no_event")));
        int eventIdx = Math.max(0, eventValues.indexOf(eventFilter));

        int leftX = 14;
        int rightEdge = rowControlRight();
        int gap = 8;
        int colW = Math.max(40, (rightEdge - leftX - gap) / 2);
        int labelW = 34;
        label(tr("gui.dmz_ragnarok.core.cosmetics.filter_type"), leftX, FILTER_Y + 2, 0xFFB0B0B0);
        typeDropdown = dropdown(leftX + labelW, FILTER_Y, colW - labelW, typeOpts, typeIdx);
        int ex = leftX + colW + gap;
        label(tr("gui.dmz_ragnarok.core.cosmetics.filter_event"), ex, FILTER_Y + 2, 0xFFB0B0B0);
        eventDropdown = dropdown(ex + labelW, FILTER_Y, colW - labelW, eventOpts, eventIdx);
    }

    @Override
    protected void onDropdownSelect(DmzDropdown dropdown, int row)
    {
        if (dropdown == typeDropdown)
        {
            typeFilter = row >= 0 && row < typeValues.size() ? typeValues.get(row) : "";
            scroll = 0;
            rebuildWidgets();
        }
        else if (dropdown == eventDropdown)
        {
            eventFilter = row >= 0 && row < eventValues.size() ? eventValues.get(row) : "";
            scroll = 0;
            rebuildWidgets();
        }
    }

    /**
     * The eligibility set as words, for example "Normal, Magic".
     *
     * <p>Spelled out rather than abbreviated because this is the row an admin scans to answer "what can this
     * roll as", and a set is not a single badge. An unknown key is shown raw rather than dropped: a row that
     * quietly listed fewer qualities than the record holds would be worse than an ugly one.
     */
    private String qualityLabel(String csv)
    {
        List<String> parts = new ArrayList<>();
        for (String piece : csv.split(","))
        {
            String key = piece.trim();
            if (key.isEmpty())
                continue;
            String label = tr("gui.dmz_ragnarok.cosmetics.quality." + key);
            parts.add(label);
        }
        return parts.isEmpty() ? csv : String.join(", ", parts);
    }

    private static String at(List<String> row, int index, String fallback)
    {
        return row.size() > index ? row.get(index) : fallback;
    }
}
