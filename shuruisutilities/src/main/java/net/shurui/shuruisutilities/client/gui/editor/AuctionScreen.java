package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.auction.network.PacketOpenAuction;
import net.shurui.shuruisutilities.auction.network.PacketOpenAuction.ClaimView;
import net.shurui.shuruisutilities.auction.network.PacketOpenAuction.ListingView;
import net.shurui.shuruisutilities.client.gui.DmzDropdown;
import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.ItemDisplay;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * The auction house GUI, built on SU's {@link SagaBaseScreen} toolkit. Three tabs: Browse (every open listing, with
 * Bid and Buy Now), My Listings (the viewer's own listings, with Cancel and a "list held item" footer) and Claim
 * (pending items and refunds, with Collect all). It renders the authoritative view carried by
 * {@link PacketOpenAuction} and does nothing but echo string actions back through {@code PacketEditorAction}; the
 * server re-validates everything. Item icons are drawn with the shared {@link ItemDisplay} widget.
 */
public class AuctionScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 22;

    private static final String[] SORT_KEYS = { "ending_soon", "newest", "price_low", "price_high" };
    private static final String[] SORT_LABELS = { "Ending soon", "Newest", "Price: low", "Price: high" };

    // the tab + sort survive a server refresh (which pushes a brand-new screen), so a bid never bounces the viewer
    // back to Browse/default order.
    private static int sLastTab = 0;
    private static String sLastSort = "ending_soon";

    private final PacketOpenAuction data;
    private int tab;
    private String sortKey;
    private int scroll;

    private DmzDropdown sortDd;
    private EditBox bidBox;      // Browse: shared bid amount for the per-row Bid buttons
    private EditBox startBox;    // My Listings: starting bid for a new listing
    private EditBox buyNowBox;   // My Listings: optional buy-now price
    private String feedback;

    // item icons for the currently visible rows, drawn + hover-tested in renderTopOverlay (inside the scaled pose)
    private final List<ItemDisplay> itemCells = new ArrayList<>();

    public AuctionScreen(PacketOpenAuction data)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.menu.auction"), UI_W, UI_H, null);
        this.data = data;
        this.tab = Math.min(2, Math.max(0, sLastTab));
        this.sortKey = sLastSort;
    }

    public static void open(PacketOpenAuction data)
    {
        Minecraft.getInstance().setScreen(new AuctionScreen(data));
    }

    private int listTop()
    {
        return GuiTheme.CONTENT_TOP + GuiTheme.BUTTON_HEIGHT + 6;
    }

    // Shared across the three tabs (and the scroll clamp), so it lives here rather than in one init method. 34 clears
    // the tallest tab's footer form: My Listings draws a fields row at footerY - 34 with a list button below it;
    // Browse and Claim place their controls lower still, so this reserve keeps every tab's list off its widgets.
    private int maxRows()
    {
        return rowsThatFit(listTop(), ROW_H, 34);
    }

    @Override
    protected void init()
    {
        super.init();
        itemCells.clear();

        // tab bar
        String[] sections = {
                tr("gui.dmz_ragnarok.core.auction.tab.browse"),
                tr("gui.dmz_ragnarok.core.auction.tab.mine"),
                tr("gui.dmz_ragnarok.core.auction.tab.claim") };
        tabs(10, GuiTheme.CONTENT_TOP, UI_W - 20, sections, tab, i -> {
            tab = i;
            sLastTab = i;
            scroll = 0;
            feedback = null;
            rebuildWidgets();
        });

        switch (tab)
        {
            case 1 -> initMine();
            case 2 -> initClaim();
            default -> initBrowse();
        }

        if (feedback != null)
        {
            labelCentered("§c" + feedback, UI_W / 2, footerY() - 12, 0xFFFFFFFF);
        }

        // footer: Menu (back to the player hub) + Close, on every tab
        int menuW = 60;
        int closeW = 60;
        int closeX = UI_W - GuiTheme.CONTENT_PADDING - closeW;
        int menuX = closeX - GuiTheme.BUTTON_GAP_X - menuW;
        btn(menuX, footerY(), menuW, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openPlayerHub);
        btn(closeX, footerY(), closeW, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.close"),
                this::onClose);
    }

    private void initBrowse()
    {
        List<ListingView> rows = data.listings;
        clampScroll(rows.size());
        int top = listTop();
        int end = Math.min(rows.size(), scroll + maxRows());
        for (int i = scroll; i < end; i++)
        {
            ListingView v = rows.get(i);
            int ry = top + (i - scroll) * ROW_H;
            itemCells.add(new ItemDisplay(10, ry + 2, v.stack));

            long going = v.currentBid > 0 ? v.currentBid : v.startingBid;
            String bidLabel = v.currentBid > 0
                    ? tr("gui.dmz_ragnarok.core.auction.bid_now", fmt(going))
                    : tr("gui.dmz_ragnarok.core.auction.start_at", fmt(going));
            label("§e" + bidLabel, 32, ry + 2, 0xFFF6E27A);
            String sub = "§7" + v.sellerName + " · " + timeLeft(v.remainingMillis);
            if (v.buyNowPrice > 0)
            {
                sub += " · " + tr("gui.dmz_ragnarok.core.auction.buy_at", fmt(v.buyNowPrice));
            }
            label(sub, 32, ry + 12, 0xFF9A9A9A);

            int buyW = 46;
            int bidW = 40;
            int buyX = rowControlRight() - buyW;
            int bidX = buyX - GuiTheme.BUTTON_GAP_X - bidW;
            final String id = v.id.toString();
            if (v.own)
            {
                // your own listing: nothing to bid/buy here (manage it under My Listings).
                label("§8" + tr("gui.dmz_ragnarok.core.auction.yours"), bidX, ry + 6, 0xFF808080);
            }
            else
            {
                btn(bidX, ry + 2, bidW, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.auction.bid"),
                        () -> submitBid(id));
                if (v.buyNowPrice > 0)
                {
                    btn(buyX, ry + 2, buyW, GuiTheme.BUTTON_HEIGHT,
                            Component.translatable("gui.dmz_ragnarok.core.auction.buynow"),
                            () -> EditorScreens.act("auction", "buynow", id, sortKey));
                }
            }
        }
        scrollList(10, uiWidth, top, ROW_H, maxRows(), rows.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        // controls row: sort dropdown + shared bid amount field
        int ctrlY = footerY() - GuiTheme.FIELD_HEIGHT - 8;
        sortDd = dropdown(10, ctrlY, 96, options(SORT_LABELS), sortIndex());
        label(tr("gui.dmz_ragnarok.core.auction.bid_amount"), 116, ctrlY + 1, 0xFFCFE8B0);
        bidBox = field(178, ctrlY, 90, "");
        bidBox.setHint(Component.translatable("gui.dmz_ragnarok.core.auction.amount_hint"));
        bidBox.setMaxLength(19);
    }

    private void submitBid(String listingId)
    {
        String raw = bidBox == null ? "" : bidBox.getValue().trim();
        long amount;
        try
        {
            amount = Long.parseLong(raw);
        }
        catch (NumberFormatException e)
        {
            feedback = tr("gui.dmz_ragnarok.core.auction.err_amount");
            rebuildWidgets();
            return;
        }
        if (amount <= 0)
        {
            feedback = tr("gui.dmz_ragnarok.core.auction.err_amount");
            rebuildWidgets();
            return;
        }
        EditorScreens.act("auction", "bid", listingId, Long.toString(amount), sortKey);
    }

    private void initMine()
    {
        List<ListingView> mine = new ArrayList<>();
        for (ListingView v : data.listings)
        {
            if (v.own)
            {
                mine.add(v);
            }
        }
        clampScroll(mine.size());
        int top = listTop();
        int end = Math.min(mine.size(), scroll + maxRows());
        for (int i = scroll; i < end; i++)
        {
            ListingView v = mine.get(i);
            int ry = top + (i - scroll) * ROW_H;
            itemCells.add(new ItemDisplay(10, ry + 2, v.stack));

            long going = v.currentBid > 0 ? v.currentBid : v.startingBid;
            label("§e" + fmt(going), 32, ry + 2, 0xFFF6E27A);
            String sub = v.currentBid > 0
                    ? "§7" + tr("gui.dmz_ragnarok.core.auction.top_bidder", v.bidderName) + " · " + timeLeft(v.remainingMillis)
                    : "§7" + tr("gui.dmz_ragnarok.core.auction.no_bids") + " · " + timeLeft(v.remainingMillis);
            label(sub, 32, ry + 12, 0xFF9A9A9A);

            int cancelW = 52;
            int cancelX = rowControlRight() - cancelW;
            final String id = v.id.toString();
            if (v.currentBid > 0)
            {
                // a live bid cannot be cancelled (the bidder is owed a settle); the auction will resolve on expiry.
                label("§8" + tr("gui.dmz_ragnarok.core.auction.locked"), cancelX - 4, ry + 6, 0xFF808080);
            }
            else
            {
                btn(cancelX, ry + 2, cancelW, GuiTheme.BUTTON_HEIGHT,
                        Component.translatable("gui.dmz_ragnarok.core.auction.cancel"),
                        () -> EditorScreens.act("auction", "cancel", id, sortKey));
            }
        }
        scrollList(10, uiWidth, top, ROW_H, maxRows(), mine.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        // footer form: start bid + buy-now on one row, the "list held item" submit on its own row below. Packing
        // all three onto a single row ran the button into the buy-now field (and left no room for the longer
        // localised button label), so the button gets a full row of its own here.
        int fieldY = footerY() - GuiTheme.BUTTON_HEIGHT - GuiTheme.FIELD_HEIGHT - 9;
        label(tr("gui.dmz_ragnarok.core.auction.start"), 10, fieldY + 1, 0xFFCFE8B0);
        startBox = field(56, fieldY, 60, "");
        startBox.setMaxLength(19);
        label(tr("gui.dmz_ragnarok.core.auction.buynow_opt"), 122, fieldY + 1, 0xFFCFE8B0);
        buyNowBox = field(176, fieldY, 60, "");
        buyNowBox.setMaxLength(19);

        int listW = 120;
        int listX = rowControlRight() - listW;
        int listY = footerY() - GuiTheme.BUTTON_HEIGHT - 4;
        var listBtn = commitBtn(listX, listY, listW, GuiTheme.BUTTON_HEIGHT,
                Component.translatable("gui.dmz_ragnarok.core.auction.list_held"), this::submitList);
        listBtn.active = data.canList;
    }

    private void submitList()
    {
        if (!data.canList)
        {
            feedback = tr("gui.dmz_ragnarok.core.auction.err_too_many");
            rebuildWidgets();
            return;
        }
        long start;
        try
        {
            start = Long.parseLong(startBox.getValue().trim());
        }
        catch (NumberFormatException e)
        {
            feedback = tr("gui.dmz_ragnarok.core.auction.err_start");
            rebuildWidgets();
            return;
        }
        if (start < 1)
        {
            feedback = tr("gui.dmz_ragnarok.core.auction.err_start");
            rebuildWidgets();
            return;
        }
        long buyNow = 0;
        String bn = buyNowBox.getValue().trim();
        if (!bn.isEmpty())
        {
            try
            {
                buyNow = Long.parseLong(bn);
            }
            catch (NumberFormatException e)
            {
                feedback = tr("gui.dmz_ragnarok.core.auction.err_buynow");
                rebuildWidgets();
                return;
            }
            if (buyNow > 0 && buyNow < start)
            {
                feedback = tr("gui.dmz_ragnarok.core.auction.err_buynow_low");
                rebuildWidgets();
                return;
            }
        }
        EditorScreens.act("auction", "create", Long.toString(start), Long.toString(buyNow), sortKey);
    }

    private void initClaim()
    {
        List<ClaimView> rows = data.claims;
        clampScroll(rows.size());
        int top = listTop();
        int end = Math.min(rows.size(), scroll + maxRows());
        for (int i = scroll; i < end; i++)
        {
            ClaimView c = rows.get(i);
            int ry = top + (i - scroll) * ROW_H;
            if (c.isItem)
            {
                itemCells.add(new ItemDisplay(10, ry + 2, c.stack));
                label("§f" + reason(c.reasonKey), 32, ry + 6, 0xFFFFFFFF);
            }
            else
            {
                label("§e" + fmt(c.zeni) + " " + data.currency, 32, ry + 2, 0xFFF6E27A);
                label("§7" + reason(c.reasonKey), 32, ry + 12, 0xFF9A9A9A);
            }
        }
        scrollList(10, uiWidth, top, ROW_H, maxRows(), rows.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        if (rows.isEmpty())
        {
            labelCentered("§7" + tr("gui.dmz_ragnarok.core.auction.claim_empty"), UI_W / 2, top + 20, 0xFF9A9A9A);
        }

        int ctrlY = footerY() - GuiTheme.FIELD_HEIGHT - 8;
        int collectW = 96;
        var collectBtn = commitBtn(10, ctrlY - 1, collectW, GuiTheme.BUTTON_HEIGHT,
                Component.translatable("gui.dmz_ragnarok.core.auction.collect"),
                () -> EditorScreens.act("auction", "claim", sortKey));
        collectBtn.active = !rows.isEmpty();
    }

    @Override
    protected void onDropdownSelect(DmzDropdown dropdown, int row)
    {
        if (dropdown == sortDd && row >= 0 && row < SORT_KEYS.length)
        {
            sortKey = SORT_KEYS[row];
            sLastSort = sortKey;
            EditorScreens.act("auction", "refresh", sortKey);
        }
    }

    @Override
    protected void renderTopOverlay(GuiGraphics g, int vmx, int vmy)
    {
        for (ItemDisplay cell : itemCells)
        {
            cell.render(g, this.font);
        }
        if (openDropdown != null)
        {
            return; // an open list floats above; don't cover it with an item tooltip
        }
        for (ItemDisplay cell : itemCells)
        {
            if (cell.isHovered(vmx, vmy))
            {
                cell.renderTooltip(g, this.font, vmx, vmy);
                break;
            }
        }
    }

    private int sortIndex()
    {
        for (int i = 0; i < SORT_KEYS.length; i++)
        {
            if (SORT_KEYS[i].equals(sortKey))
            {
                return i;
            }
        }
        return 0;
    }

    private void clampScroll(int count)
    {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, count - maxRows())));
    }

    private static String fmt(long amount)
    {
        return String.format("%,d", amount);
    }

    private String reason(String key)
    {
        return key == null || key.isEmpty() ? "" : Component.translatable(key).getString();
    }

    // remainingMillis -> a short "2d 3h" / "5h 12m" / "8m" / "<1m" label
    private static String timeLeft(long millis)
    {
        if (millis <= 0)
        {
            return "expired";
        }
        long minutes = millis / 60000L;
        long days = minutes / 1440L;
        long hours = (minutes % 1440L) / 60L;
        long mins = minutes % 60L;
        if (days > 0)
        {
            return days + "d " + hours + "h";
        }
        if (hours > 0)
        {
            return hours + "h " + mins + "m";
        }
        if (mins > 0)
        {
            return mins + "m";
        }
        return "<1m";
    }
}
