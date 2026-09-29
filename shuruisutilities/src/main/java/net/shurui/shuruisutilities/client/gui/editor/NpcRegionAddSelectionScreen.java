package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.compat.client.NpcRegionCacheClient;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

// popup after right-dragging a box on Xaero's World Map: ADD it to an existing region (from the list) or CREATE
// a new one. runs /npcregion addbox or create (server-authoritative), opening the region editor for new regions.
public class NpcRegionAddSelectionScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_ROW_H = 15;

    private final String dim;
    private final int x1, z1, x2, z2;
    private int scroll = 0;

    public NpcRegionAddSelectionScreen(String dim, int x1, int z1, int x2, int z2)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.npcsel.add_title"), UI_W, UI_H, null);
        this.dim = dim;
        this.x1 = x1;
        this.z1 = z1;
        this.x2 = x2;
        this.z2 = z2;
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();

        int w = Math.abs(x2 - x1) + 1, h = Math.abs(z2 - z1) + 1;
        headerSubtitle = tr("gui.dmz_ragnarok.core.npcsel.dims", w, h);
        rowY = 30;

        label("§e" + tr("gui.dmz_ragnarok.core.npcsel.add_to_existing"), 14, rowY);
        rowY += 12;

        List<String> names = NpcRegionCacheClient.namesIn(dim);
        int listTop = rowY;
        int maxRows = rowsThatFit(listTop, LIST_ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, names.size() - maxRows)));
        int end = Math.min(names.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            final String name = names.get(i);
            int ry = listTop + (i - scroll) * LIST_ROW_H;
            rowBtn(14, ry, rowControlRight() - 14, LIST_ROW_H - 2, Component.literal("§f" + name),
                    () -> run("npcregion addbox " + name + " " + x1 + " " + z1 + " " + x2 + " " + z2));
        }
        if (names.isEmpty())
            label("§7" + tr("gui.dmz_ragnarok.core.npcsel.no_regions"), 14, listTop + 2);
        scrollList(14, uiWidth, listTop, LIST_ROW_H, maxRows, names.size(), scroll,
                v -> { scroll = v; rebuildWidgets(); });

        int by = footerY();
        btn(UI_W / 2 - 104, by, 100, footerBtnHeight(), Component.literal("§a").append(Component.translatable("gui.dmz_ragnarok.core.npcsel.create_new")),
                () -> run("npcregion create " + x1 + " " + z1 + " " + x2 + " " + z2));
        btn(UI_W / 2 + 4, by, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.npcsel.cancel"), this::onClose);
    }

    private void run(String command)
    {
        onClose();
        EditorScreens.runCommand(command);
    }

    @Override
    public void onClose()
    {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
