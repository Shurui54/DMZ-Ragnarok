package net.shurui.shuruisutilities.compat.customnpcs.client;

import net.shurui.shuruisutilities.compat.customnpcs.ZeniShop;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;

import noppes.npcs.packets.Packets;
import noppes.npcs.packets.server.SPacketNpcRoleSave;
import noppes.npcs.roles.RoleTrader;

// editor for a trader NPC's Zeni prices, opened from the "Zeni Prices" button injected into CNPC's trader setup
// GUI. 18 slots, each with a Buy + Sell field; Save writes the numbers onto the sold ItemStack's NBT (ZeniShop)
// and persists the role via CNPC's own SPacketNpcRoleSave, so no custom networking. Buy = Zeni to buy; Sell =
// Zeni paid to the player (sold item is a marker, not given).
public class ZeniPriceScreen extends Screen
{
    private static final int SLOTS = 18;

    private final RoleTrader role;
    private final Screen parent;
    private final EditBox[] buy = new EditBox[SLOTS];
    private final EditBox[] sell = new EditBox[SLOTS];

    // which page the tabs are showing. both EditBoxes stay alive (only visibility flips), so switching tabs never
    // drops a typed-but-unsaved value and save() can still read every box at once.
    private boolean showBuy = true;
    private ZeniTabButton buyTab;
    private ZeniTabButton sellTab;

    public ZeniPriceScreen(RoleTrader role, Screen parent)
    {
        super(Component.literal("Zeni Shop Prices"));
        this.role = role;
        this.parent = parent;
    }

    private ItemStack soldAt(int slot)
    {
        return slot < role.inventorySold.items.size() ? role.inventorySold.items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    protected void init()
    {
        // BUY and SELL are now separate pages behind tabs, so each slot has one field's worth of width. the buy box
        // and the sell box occupy the SAME cell and only one is visible at a time (updatePage flips visibility).
        int colW = 190;
        int left = this.width / 2 - colW;
        for (int slot = 0; slot < SLOTS; slot++)
        {
            int col = slot / 9;
            int row = slot % 9;
            int x = left + col * colW;
            int y = 44 + row * 18;

            ItemStack sold = soldAt(slot);
            boolean priceable = sold != null && !sold.isEmpty();

            EditBox b = new EditBox(this.font, x + 28, y, 70, 14, Component.literal("buy"));
            EditBox s = new EditBox(this.font, x + 28, y, 70, 14, Component.literal("sell"));
            for (EditBox box : new EditBox[] { b, s })
            {
                box.setMaxLength(9);
                box.setFilter(v -> v.isEmpty() || v.chars().allMatch(Character::isDigit));
                box.setEditable(priceable);
            }
            if (priceable)
            {
                int bp = ZeniShop.buyPrice(sold);
                int sp = ZeniShop.sellPrice(sold);
                if (bp > 0)
                    b.setValue(Integer.toString(bp));
                if (sp > 0)
                    s.setValue(Integer.toString(sp));
            }
            buy[slot] = b;
            sell[slot] = s;
            addRenderableWidget(b);
            addRenderableWidget(s);
        }

        // tab strip centred under the title, sized to its labels the way CNPC sizes its own top tabs.
        buyTab = new ZeniTabButton(0, 24, this.font, Component.literal("Buy"), btn -> setPage(true));
        sellTab = new ZeniTabButton(0, 24, this.font, Component.literal("Sell"), btn -> setPage(false));
        int total = buyTab.getWidth() + sellTab.getWidth();
        int tx = this.width / 2 - total / 2;
        buyTab.setX(tx);
        sellTab.setX(tx + buyTab.getWidth());
        addRenderableWidget(buyTab);
        addRenderableWidget(sellTab);

        addRenderableWidget(Button.builder(Component.literal("Save & Close"), b -> save())
                .bounds(this.width / 2 - 154, this.height - 28, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(this.width / 2 + 4, this.height - 28, 150, 20).build());

        updatePage();
    }

    private void setPage(boolean buyPage)
    {
        this.showBuy = buyPage;
        updatePage();
    }

    // reflect the active tab: only the current page's fields render and accept input. an invisible EditBox rejects
    // both clicks and keystrokes, so hidden values sit untouched until save() reads them.
    private void updatePage()
    {
        buyTab.setActive(showBuy);
        sellTab.setActive(!showBuy);
        for (int slot = 0; slot < SLOTS; slot++)
        {
            if (buy[slot] != null)
                buy[slot].setVisible(showBuy);
            if (sell[slot] != null)
                sell[slot].setVisible(!showBuy);
        }
    }

    private void save()
    {
        for (int slot = 0; slot < SLOTS; slot++)
        {
            ItemStack sold = soldAt(slot);
            if (sold == null || sold.isEmpty())
                continue;
            ZeniShop.setBuyPrice(sold, parse(buy[slot]));
            ZeniShop.setSellPrice(sold, parse(sell[slot]));
        }
        Packets.sendServer(new SPacketNpcRoleSave(role.save(new CompoundTag())));
        onClose();
    }

    private static int parse(EditBox box)
    {
        String v = box.getValue().trim();
        if (v.isEmpty())
            return 0;
        try
        {
            return Math.max(0, Integer.parseInt(v));
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial)
    {
        this.renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        int colW = 190;
        int left = this.width / 2 - colW;
        for (int slot = 0; slot < SLOTS; slot++)
        {
            int col = slot / 9;
            int row = slot % 9;
            int x = left + col * colW;
            int y = 44 + row * 18;
            // trade item + count so the admin sees which slot is which
            ItemStack sold = soldAt(slot);
            if (sold != null && !sold.isEmpty())
            {
                g.renderItem(sold, x, y - 3);
                g.renderItemDecorations(this.font, sold, x, y - 3);
            }
            else
            {
                g.drawString(this.font, "#" + (slot + 1), x + 4, y + 3, 0x555555, false);
            }
            // one Zeni symbol before the active page's field, coloured to match that page (buy yellow, sell green)
            g.drawString(this.font, "Ƶ", x + 20, y + 3, showBuy ? 0xFFFF55 : 0x55FF55, false);
        }
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public void onClose()
    {
        this.minecraft.setScreen(parent);
    }
}
