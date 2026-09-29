package net.shurui.dev.sdu.client.gui.shenron;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.shurui.dev.sdu.client.gui.DmzTextureButton;
import net.shurui.dev.sdu.client.gui.DmzTextures;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.ShrineSummonC2S;
import net.shurui.dev.sdu.shenron.ShrineColor;
import net.shurui.dev.sdu.shenron.ShrineRequiredItem;

import java.util.ArrayList;
import java.util.List;

/**
 * The Shenron-shrine summoning screen. Shows the required items (icon + count, green if the player has enough,
 * red otherwise) and a "Summon Shenron" button (disabled when items are missing). Styled with DMZ's menubig
 * panel to match the rest of the SDU editor GUIs. Entirely independent of DMZ's own wish/summon screens.
 */
public class ShrineScreen extends Screen {

    private final BlockPos pos;
    private final ShrineColor color;
    private final List<ShrineRequiredItem> required;
    private final boolean hasItems;

    private int panelX;
    private int panelY;
    private final int panelW = 220;
    private final int panelH = 180;

    private ShrineScreen(BlockPos pos, ShrineColor color, List<ShrineRequiredItem> required, boolean hasItems) {
        super(Component.translatable("gui.dmz_ragnarok.npc.shrine.title"));
        this.pos = pos;
        this.color = color;
        this.required = required == null ? new ArrayList<>() : required;
        this.hasItems = hasItems;
    }

    public static void open(BlockPos pos, ShrineColor color, List<ShrineRequiredItem> required, boolean hasItems) {
        Minecraft.getInstance().setScreen(new ShrineScreen(pos, color, required, hasItems));
    }

    @Override
    protected void init() {
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        DmzTextureButton summon = new DmzTextureButton(panelX + panelW / 2 - 80, panelY + panelH - 52, 160, 20,
                Component.translatable("gui.dmz_ragnarok.npc.shrine.summon"), DmzTextures.MENU_BIG,
                DmzTextures.BUTTON_U, DmzTextures.BUTTON_V, DmzTextures.BUTTON_W, DmzTextures.BUTTON_H,
                DmzTextures.ATLAS, () -> {
            DmzNet.sendToServer(new ShrineSummonC2S(pos, color));
            onClose();
        });
        summon.active = hasItems;
        addRenderableWidget(summon);

        addRenderableWidget(new DmzTextureButton(panelX + panelW / 2 - 80, panelY + panelH - 28, 160, 20,
                Component.translatable("gui.dmz_ragnarok.npc.btn.close"), DmzTextures.MENU_BIG,
                DmzTextures.BUTTON_U, DmzTextures.BUTTON_V, DmzTextures.BUTTON_W, DmzTextures.BUTTON_H,
                DmzTextures.ATLAS, this::onClose));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        DmzTextures.panel(g, panelX, panelY, panelW, panelH);

        Component title = Component.translatable("gui.dmz_ragnarok.npc.shrine.title.color",
                Component.translatable("gui.dmz_ragnarok.npc.shrine.color." + color.key()));
        g.drawCenteredString(this.font, title, panelX + panelW / 2, panelY + 10, 0xFFFFAA00);
        g.drawCenteredString(this.font, Component.translatable("gui.dmz_ragnarok.npc.shrine.requirements"),
                panelX + panelW / 2, panelY + 26, 0xFFFFFFFF);

        int rowY = panelY + 42;
        int iconX = panelX + 22;
        for (ShrineRequiredItem req : required) {
            Item item = resolve(req.item);
            ItemStack stack = item == null ? ItemStack.EMPTY : new ItemStack(item);
            if (!stack.isEmpty()) {
                g.renderItem(stack, iconX, rowY - 4);
            }
            Component name = stack.isEmpty()
                    ? Component.literal(req.item)
                    : stack.getHoverName();
            int textColor = this.hasItems ? 0xFF55FF55 : 0xFFFF5555;
            g.drawString(this.font, Component.literal("x" + req.count + "  ").append(name),
                    iconX + 22, rowY, textColor, false);
            rowY += 20;
        }

        if (required.isEmpty()) {
            g.drawCenteredString(this.font, Component.translatable("gui.dmz_ragnarok.npc.shrine.no_requirements"),
                    panelX + panelW / 2, rowY, 0xFF55FF55);
        } else if (!hasItems) {
            g.drawCenteredString(this.font, Component.translatable("gui.dmz_ragnarok.npc.shrine.missing_items"),
                    panelX + panelW / 2, panelY + panelH - 66, 0xFFFF5555);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private static Item resolve(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
