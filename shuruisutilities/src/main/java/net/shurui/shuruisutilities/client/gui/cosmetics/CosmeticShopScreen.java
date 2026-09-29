package net.shurui.shuruisutilities.client.gui.cosmetics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.util.UUID;

import net.shurui.shuruisutilities.client.cosmetics.WardrobeCosmeticLayer;
import net.shurui.shuruisutilities.client.gui.DmzDropdown;
import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.preview.LivePlayerPreview;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.client.hud.ShardClientCache;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticDef;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticQuality;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticSlot;
import net.shurui.shuruisutilities.cosmetics.wardrobe.EquippedCosmetic;
import net.shurui.shuruisutilities.cosmetics.wardrobe.client.CosmeticClientStore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The cosmetic shop: your character, what is for sale, your Shards, and one click to buy.
 *
 * <h2>Same visual family as the wardrobe</h2>
 * The live portrait, the tile grid and the detail strip are the wardrobe's, so the two read as one feature. The
 * portrait is the player's real race, form, hair and colours (see {@link LivePlayerPreview}); the cosmetic is not
 * drawn ON it, because the render layer is a later milestone, and the shop is honest about that by showing the
 * item as a tile rather than pretending it is worn.
 *
 * <h2>Everything is the server's answer</h2>
 * Rows are {@code [listingId, catalogId, displayName, slotKey, price, category, canBuy, reason, description,
 * featured]}, computed server side. A buy is {@code EditorScreens.act("shop", "buy", listingId)}, re-validated and
 * charged in {@code CosmeticShop}, so a forged click buys a refusal. The balance shown is the client's own cached
 * figure, pushed by {@code ArgentSync}; every purchase re-pushes it.
 */
public class CosmeticShopScreen extends SagaBaseScreen
{
    private static final int UI_W = 580;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final int PREVIEW_X = 8;
    private static final int PREVIEW_Y = 30;
    private static final int PREVIEW_W = 150;
    private static final int PREVIEW_H = 172;

    private static final int DETAIL_X = PREVIEW_X;
    private static final int DETAIL_W = 292;
    private static final int DETAIL_Y = 206;
    private static final int DETAIL_H = 28;

    private static final int GRID_X = 308;
    private static final int GRID_TOP = 44;
    private static final int GRID_W = 250;
    private static final int GRID_TILE = 42;
    private static final int GRID_GAP = 5;
    private static final int GRID_COLS = (GRID_W + GRID_GAP) / (GRID_TILE + GRID_GAP);

    private static final float SPIN_PERIOD_MS = 12000.0F;

    /** The event-filter value that means "only cosmetics with no event". A char no event name can hold. */
    private static final String NO_EVENT = "\u0000none";

    /**
     * The tile "worn" in the preview carries a synthetic instance id. It is never sent to the server (the preview
     * is client-only and never equips), so any fixed non-null UUID does; the render path only needs a valid record.
     */
    private static final UUID PREVIEW_INSTANCE = new UUID(0L, 0L);

    /**
     * The listing the player last had selected, remembered ACROSS a refresh. A purchase re-opens the shop as a
     * fresh screen, and jumping back to the top of the list each time would be jarring, so the selection is
     * restored here instead. Static because the screen instance is replaced on every refresh.
     */
    private static String lastSelected = "";

    /**
     * True from the moment Buy is pressed until the refreshed shop screen is built. It disables the Buy button so a
     * second press cannot dispatch a second purchase while one is in flight. This is only the UI half; the server
     * is idempotent against a double click on its own.
     */
    private static boolean purchasing;

    private final List<ShopEntry> entries;
    private int scroll;
    private String selected = "";

    /** "" is All; otherwise a slot key. */
    private String typeFilter = "";
    /** "" is All; {@link #NO_EVENT} is "No event"; otherwise an event name. */
    private String eventFilter = "";
    private DmzDropdown typeDropdown;
    private DmzDropdown eventDropdown;
    private final List<String> typeValues = new ArrayList<>();
    private final List<String> eventValues = new ArrayList<>();

    private float yaw = (float) Math.PI;
    private float pitch;
    private long lastFrameMs;
    private boolean dragging;

    public CosmeticShopScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.shop.title"), UI_W, UI_H, null);
        this.entries = ShopEntry.parse(rows);
        // This screen is being (re)built, so any in-flight purchase has completed: clear the UI guard.
        purchasing = false;
        // Restore the previous selection across a refresh; fall back to the first entry when it is gone.
        if (byId(lastSelected) != null)
            selected = lastSelected;
        else if (!entries.isEmpty())
            selected = entries.get(0).listingId;
        lastSelected = selected;
    }

    private ShopEntry byId(String listingId)
    {
        if (listingId == null || listingId.isBlank())
            return null;
        for (ShopEntry e : entries)
            if (listingId.equals(e.listingId))
                return e;
        return null;
    }

    /** The event on a listing, resolved through the client catalogue by its cosmetic id, or "" when unknown. */
    private static String eventOf(ShopEntry e)
    {
        CosmeticDef def = e == null ? null : CosmeticClientStore.def(e.catalogId);
        return def == null || def.event == null ? "" : def.event.trim();
    }

    /** The listings passing both filters. */
    private List<ShopEntry> filtered()
    {
        List<ShopEntry> out = new ArrayList<>();
        for (ShopEntry e : entries)
        {
            if (!typeFilter.isBlank() && !typeFilter.equals(e.slotKey))
                continue;
            if (!eventFilter.isBlank())
            {
                String ev = eventOf(e);
                if (NO_EVENT.equals(eventFilter) ? !ev.isBlank() : !eventFilter.equals(ev))
                    continue;
            }
            out.add(e);
        }
        return out;
    }

    private List<String> presentTypes()
    {
        Map<String, String> seen = new LinkedHashMap<>();
        for (ShopEntry e : entries)
            if (e.slotKey != null && !e.slotKey.isBlank())
                seen.putIfAbsent(e.slotKey, e.slotKey);
        return new ArrayList<>(seen.values());
    }

    private List<String> presentEvents()
    {
        Map<String, String> seen = new LinkedHashMap<>();
        for (ShopEntry e : entries)
        {
            String ev = eventOf(e);
            if (!ev.isBlank())
                seen.putIfAbsent(ev, ev);
        }
        return new ArrayList<>(seen.values());
    }

    /** The Type and Event filter dropdowns, above the grid. Options are whatever the current listings span. */
    private void buildFilters()
    {
        typeValues.clear();
        eventValues.clear();

        List<Component> typeOpts = new ArrayList<>();
        typeValues.add("");
        typeOpts.add(Component.literal(tr("gui.dmz_ragnarok.core.cosmetics.filter_all")));
        for (String slot : presentTypes())
        {
            typeValues.add(slot);
            typeOpts.add(Component.literal(tr("gui.dmz_ragnarok.cosmetics.slot." + slot)));
        }
        int typeIdx = Math.max(0, typeValues.indexOf(typeFilter));

        List<Component> eventOpts = new ArrayList<>();
        eventValues.add("");
        eventOpts.add(Component.literal(tr("gui.dmz_ragnarok.core.cosmetics.filter_all")));
        List<String> events = presentEvents();
        for (String ev : events)
        {
            eventValues.add(ev);
            eventOpts.add(Component.literal(ev));
        }
        eventValues.add(NO_EVENT);
        eventOpts.add(Component.literal(tr("gui.dmz_ragnarok.core.cosmetics.filter_no_event")));
        int eventIdx = Math.max(0, eventValues.indexOf(eventFilter));

        int y = 30;
        int gap = 6;
        int half = (GRID_W - gap) / 2;
        int tLabW = font.width(tr("gui.dmz_ragnarok.core.cosmetics.filter_type")) + 3;
        label(tr("gui.dmz_ragnarok.core.cosmetics.filter_type"), GRID_X, y + 2, 0xFFB0B0B0);
        typeDropdown = dropdown(GRID_X + tLabW, y, half - tLabW, typeOpts, typeIdx);
        int ex = GRID_X + half + gap;
        int eLabW = font.width(tr("gui.dmz_ragnarok.core.cosmetics.filter_event")) + 3;
        label(tr("gui.dmz_ragnarok.core.cosmetics.filter_event"), ex, y + 2, 0xFFB0B0B0);
        eventDropdown = dropdown(ex + eLabW, y, half - eLabW, eventOpts, eventIdx);
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

    @Override
    protected void init()
    {
        super.init();
        Minecraft mc = Minecraft.getInstance();
        headerName = mc.player == null ? null : mc.player.getGameProfile().getName();
        headerSubtitle = tr("gui.dmz_ragnarok.core.shop.balance",
                ShardClientCache.known() ? format(ShardClientCache.get()) : "?");

        buildDetail();
        buildFilters();
        buildGrid();

        ShopEntry sel = byId(selected);
        int y = footerY();
        int h = footerBtnHeight();
        String reason = buyReason(sel);
        var buy = btn(PREVIEW_X, y, 132, h,
                Component.literal(sel == null ? tr("gui.dmz_ragnarok.core.shop.buy")
                        : tr("gui.dmz_ragnarok.core.shop.buy_price", format(sel.price))),
                () ->
                {
                    // Only the Buy button ever purchases, and only once: guard on the same reason the button's
                    // enabled state uses, then latch the in-flight flag so a second press does nothing until the
                    // refreshed screen clears it.
                    ShopEntry e = byId(selected);
                    if (e != null && !purchasing && buyReason(e) == null)
                    {
                        purchasing = true;
                        EditorScreens.act("shop", "buy", e.listingId);
                        rebuildWidgets();
                    }
                });
        buy.active = reason == null;
        // When the button is disabled, hover it to learn why (not enough Shards, at the limit, already owned, or a
        // purchase in progress). A dedicated line rather than a silent dead button.
        if (reason != null)
            tooltip(PREVIEW_X, y, 132, h, reason);

        btn(rowControlRight() - 64, y, 64, h, Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openPlayerHub);
    }

    /** Why the selected listing cannot be bought right now, or null when it can. Drives the Buy button. */
    private String buyReason(ShopEntry sel)
    {
        if (sel == null)
            return tr("gui.dmz_ragnarok.core.shop.pick_hint");
        if (purchasing)
            return tr("gui.dmz_ragnarok.core.shop.buy_in_progress");
        if (!sel.canBuy)
        {
            if ("owned".equals(sel.reason))
                return tr("gui.dmz_ragnarok.core.shop.set_owned");
            return tr("gui.dmz_ragnarok.core.shop.at_limit");
        }
        if (ShardClientCache.known() && ShardClientCache.get() < sel.price)
            return tr("gui.dmz_ragnarok.core.shop.cannot_afford");
        return null;
    }

    private void buildDetail()
    {
        rect(DETAIL_X, DETAIL_Y, DETAIL_W, DETAIL_H, 0xFF1A1A20);
        ShopEntry sel = byId(selected);
        if (sel == null)
        {
            labelCentered(entries.isEmpty() ? tr("gui.dmz_ragnarok.core.shop.empty")
                    : tr("gui.dmz_ragnarok.core.shop.pick_hint"), DETAIL_X + DETAIL_W / 2, DETAIL_Y + 10,
                    GuiTheme.COLOR_MUTED);
            return;
        }
        label(sel.name, DETAIL_X + 6, DETAIL_Y + 5, GuiTheme.COLOR_TITLE);
        String price = tr("gui.dmz_ragnarok.core.shop.price", format(sel.price));
        label(price, DETAIL_X + DETAIL_W - 6 - font.width(price), DETAIL_Y + 5, GuiTheme.COLOR_ROW);
        String line = !sel.description.isBlank() ? sel.description
                : tr("gui.dmz_ragnarok.cosmetics.slot." + sel.slotKey);
        if (!line.isBlank())
            label(line, DETAIL_X + 6, DETAIL_Y + 16, GuiTheme.COLOR_MUTED);
    }

    private void buildGrid()
    {
        List<ShopEntry> shown = filtered();
        int pitch = GRID_TILE + GRID_GAP;
        int rows = Math.max(1, (GuiTheme.contentBottom(uiHeight) - GRID_TOP) / pitch);
        int totalRows = Math.max(1, (shown.size() + GRID_COLS - 1) / GRID_COLS);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, totalRows - rows)));

        int first = scroll * GRID_COLS;
        int last = Math.min(shown.size(), first + rows * GRID_COLS);
        for (int i = first; i < last; i++)
        {
            ShopEntry e = shown.get(i);
            int cell = i - first;
            int x = GRID_X + (cell % GRID_COLS) * pitch;
            int gy = GRID_TOP + (cell / GRID_COLS) * pitch;
            // Shop items are always Normal, so the tile is not framed; the corner text is the price instead of a
            // count. Clicking selects, clicking the selected one buys, exactly like the wardrobe grid.
            CosmeticTile tile = new CosmeticTile(x, gy, GRID_TILE, e.catalogId, e.name, CosmeticQuality.NORMAL,
                    () -> pick(e));
            tile.selected(e.listingId.equals(selected)).counter(shortPrice(e.price));
            addRenderableWidget(tile);
            tooltip(x, gy, GRID_TILE, GRID_TILE, tip(e));
        }
        if (shown.isEmpty())
            labelCentered(tr("gui.dmz_ragnarok.core.shop.empty"), GRID_X + GRID_W / 2, GRID_TOP + 20,
                    GuiTheme.COLOR_MUTED);
        scrollList(GRID_X, GRID_X + GRID_W, GRID_TOP, pitch, rows, totalRows, scroll, v ->
        {
            scroll = v;
            rebuildWidgets();
        });
    }

    private void pick(ShopEntry e)
    {
        // Clicking a tile only SELECTS it (highlight, detail, preview). Buying is the dedicated Buy button's job,
        // so no click, including a second click on the same tile, ever purchases. This matches the two-step
        // select-then-act pattern the wardrobe and the other suite screens use.
        if (e.listingId.equals(selected))
            return;
        selected = e.listingId;
        lastSelected = selected;
        rebuildWidgets();
    }

    private String tip(ShopEntry e)
    {
        StringBuilder out = new StringBuilder(e.name);
        out.append('\n').append(tr("gui.dmz_ragnarok.core.shop.price", format(e.price)));
        if (!e.canBuy && "limit".equals(e.reason))
            out.append('\n').append(tr("gui.dmz_ragnarok.core.shop.at_limit"));
        if (!e.canBuy && "owned".equals(e.reason))
            out.append('\n').append(tr("gui.dmz_ragnarok.core.shop.set_owned"));
        if (!e.description.isBlank())
            out.append('\n').append(e.description);
        return out.toString();
    }

    // ---------------------------------------------------------------- the portrait (copied from the wardrobe)

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        super.render(g, mouseX, mouseY, partialTick);
        if (!LivePlayerPreview.available())
            return;
        g.flush();
        advanceSpin();

        rect(PREVIEW_X, PREVIEW_Y, PREVIEW_W, PREVIEW_H, 0xFF2A2A30);
        rect(PREVIEW_X + 1, PREVIEW_Y + 1, PREVIEW_W - 2, PREVIEW_H - 2, 0xFF121216);

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
        // Show the SELECTED cosmetic on the player for the duration of this one draw, over their own outfit,
        // without changing their real equipment. The override is set only around this call and cleared in the
        // finally, so the player's own body in the world (F5) is never affected. A key or set listing has no single
        // wearable to preview, so the override is left null and the portrait is the player's own outfit.
        WardrobeCosmeticLayer.previewOverride = previewOverrideFor(byId(selected));
        try
        {
            LivePlayerPreview.render(g, cxS, feetS, scaleS, yaw, pitch);
        }
        finally
        {
            WardrobeCosmeticLayer.previewOverride = null;
            g.disableScissor();
        }
    }

    /**
     * The preview override for a shop entry: a single wearable cosmetic drawn in its own slot, or null when there
     * is nothing to preview (an empty selection, a key or set listing, or a cosmetic whose slot is not one the
     * body layer draws, such as a pet or mount).
     */
    private java.util.function.Function<CosmeticSlot, EquippedCosmetic> previewOverrideFor(ShopEntry e)
    {
        if (e == null || e.catalogId.isBlank())
            return null;
        CosmeticDef def = CosmeticClientStore.def(e.catalogId);
        if (def == null || !def.wearable() || def.slot == null)
            return null;
        CosmeticSlot slot = def.slot;
        EquippedCosmetic preview = new EquippedCosmetic(def.id, PREVIEW_INSTANCE, CosmeticQuality.NORMAL, "");
        return s -> s == slot ? preview : null;
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

    private static String format(long v)
    {
        return String.format(Locale.ROOT, "%,d", v);
    }

    /** A compact price for the tile corner: 1200 becomes 1.2k so it fits. */
    private static String shortPrice(long v)
    {
        if (v < 1000L)
            return Long.toString(v);
        if (v < 1_000_000L)
            return String.format(Locale.ROOT, "%.1fk", v / 1000.0D);
        return String.format(Locale.ROOT, "%.1fm", v / 1_000_000.0D);
    }

    /** One offer as the shop packet describes it. Parsed once here rather than in the screen body. */
    private static final class ShopEntry
    {
        final String listingId;
        final String catalogId;
        final String name;
        final String slotKey;
        final long price;
        final boolean canBuy;
        final String reason;
        final String description;

        private ShopEntry(List<String> row)
        {
            this.listingId = at(row, 0);
            this.catalogId = at(row, 1);
            String display = at(row, 2);
            this.name = display.isBlank() ? catalogId : display;
            this.slotKey = at(row, 3);
            this.price = parse(at(row, 4));
            this.canBuy = Boolean.parseBoolean(at(row, 6));
            this.reason = at(row, 7);
            this.description = at(row, 8);
        }

        static List<ShopEntry> parse(List<List<String>> rows)
        {
            List<ShopEntry> out = new ArrayList<>();
            if (rows == null)
                return out;
            for (List<String> row : rows)
                if (row != null && !row.isEmpty() && !row.get(0).isBlank())
                    out.add(new ShopEntry(row));
            return out;
        }

        private static String at(List<String> row, int i)
        {
            String v = row.size() > i ? row.get(i) : "";
            return v == null ? "" : v;
        }

        private static long parse(String s)
        {
            try
            {
                return Long.parseLong(s.trim());
            }
            catch (RuntimeException e)
            {
                return 0L;
            }
        }
    }
}
