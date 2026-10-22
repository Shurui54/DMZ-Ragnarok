package net.shurui.shuruisutilities.vehicleworkbench.client;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import net.shurui.dev.sdu.client.gui.DmzTextureButton;
import net.shurui.dev.sdu.client.gui.theme.GuiSounds;
import net.shurui.dev.sdu.client.gui.theme.GuiText;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.client.gui.theme.ThemeRender;
import net.shurui.shuruisutilities.vehicleworkbench.VehicleTiers;
import net.shurui.shuruisutilities.vehicleworkbench.VehicleUpgradeMenu;
import net.shurui.shuruisutilities.vehicleworkbench.VehicleUpgrades;

/**
 * The upgrade bench screen (compact console), on the suite's GuiTheme: vehicle slots and the kit slot on the left, the
 * previewed result and what it costs on the right. The preview runs the same {@link VehicleUpgrades#plan} the server
 * runs, with the server's limits from the menu's data slots; Upgrade only asks, the server decides.
 */
public class VehicleUpgradeScreen extends net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<VehicleUpgradeMenu>
{
    private static final int W = 232;
    private static final int H = VehicleUpgradeMenu.INV_Y + 58 + 16 + 8;
    private static final int BOX_X = 120;
    private static final int BOX_Y = GuiTheme.CONTENT_TOP;
    private static final int BOX_W = W - 8 - BOX_X;
    private static final int BOX_H = VehicleUpgradeMenu.INV_Y - 14 - BOX_Y;

    private DmzTextureButton upgradeButton;
    private VehicleUpgrades.Plan plan;

    public VehicleUpgradeScreen(VehicleUpgradeMenu menu, Inventory inv, Component title)
    {
        super(menu, inv, title);
        this.imageWidth = W;
        this.imageHeight = H;
    }

    @Override
    protected void init()
    {
        super.init();
        upgradeButton = new DmzTextureButton(leftPos + BOX_X + 4, topPos + BOX_Y + BOX_H - GuiTheme.BUTTON_HEIGHT - 4,
                BOX_W - 8, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.vehicle_upgrade.button"),
                GuiTheme.BUTTONS, 0, 0, 0, 0, 0, this::upgrade);
        upgradeButton.commits();
        addRenderableWidget(upgradeButton);
        GuiSounds.navigate();
    }

    /** Ask the server to upgrade what is in the slots (also used by the dev visual test). */
    public void upgrade()
    {
        if (minecraft != null && minecraft.gameMode != null)
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, VehicleUpgradeMenu.BTN_UPGRADE);
    }

    private VehicleUpgrades.Plan currentPlan()
    {
        if (plan == null && minecraft != null && minecraft.level != null)
            plan = menu.plan(minecraft.level.getRecipeManager());
        return plan;
    }

    @Override
    protected void containerTick()
    {
        super.containerTick();
        plan = null;
        VehicleUpgrades.Plan p = currentPlan();
        upgradeButton.active = p != null && p.ok();
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mx, int my)
    {
        int x = leftPos;
        int y = topPos;
        ThemeRender.panel(g, x, y, imageWidth, imageHeight);
        ThemeRender.header(g, x, y, imageWidth);
        ThemeRender.tooltip(g, x + BOX_X, y + BOX_Y, BOX_W, BOX_H);
        for (Slot s : menu.slots)
            slotArt(g, x + s.x - 1, y + s.y - 1);

        VehicleUpgrades.Plan p = currentPlan();
        int rx = x + BOX_X + (BOX_W - 18) / 2;
        int ry = y + BOX_Y + 4;
        slotArt(g, rx, ry);
        if (p == null)
            return;
        int tx = x + BOX_X + 4;
        int tw = BOX_W - 8;
        int ty = ry + 22;
        if (p.ok())
        {
            g.renderItem(p.result(), rx + 1, ry + 1);
            GuiText.drawFitted(g, font, p.result().getHoverName().getString(), tx - GuiTheme.TEXT_PADDING_X, ty, tw, 10,
                    GuiTheme.COLOR_TITLE);
            GuiText.drawFitted(g, font, VehicleTiers.label(VehicleTiers.tier(p.result())).getString(),
                    tx - GuiTheme.TEXT_PADDING_X, ty + 11, tw, 10, GuiTheme.COLOR_VALUE);
            String how = p.mode() == VehicleUpgrades.Mode.MERGE
                    ? Component.translatable("gui.dmz_ragnarok.vehicle_upgrade.uses_copies", p.vehicleSlots().size()).getString()
                    : Component.translatable("gui.dmz_ragnarok.vehicle_upgrade.uses_kits", p.kits()).getString();
            GuiText.drawFitted(g, font, how, tx - GuiTheme.TEXT_PADDING_X, ty + 22, tw, 10, GuiTheme.COLOR_LABEL);
        }
        else
        {
            List<FormattedCharSequence> lines = font.split(p.problem(), tw);
            for (int i = 0; i < lines.size() && i < 4; i++)
                g.drawString(font, lines.get(i), tx, ty + i * 10, GuiTheme.COLOR_MUTED, false);
        }
    }

    private void slotArt(GuiGraphics g, int x, int y)
    {
        g.blit(GuiTheme.SLOT, x, y, 18, 18, 0.0F, 0.0F, GuiTheme.SLOT_TEX_W, GuiTheme.SLOT_TEX_H,
                GuiTheme.SLOT_TEX_W, GuiTheme.SLOT_TEX_H);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my)
    {
        g.drawString(font, title, GuiTheme.NAME_TOP_LEFT_X + 2, GuiTheme.NAME_TOP_LEFT_Y, GuiTheme.COLOR_TITLE, false);
        g.drawString(font, Component.translatable("gui.dmz_ragnarok.vehicle_upgrade.vehicles"),
                VehicleUpgradeMenu.VEHICLE_X, VehicleUpgradeMenu.VEHICLE_Y - 11, GuiTheme.COLOR_LABEL, false);
        g.drawString(font, Component.translatable("gui.dmz_ragnarok.vehicle_upgrade.kits"),
                VehicleUpgradeMenu.KIT_X, VehicleUpgradeMenu.KIT_Y - 11, GuiTheme.COLOR_LABEL, false);
        g.drawString(font, Component.translatable("gui.dmz_ragnarok.vehicle_upgrade.max_label",
                        VehicleTiers.roman(menu.maxTier())),
                VehicleUpgradeMenu.KIT_X + 24, VehicleUpgradeMenu.KIT_Y + 5, GuiTheme.COLOR_MUTED, false);
        g.drawString(font, playerInventoryTitle, VehicleUpgradeMenu.INV_X, VehicleUpgradeMenu.INV_Y - 10,
                GuiTheme.COLOR_LABEL, false);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial)
    {
        renderBackground(g);
        super.render(g, mx, my, partial);
        renderTooltip(g, mx, my);
        VehicleUpgrades.Plan p = currentPlan();
        int rx = leftPos + BOX_X + (BOX_W - 18) / 2;
        int ry = topPos + BOX_Y + 4;
        if (p != null && p.ok() && mx >= rx && mx < rx + 18 && my >= ry && my < ry + 18)
            g.renderTooltip(font, p.result(), mx, my);
    }

    /** The previewed result, or empty (dev visual test reads it). */
    public ItemStack preview()
    {
        VehicleUpgrades.Plan p = currentPlan();
        return p == null ? ItemStack.EMPTY : p.result();
    }
}
