package net.shurui.shuruisutilities.compat.customnpcs.client;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.compat.customnpcs.PacketZeniShopTab;
import net.shurui.shuruisutilities.compat.customnpcs.ZeniShop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import noppes.npcs.client.gui.player.GuiNPCTrader;
import noppes.npcs.client.gui.roles.GuiNpcTraderSetup;
import noppes.npcs.containers.ContainerNPCTrader;
import noppes.npcs.containers.ContainerNPCTraderSetup;
import noppes.npcs.roles.RoleTrader;

// client CNPC shop integration via ScreenEvents, not mixins (mixing into a deobf-remapped third-party mod's
// vanilla-inherited methods doesn't remap reliably). trader setup screen gets a "Zeni Prices" button; player
// shop screen draws each slot's price. only registered when CustomNPCs is present, so the noppes bodies never load.
public final class ZeniShopClient
{
    private ZeniShopClient() {}

    // Zeni symbol (U+01B5)
    public static final String ZENI = "Ƶ";

    // which tab the player shop is filtered to. we can only decorate GuiNPCTrader from the outside (ScreenEvent), so
    // the tab state lives here, not on the screen. one shop is open at a time, so a single field is enough; it is
    // reset to the buy page each time a trader opens so a reopened shop never inherits the last shop's tab.
    private boolean shopShowBuy = true;

    // Whether the open shop has content on BOTH sides and therefore needs the tab strip at all. A shop that only
    // buys, or only sells, has every slot on one tab, so showing tabs would let the player switch to an empty one
    // and black the whole shop out. Computed once per open, in onScreenInit.
    private boolean shopSplit;

    public static void register()
    {
        MinecraftForge.EVENT_BUS.register(new ZeniShopClient());
    }

    // AbstractContainerScreen leftPos/topPos (SRG names), read reflectively since they're protected
    private static int guiLeft(AbstractContainerScreen<?> screen)
    {
        return ObfuscationReflectionHelper.getPrivateValue(AbstractContainerScreen.class, screen, "f_97735_");
    }

    private static int guiTop(AbstractContainerScreen<?> screen)
    {
        return ObfuscationReflectionHelper.getPrivateValue(AbstractContainerScreen.class, screen, "f_97736_");
    }

    // AbstractContainerScreen imageWidth (SRG name), read reflectively like the two above. we need the real
    // panel width to size the cover band rather than a hardcoded guess that drifts if CNPC changes its texture.
    private static int imageWidth(AbstractContainerScreen<?> screen)
    {
        return ObfuscationReflectionHelper.getPrivateValue(AbstractContainerScreen.class, screen, "f_97726_");
    }

    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event)
    {
        Screen screen = event.getScreen();
        // A freshly opened shop picks its starting tab from what the shop actually HAS, and never inherits a stale
        // tab from a prior shop. This used to force the Buy page unconditionally, which is what made a correctly
        // configured sell-only shop open with every item blacked out: those slots belong to the Sell tab, so on the
        // Buy tab isCovered painted over all of them and the operator saw a working shop render as greyed out.
        if (screen instanceof GuiNPCTrader trader)
        {
            RoleTrader role = ((ContainerNPCTrader) trader.getMenu()).role;
            boolean hasBuy = hasSide(role, true);
            boolean hasSell = hasSide(role, false);
            shopSplit = hasBuy && hasSell;
            // Open on Buy whenever there is anything to buy, and fall back to Buy for a shop with no Zeni prices at
            // all (an ordinary CustomNPCs trader), which leaves those behaving exactly as they did before.
            shopShowBuy = hasBuy || !hasSell;
            reportTab();
        }
        if (!(screen instanceof GuiNpcTraderSetup setup))
            return;
        RoleTrader role = ((ContainerNPCTraderSetup) setup.getMenu()).role;
        int x = guiLeft(setup) + 214;
        int y = guiTop(setup) + 190;
        event.addListener(Button.builder(Component.literal("Zeni Prices"),
                b -> Minecraft.getInstance().setScreen(new ZeniPriceScreen(role, setup)))
                .bounds(x, y, 100, 20).build());
    }

    @SubscribeEvent
    public void onScreenRender(ScreenEvent.Render.Post event)
    {
        Screen screen = event.getScreen();
        if (!(screen instanceof GuiNPCTrader trader))
            return;
        RoleTrader role = ((ContainerNPCTrader) trader.getMenu()).role;
        GuiGraphics graphics = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        int left = guiLeft(trader);
        int top = guiTop(trader);
        int width = imageWidth(trader);
        int mouseX = event.getMouseX();
        int mouseY = event.getMouseY();
        for (int slot = 0; slot < 18 && slot < role.inventorySold.items.size(); slot++)
        {
            ItemStack sold = role.inventorySold.items.get(slot);
            if (sold == null || sold.isEmpty())
                continue;
            int buy = ZeniShop.buyPrice(sold);
            int sell = ZeniShop.sellPrice(sold);
            int x = left + slot % 3 * 72 + 10;
            int y = top + slot / 3 * 21 + 6;
            // a trade belonging to the other tab must be hidden. we render at Render.Post, so CNPC has already drawn
            // all 18 slots; filtering means painting over the sold item at a raised z (above CNPC's own z+300 hover
            // text), not preventing the draw. onMouseClicked cancels clicks on the same slots so a hidden trade
            // cannot be bought. isCovered classifies identically in both places so the cover and the block never drift.
            if (shopSplit && isCovered(shopShowBuy, sold))
            {
                graphics.pose().pushPose();
                graphics.pose().translate(0, 0, 400);
                graphics.fill(x + 40, y - 1, x + 70, y + 18, 0xF0202020);
                graphics.pose().popPose();
                continue;
            }
            if (buy <= 0 && sell <= 0)
                continue;
            // On a slot with both prices the visible tab decides which price is shown, and it must agree with the
            // direction the server will take (ZeniShopTabs), or the label would promise the opposite of the trade.
            boolean showingSell = (buy > 0 && sell > 0) ? !shopShowBuy : sell > 0;
            String label = showingSell ? "+" + ZENI + sell : ZENI + Integer.toString(buy);
            int color = showingSell ? 0xFF55FF55 : 0xFFFFFF55;
            graphics.drawString(font, label, x, y + 5, color, false);

            // cover CNPC's misleading "you have enough money" line (drawn on sold-slot hover, always green since
            // our Zeni trades have no currency item) with the actual price
            int soldX = left + slot % 3 * 72 + 53;
            int soldY = top + slot / 3 * 21 + 7;
            if (mouseX >= soldX && mouseX < soldX + 16 && mouseY >= soldY && mouseY < soldY + 16)
            {
                String msg = showingSell ? ("Sell for " + ZENI + sell)
                        : (ZENI + Integer.toString(buy) + " to buy");
                int w = font.width(msg);
                int ty = top + 131;
                // fill the whole panel width at this line, not just a box the size of our short label. CNPC centres
                // its own "trader.sufficient" / "trader.insufficient" sentence here and that string is wider than
                // our price, so a fill sized to ours leaves their text poking out past both ends and still legible.
                // their insufficient variant is drawn at z+300 to sit above the panel art, so we push above that too,
                // otherwise depth testing keeps the raised text visible through a band left at the default depth.
                graphics.pose().pushPose();
                graphics.pose().translate(0, 0, 400);
                graphics.fill(left + 1, ty - 2, left + width - 1, ty + 10, 0xF0100010);
                graphics.drawString(font, msg, left + (width - w) / 2, ty, color, false);
                graphics.pose().popPose();
            }
        }

        // draw the tabs last, above the panel, replicating the same GuiMenuTopButton look as the editor's real
        // widget (we cannot host a live widget on CNPC's screen through ScreenEvent). onMouseClicked owns the same
        // geometry and switches the filter, so these are pure paint. y = top - 18 seats them on the panel's top edge
        // the way CNPC's own top tabs sit.
        // A single-sided shop shows no tabs: there is nothing to switch to, and offering the switch is what let a
        // player land on an empty tab where every slot is covered.
        if (!shopSplit)
            return;
        int bw = ZeniTabButton.tabWidth(font, "Buy");
        int sw = ZeniTabButton.tabWidth(font, "Sell");
        int tabY = top - 18;
        boolean buyHover = inTab(mouseX, mouseY, left, tabY, bw, shopShowBuy);
        boolean sellHover = inTab(mouseX, mouseY, left + bw, tabY, sw, !shopShowBuy);
        ZeniTabButton.draw(graphics, font, left, tabY, bw, Component.literal("Buy"), shopShowBuy, buyHover);
        ZeniTabButton.draw(graphics, font, left + bw, tabY, sw, Component.literal("Sell"), !shopShowBuy, sellHover);
    }

    // A slot is hidden when its Zeni role puts it on the OTHER tab. This is the single source of truth shared by the
    // render cover and the click block, so what looks hidden is exactly what is blocked.
    //
    // The two tabs are deliberately NOT symmetric. The Buy tab hides only what is sell-priced, so an ordinary
    // (non-Zeni) CustomNPCs trade stays visible and buyable there exactly as it always was. The Sell tab hides
    // everything WITHOUT a sell price, which is the stricter test: asking "is this buy-priced" let a plain CNPC
    // trade through, because it carries neither price, and CustomNPCs then completed the purchase from the sell
    // page. Anything the player cannot sell has no business being clickable while they are looking at Sell.
    private static boolean isCovered(boolean showBuy, ItemStack sold)
    {
        int buy = ZeniShop.buyPrice(sold);
        int sell = ZeniShop.sellPrice(sold);
        // A slot carrying BOTH prices belongs to BOTH tabs: buy it on Buy, sell it on Sell. It used to be classified
        // as sell-only, which covered it on the Buy tab and cancelled its clicks, so its buy price was unreachable no
        // matter what the operator typed. The server tells the two apart by the player's open tab (ZeniShopTabs).
        if (buy > 0 && sell > 0)
            return false;
        return showBuy ? sell > 0 : sell <= 0;
    }

    // Whether any slot in this shop carries a buy price, or a sell price. Drives both the opening tab and whether the
    // tab strip is shown at all, so a shop is never opened onto a page that has nothing on it.
    private static boolean hasSide(RoleTrader role, boolean buySide)
    {
        if (role == null || role.inventorySold == null)
            return false;
        for (int slot = 0; slot < 18 && slot < role.inventorySold.items.size(); slot++)
        {
            ItemStack sold = role.inventorySold.items.get(slot);
            if (sold == null || sold.isEmpty())
                continue;
            if (buySide ? ZeniShop.buyPrice(sold) > 0 : ZeniShop.sellPrice(sold) > 0)
                return true;
        }
        return false;
    }

    // Tell the server which tab is open, so a slot with both prices is read as the direction the player can see.
    // Sent on open and on every switch; the server defaults to Buy until it hears, which is the fail-safe guess.
    private void reportTab()
    {
        NetworkUtils.sendToServer(new PacketZeniShopTab(shopShowBuy));
    }

    // hit test against a tab, using CNPC's shrunk height for an inactive tab (2px shorter) so the dead 2px strip at
    // the bottom of an idle tab is not clickable, exactly as GuiMenuTopButton behaves.
    private static boolean inTab(double mx, double my, int x, int y, int width, boolean active)
    {
        int h = 20 - (active ? 0 : 2);
        return mx >= x && mx < x + width && my >= y && my < y + h;
    }

    @SubscribeEvent
    public void onMouseClicked(ScreenEvent.MouseButtonPressed.Pre event)
    {
        Screen screen = event.getScreen();
        if (!(screen instanceof GuiNPCTrader trader))
            return;
        RoleTrader role = ((ContainerNPCTrader) trader.getMenu()).role;
        Font font = Minecraft.getInstance().font;
        int left = guiLeft(trader);
        int top = guiTop(trader);
        double mx = event.getMouseX();
        double my = event.getMouseY();

        // A single-sided shop draws no tabs and covers nothing, so there is neither a strip to hit nor a hidden trade
        // to block. Leaving the hit tests live would eat clicks on invisible tabs.
        if (!shopSplit)
            return;

        // a click on the tab strip switches the filter and must not fall through to CNPC (which would treat it as a
        // slot or background click).
        int bw = ZeniTabButton.tabWidth(font, "Buy");
        int sw = ZeniTabButton.tabWidth(font, "Sell");
        int tabY = top - 18;
        if (inTab(mx, my, left, tabY, bw, shopShowBuy))
        {
            shopShowBuy = true;
            reportTab();
            event.setCanceled(true);
            return;
        }
        if (inTab(mx, my, left + bw, tabY, sw, !shopShowBuy))
        {
            shopShowBuy = false;
            reportTab();
            event.setCanceled(true);
            return;
        }

        // block buying a hidden trade. CNPC turns a click on the sold-item rect (i+43, j+1, 16, 16) into a purchase,
        // so cancelling exactly that rect for a covered slot stops the hidden trade from firing while leaving every
        // visible trade, and every non-slot click, untouched. we only cancel when isCovered positively places the
        // slot on the other tab, so a slot we cannot classify is never blocked: fail closed toward not eating a real
        // click. the cover we painted at Render.Post uses the same isCovered, so what looks hidden is what is blocked.
        for (int slot = 0; slot < 18 && slot < role.inventorySold.items.size(); slot++)
        {
            ItemStack sold = role.inventorySold.items.get(slot);
            if (sold == null || sold.isEmpty())
                continue;
            if (!isCovered(shopShowBuy, sold))
                continue;
            int i = left + slot % 3 * 72 + 10;
            int j = top + slot / 3 * 21 + 6;
            if (mx >= i + 43 && mx < i + 43 + 16 && my >= j + 1 && my < j + 1 + 16)
            {
                event.setCanceled(true);
                return;
            }
        }
    }
}
