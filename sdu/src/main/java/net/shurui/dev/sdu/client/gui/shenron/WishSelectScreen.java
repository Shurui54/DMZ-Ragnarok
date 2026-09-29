package net.shurui.dev.sdu.client.gui.shenron;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.DmzTextureButton;
import net.shurui.dev.sdu.client.gui.DmzTextures;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.ShrineWishC2S;
import net.shurui.dev.sdu.shenron.ShrineColor;
import net.shurui.dev.sdu.shenron.ShrineWish;

import java.util.ArrayList;
import java.util.List;

/**
 * The wish-selection screen shown to the summoner after Shenron appears. Lists the colour's available wishes;
 * clicking a row selects it (hovering shows its description as a tooltip), and Confirm sends the choice to the
 * server. Closing without confirming simply lets Shenron time out. Fully custom - never touches DMZ's
 * {@code WishesScreen}.
 */
public class WishSelectScreen extends Screen {

    private final int entityId;
    private final ShrineColor color;
    private final List<ShrineWish> wishes;

    private int selected = -1;
    private DmzTextureButton confirmButton;
    private int panelX;
    private int panelY;
    private final int panelW = 240;
    private final int panelH = 200;
    private static final int ROW_H = 18;
    private int listTop;

    private WishSelectScreen(int entityId, ShrineColor color, List<ShrineWish> wishes) {
        super(Component.translatable("gui.dmz_ragnarok.npc.wish.title"));
        this.entityId = entityId;
        this.color = color;
        this.wishes = wishes == null ? new ArrayList<>() : wishes;
    }

    public static void open(int entityId, ShrineColor color, List<ShrineWish> wishes) {
        Minecraft.getInstance().setScreen(new WishSelectScreen(entityId, color, wishes));
    }

    @Override
    protected void init() {
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        listTop = panelY + 30;

        confirmButton = new DmzTextureButton(panelX + panelW / 2 - 80, panelY + panelH - 50, 160, 20,
                Component.translatable("gui.dmz_ragnarok.npc.wish.confirm"), DmzTextures.MENU_BIG,
                DmzTextures.BUTTON_U, DmzTextures.BUTTON_V, DmzTextures.BUTTON_W, DmzTextures.BUTTON_H,
                DmzTextures.ATLAS, () -> {
            if (selected >= 0 && selected < wishes.size()) {
                DmzNet.sendToServer(new ShrineWishC2S(entityId, color, wishes.get(selected).id));
                onClose();
            }
        });
        confirmButton.active = selected >= 0 && selected < wishes.size();
        addRenderableWidget(confirmButton);

        addRenderableWidget(new DmzTextureButton(panelX + panelW / 2 - 80, panelY + panelH - 26, 160, 20,
                Component.translatable("gui.dmz_ragnarok.npc.btn.close"), DmzTextures.MENU_BIG,
                DmzTextures.BUTTON_U, DmzTextures.BUTTON_V, DmzTextures.BUTTON_W, DmzTextures.BUTTON_H,
                DmzTextures.ATLAS, this::onClose));
    }

    private int rowsVisible() {
        int listBottom = panelY + panelH - 56;
        return Math.max(1, (listBottom - listTop) / ROW_H);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        DmzTextures.panel(g, panelX, panelY, panelW, panelH);
        g.drawCenteredString(this.font, this.title, panelX + panelW / 2, panelY + 10, 0xFFFFAA00);

        int rows = Math.min(rowsVisible(), wishes.size());
        ShrineWish hovered = null;
        for (int i = 0; i < rows; i++) {
            ShrineWish w = wishes.get(i);
            int rowY = listTop + i * ROW_H;
            boolean over = mouseX >= panelX + 12 && mouseX <= panelX + panelW - 12
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (i == selected) {
                g.fill(panelX + 10, rowY - 1, panelX + panelW - 10, rowY + ROW_H - 2, 0x8033AA33);
            } else if (over) {
                g.fill(panelX + 10, rowY - 1, panelX + panelW - 10, rowY + ROW_H - 2, 0x40FFFFFF);
            }
            String name = w.name == null || w.name.isBlank() ? w.id : w.name;
            g.drawString(this.font, name, panelX + 16, rowY + 4, 0xFFFFFFFF, false);
            if (over) {
                hovered = w;
            }
        }

        if (wishes.isEmpty()) {
            g.drawCenteredString(this.font, Component.translatable("gui.dmz_ragnarok.npc.wish.none"),
                    panelX + panelW / 2, listTop + 10, 0xFFFF5555);
        }

        super.render(g, mouseX, mouseY, partialTick);

        if (hovered != null && hovered.description != null && !hovered.description.isBlank()) {
            g.renderTooltip(this.font, Component.literal(hovered.description), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int rows = Math.min(rowsVisible(), wishes.size());
        for (int i = 0; i < rows; i++) {
            int rowY = listTop + i * ROW_H;
            if (mouseX >= panelX + 12 && mouseX <= panelX + panelW - 12
                    && mouseY >= rowY && mouseY < rowY + ROW_H) {
                selected = i;
                if (confirmButton != null) {
                    confirmButton.active = true;
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
