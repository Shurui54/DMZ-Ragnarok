package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.ItemDisplay;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.trade.network.PacketOpenTrade;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The player-to-player trade GUI, built on SU's {@link SagaBaseScreen} toolkit (no vanilla container). Two read-only
 * columns show each side's offered stacks (drawn with the shared {@link ItemDisplay} widget); below them the viewer's
 * own inventory is shown as a click-to-offer grid. Clicking an inventory item stages it, clicking one of your own
 * offered items takes it back, and the Confirm/Cancel buttons toggle your approval or end the trade. Everything is
 * authoritative: the screen only renders the {@link PacketOpenTrade} the server pushes and echoes back string actions
 * (add slot index / remove index / confirm / cancel) through {@code PacketEditorAction}; the server re-validates each.
 */
public class TradeScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final int CELL = ItemDisplay.SIZE + 1; // one-pixel gutter between cells

    // both offers share the same 4-wide grid; the server caps a side's offer at 16, so a 4x4 block always fits.
    private static final int OFFER_COLS = 4;
    private static final int SELF_OFFER_X = 14;
    private static final int OTHER_OFFER_X = 160;

    // the viewer's inventory (36 main slots) shown as a 9x4 click-to-offer grid.
    private static final int INV_COLS = 9;
    private static final int INV_X = 10;

    private PacketOpenTrade data;

    // The in-progress typed stake, kept OUTSIDE the screen instance so it survives a server view push (which rebuilds
    // the screen): without it, the field would be wiped to the committed amount every time the other side changes their
    // offer while you are mid-typing. Reset when the screen opens fresh or closes.
    private static String carryZeni = null;

    private EditBox zeniField;

    // item cells for the current layout, drawn + hit-tested in the scaled pose
    private final List<ItemDisplay> selfCells = new ArrayList<>();
    private final List<ItemDisplay> otherCells = new ArrayList<>();
    private final List<ItemDisplay> invCells = new ArrayList<>();
    private final List<Integer> invSlots = new ArrayList<>(); // inventory slot index behind each invCell

    public TradeScreen(PacketOpenTrade data)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.menu.trade"), UI_W, UI_H, null);
        this.data = data;
    }

    // S2C entry point: open/refresh the screen, or close it when the trade has ended (active=false). When the second
    // confirmation lands the server sends active=false to BOTH sides, so this is what shuts the window for both the
    // instant the trade settles.
    public static void accept(PacketOpenTrade data)
    {
        Minecraft mc = Minecraft.getInstance();
        if (!data.active)
        {
            // server-driven close: setScreen(null) runs removed(), NOT onClose(), so no cancel echo is sent back.
            carryZeni = null;
            if (mc.screen instanceof TradeScreen)
            {
                mc.setScreen(null);
            }
            return;
        }
        // Refresh IN PLACE when the screen is already open, so the typed-but-not-yet-set zeni field is preserved across
        // a push (a fresh setScreen would wipe it). A first open builds the screen and clears any stale carry buffer.
        if (mc.screen instanceof TradeScreen ts)
        {
            ts.data = data;
            ts.rebuildWidgets();
        }
        else
        {
            carryZeni = null;
            mc.setScreen(new TradeScreen(data));
        }
    }

    private int offerTop()
    {
        return GuiTheme.CONTENT_TOP + 22;
    }

    @Override
    protected void init()
    {
        super.init();
        selfCells.clear();
        otherCells.clear();
        invCells.clear();
        invSlots.clear();
        headerSubtitle = tr("gui.dmz_ragnarok.core.trade.subtitle", data.otherName);

        int top = offerTop();

        // column headers + confirm status
        label(tr("gui.dmz_ragnarok.core.trade.your_offer"), SELF_OFFER_X, GuiTheme.CONTENT_TOP, GuiTheme.COLOR_TITLE);
        label(tr("gui.dmz_ragnarok.core.trade.their_offer", data.otherName), OTHER_OFFER_X, GuiTheme.CONTENT_TOP,
                GuiTheme.COLOR_TITLE);
        label(data.selfConfirmed ? "§a" + tr("gui.dmz_ragnarok.core.trade.you_confirmed")
                : "§e" + tr("gui.dmz_ragnarok.core.trade.you_not"), SELF_OFFER_X, GuiTheme.CONTENT_TOP + 10, 0xFFFFFFFF);
        label(data.otherConfirmed ? "§a" + tr("gui.dmz_ragnarok.core.trade.other_confirmed", data.otherName)
                : "§7" + tr("gui.dmz_ragnarok.core.trade.other_waiting", data.otherName),
                OTHER_OFFER_X, GuiTheme.CONTENT_TOP + 10, 0xFFFFFFFF);

        // offer grids (read-only cells; a self cell is click-to-remove)
        buildGrid(selfCells, data.selfOffer, SELF_OFFER_X, top);
        buildGrid(otherCells, data.otherOffer, OTHER_OFFER_X, top);

        // zeni stake row, under the two offer columns. Left: your amount as an editable field + a Set button that
        // commits it (server-side it re-arms both confirmations, exactly like changing an item). Right: the partner's
        // staked amount, read-only. Setting a stake is blocked server-side while you are confirmed.
        int zeniY = top + OFFER_COLS * CELL + 3;
        int fieldX = SELF_OFFER_X + 46;
        int fieldW = 52;
        int setX = fieldX + fieldW + GuiTheme.BUTTON_GAP_X;
        int setW = 30;
        label(tr("gui.dmz_ragnarok.core.trade.your_zeni"), SELF_OFFER_X, zeniY + 3, GuiTheme.COLOR_ROW);
        String seed = carryZeni != null ? carryZeni : Long.toString(data.selfZeni);
        zeniField = field(fieldX, zeniY + 1, fieldW, seed);
        zeniField.setMaxLength(12);
        zeniField.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        zeniField.setResponder(s -> carryZeni = s);
        commitBtn(setX, zeniY, setW, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.trade.set_zeni"), this::sendZeni);
        label(tr("gui.dmz_ragnarok.core.trade.their_zeni", data.otherName, String.format("%,d", data.otherZeni)),
                OTHER_OFFER_X, zeniY + 3, GuiTheme.COLOR_TITLE);

        // inventory picker: the viewer's own 36 main slots (client-synced), click to stage the whole stack.
        int invLabelY = zeniY + 17;
        label(tr("gui.dmz_ragnarok.core.trade.inventory"), INV_X, invLabelY, GuiTheme.COLOR_ROW);
        int invTop = invLabelY + 11;
        Inventory inv = Minecraft.getInstance().player.getInventory();
        for (int slot = 0; slot < inv.items.size(); slot++)
        {
            int col = slot % INV_COLS;
            int row = slot / INV_COLS;
            int cx = INV_X + col * CELL;
            int cy = invTop + row * CELL;
            invCells.add(new ItemDisplay(cx, cy, inv.items.get(slot)));
            invSlots.add(slot);
        }

        // footer: Confirm (toggle) + Cancel
        int cancelW = 70;
        int confirmW = 90;
        int cancelX = UI_W - GuiTheme.CONTENT_PADDING - cancelW;
        int confirmX = cancelX - GuiTheme.BUTTON_GAP_X - confirmW;
        commitBtn(confirmX, footerY(), confirmW, footerBtnHeight(),
                Component.translatable(data.selfConfirmed ? "gui.dmz_ragnarok.core.trade.unconfirm"
                        : "gui.dmz_ragnarok.core.trade.confirm"),
                () -> EditorScreens.act("trade", "confirm"));
        btn(cancelX, footerY(), cancelW, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.trade.cancel"),
                () -> EditorScreens.act("trade", "cancel"));
    }

    // commit the typed stake to the server. The field is digit-filtered, so this only has to guard an empty value and
    // clamp; the server re-validates the amount and the balance when the player confirms.
    private void sendZeni()
    {
        String v = zeniField == null ? "" : zeniField.getValue().trim();
        long amt;
        try
        {
            amt = v.isEmpty() ? 0L : Long.parseLong(v);
        }
        catch (NumberFormatException e)
        {
            amt = 0L;
        }
        if (amt < 0)
        {
            amt = 0L;
        }
        EditorScreens.act("trade", "setzeni", Long.toString(amt));
    }

    private void buildGrid(List<ItemDisplay> out, List<ItemStack> stacks, int baseX, int baseY)
    {
        for (int i = 0; i < stacks.size(); i++)
        {
            int col = i % OFFER_COLS;
            int row = i / OFFER_COLS;
            out.add(new ItemDisplay(baseX + col * CELL, baseY + row * CELL, stacks.get(i)));
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button)
    {
        if (button == 0)
        {
            double vx = toVirtualX(mx);
            double vy = toVirtualY(my);
            // click a staged item of your own to take it back (blocked while you are locked by your own confirm)
            if (!data.selfConfirmed)
            {
                for (int i = 0; i < selfCells.size(); i++)
                {
                    if (selfCells.get(i).isHovered(vx, vy))
                    {
                        EditorScreens.act("trade", "remove", Integer.toString(i));
                        return true;
                    }
                }
                for (int i = 0; i < invCells.size(); i++)
                {
                    if (!invCells.get(i).stack().isEmpty() && invCells.get(i).isHovered(vx, vy))
                    {
                        EditorScreens.act("trade", "add", Integer.toString(invSlots.get(i)));
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    protected void renderTopOverlay(GuiGraphics g, int vmx, int vmy)
    {
        for (ItemDisplay cell : selfCells)
        {
            cell.render(g, this.font);
        }
        for (ItemDisplay cell : otherCells)
        {
            cell.render(g, this.font);
        }
        for (ItemDisplay cell : invCells)
        {
            cell.render(g, this.font);
        }
        // hover tooltip: whichever cell the mouse is over (offers first, then inventory)
        for (ItemDisplay cell : allCells())
        {
            if (cell.isHovered(vmx, vmy))
            {
                cell.renderTooltip(g, this.font, vmx, vmy);
                return;
            }
        }
    }

    private List<ItemDisplay> allCells()
    {
        List<ItemDisplay> all = new ArrayList<>(selfCells.size() + otherCells.size() + invCells.size());
        all.addAll(selfCells);
        all.addAll(otherCells);
        all.addAll(invCells);
        return all;
    }

    // ESC (or the window otherwise closing itself) cancels the trade, mirroring the old "close window = cancel"
    // behaviour. A server-driven end uses setScreen(null), which runs removed() not onClose(), so this only fires on a
    // real user close and the server's finished-guard makes a late echo harmless.
    @Override
    public void onClose()
    {
        EditorScreens.act("trade", "cancel");
        super.onClose();
    }
}
