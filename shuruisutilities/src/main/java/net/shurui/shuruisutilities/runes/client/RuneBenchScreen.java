package net.shurui.shuruisutilities.runes.client;

import java.util.List;

import net.shurui.shuruisutilities.runes.ArmorRunes;
import net.shurui.shuruisutilities.runes.RuneBenchMenu;
import net.shurui.shuruisutilities.runes.RuneExtraction;
import net.shurui.shuruisutilities.runes.RuneItem;
import net.shurui.shuruisutilities.runes.RuneTier;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The rune bench screen: the piece's affinity on the left, a socket button, and one removal button per filled slot.
 *
 * <p>Removal buttons carry the warning in their own tooltip rather than in a corner of the window, so the risk is
 * read where the decision is made. A button that would DESTROY the rune says so in red and in those words; the
 * chance shown is the real one, since the outcome is a single roll rather than chained ones.
 */
public class RuneBenchScreen extends AbstractContainerScreen<RuneBenchMenu>
{
    /**
     * Vanilla's smithing container texture, used deliberately by PATH rather than copied into this mod. The
     * Ragnapack restyles this exact file, so pointing at it means the bench inherits the pack's art automatically
     * and keeps inheriting it when the pack changes; a copy under our own namespace would freeze today's look and
     * silently stop matching.
     */
    private static final net.minecraft.resources.ResourceLocation BG =
            new net.minecraft.resources.ResourceLocation("minecraft", "textures/gui/container/smithing.png");

    // Button grid, kept as constants so the no-overlap reasoning above is checkable rather than buried in calls.
    private static final int BTN_W = 52;
    private static final int BTN_H = 14;
    private static final int BTN_COL1 = 66;
    private static final int BTN_COL2 = 120;
    private static final int BTN_ROW1 = 16;
    private static final int BTN_ROW2 = 32;

    // The hammer's home in the smithing texture, and how far down it is redrawn. Sized to clear the slot row at y48.
    private static final int HAMMER_X = 6;
    private static final int HAMMER_Y = 4;
    private static final int HAMMER_W = 37;
    private static final int HAMMER_H = 35;
    private static final int HAMMER_DROP = 8;

    // Blank panel immediately right of the hammer, used to paint over where it used to be.
    private static final int PANEL_SRC_X = 46;
    private static final int PANEL_SRC_Y = 4;

    private Button socketBtn;
    private Button awakenBtn;
    private final Button[] removeBtns = new Button[ArmorRunes.SOCKETS];

    public RuneBenchScreen(RuneBenchMenu menu, Inventory inv, Component title)
    {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init()
    {
        super.init();
        int x = leftPos;
        int y = topPos;

        // Two rows of two, in the band between the title and the slot row. Every bound is checked against the
        // things around it: the rune slot ends at x62 so the columns start at 66; the right column ends at 172,
        // inside the 176 window; and the rows finish at y46, clear of the slots at y48 and the inventory label at
        // y72. Nothing overlaps a slot, the title, or the covered slots the smithing art draws.
        final int col1 = x + BTN_COL1;
        final int col2 = x + BTN_COL2;
        socketBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.dmz_ragnarok.rune_bench.socket"),
                b -> send(RuneBenchMenu.BTN_SOCKET))
                .bounds(col1, y + BTN_ROW1, BTN_W, BTN_H)
                .build());

        awakenBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.dmz_ragnarok.rune_bench.awaken"),
                b -> send(RuneBenchMenu.BTN_AWAKEN))
                .bounds(col2, y + BTN_ROW1, BTN_W, BTN_H)
                .build());

        for (int i = 0; i < ArmorRunes.SOCKETS; i++)
        {
            final int index = i;
            removeBtns[i] = addRenderableWidget(Button.builder(
                    Component.translatable("gui.dmz_ragnarok.rune_bench.remove", index + 1),
                    b -> send(RuneBenchMenu.BTN_REMOVE_BASE + index))
                    .bounds(index == 0 ? col1 : col2, y + BTN_ROW2, BTN_W, BTN_H)
                    .build());
        }
    }

    private void send(int button)
    {
        if (minecraft != null && minecraft.gameMode != null)
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
    }

    @Override
    protected void containerTick()
    {
        super.containerTick();
        ItemStack armor = menu.armor();
        boolean rolled = ArmorRunes.has(armor);
        List<ArmorRunes.Socket> sockets = rolled ? ArmorRunes.sockets(armor) : List.of();

        ItemStack rune = menu.rune();
        awakenBtn.active = rune.getItem() instanceof RuneItem dr && dr.isDormant();
        socketBtn.active = !armor.isEmpty()
                && rune.getItem() instanceof RuneItem r && !r.isDormant()
                && (!rolled || ArmorRunes.freeSockets(armor) > 0);

        for (int i = 0; i < ArmorRunes.SOCKETS; i++)
        {
            boolean filled = i < sockets.size();
            removeBtns[i].active = filled;
            removeBtns[i].setTooltip(filled ? warningFor(sockets.get(i)) : null);
        }
    }

    /** The warning shown on a removal button: which rune, and whether this would degrade or destroy it. */
    private net.minecraft.client.gui.components.Tooltip warningFor(ArmorRunes.Socket socket)
    {
        int pct = Math.round(RuneExtraction.DAMAGE_CHANCE * 100.0f);
        RuneTier tier = socket.tier();
        Component line = RuneExtraction.wouldRiskDestruction(tier)
                ? Component.translatable("tooltip.dmz_ragnarok.rune.remove_break",
                        Component.literal(socket.stat().label()).withStyle(socket.stat().colour()),
                        Component.literal(pct + "%").withStyle(ChatFormatting.RED))
                : Component.translatable("tooltip.dmz_ragnarok.rune.remove_degrade",
                        Component.literal(socket.stat().label()).withStyle(socket.stat().colour()),
                        Component.literal(pct + "%").withStyle(ChatFormatting.RED));
        return net.minecraft.client.gui.components.Tooltip.create(line);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mx, int my)
    {
        g.blit(BG, leftPos, topPos, 0, 0, imageWidth, imageHeight);

        // The hammer is baked into the smithing texture near the top, where it crowded the window. It cannot be
        // moved in the file without forking the art and losing the Ragnapack's restyle, so it is moved HERE: cover
        // where it sits, then draw it again lower down.
        //
        // The cover is sampled from the same texture rather than filled with a colour, from the empty band directly
        // beside the hammer. A hardcoded grey would be right for vanilla and wrong for every resource pack, which is
        // the whole reason this screen borrows the vanilla texture by path in the first place.
        g.blit(BG, leftPos + HAMMER_X, topPos + HAMMER_Y, PANEL_SRC_X, PANEL_SRC_Y, HAMMER_W, HAMMER_H);
        g.blit(BG, leftPos + HAMMER_X, topPos + HAMMER_Y + HAMMER_DROP, HAMMER_X, HAMMER_Y, HAMMER_W, HAMMER_H);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my)
    {
        g.drawString(font, title, 8, 6, 0xFFE0E0E8, false);
        g.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFFB0B0B8, false);

        // No affinity readout drawn here on purpose: at six rows it would run straight through the slots at y48,
        // and hovering the piece in its slot already shows the full block, from the same numbers.
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial)
    {
        renderBackground(g);
        super.render(g, mx, my, partial);
        renderTooltip(g, mx, my);
    }
}
