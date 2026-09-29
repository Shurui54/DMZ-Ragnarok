package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

// the selections (boxes) of one NPC region: each row shows footprint + bounds with its own Remove, so parts can
// be deleted without touching the rest. Remove runs /npcregion removebox (server-authoritative) + mirrors it
// locally. the last remaining selection can't be removed here; delete the whole region instead.
public class NpcRegionSelectionsScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_ROW_H = 15;

    private final NpcRegionEditScreen parent;
    private int scroll = 0;

    public NpcRegionSelectionsScreen(NpcRegionEditScreen parent)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.npcsel.title"), UI_W, UI_H, parent);
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();

        List<int[]> boxes = parent.boxes;
        headerName = tr("gui.dmz_ragnarok.core.npcsel.subtitle", parent.regionName(), boxes.size());
        rowY = 30;

        int listTop = rowY;
        // Reserve one row so the "keep one selection" hint drawn just under the list stays inside the panel.
        int maxRows = rowsThatFit(listTop, LIST_ROW_H, LIST_ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, boxes.size() - maxRows)));
        int end = Math.min(boxes.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            int[] b = boxes.get(i);
            final int index = i;
            int ry = listTop + (i - scroll) * LIST_ROW_H;
            int w = b[3] - b[0] + 1, l = b[5] - b[2] + 1;
            label("§f#" + (i + 1) + " §7(" + b[0] + ", " + b[2] + ") - (" + b[3] + ", " + b[5] + ")  §8" + w + "x" + l,
                    14, ry + 3);
            if (boxes.size() > 1)
                btn(rowControlRight() - 40, ry, 40, LIST_ROW_H - 2, Component.literal("§c").append(Component.translatable("gui.dmz_ragnarok.core.npcsel.remove")), () -> removeBox(index));
        }
        scrollList(14, uiWidth, listTop, LIST_ROW_H, maxRows, boxes.size(), scroll,
                v -> { scroll = v; rebuildWidgets(); });

        if (boxes.size() <= 1)
            label("§7" + tr("gui.dmz_ragnarok.core.npcsel.keep_one"),
                    14, listTop + maxRows * LIST_ROW_H + 4);

        int by = footerY();
        btn(UI_W / 2 - 52, by, 104, footerBtnHeight(), Component.literal("§a").append(Component.translatable("gui.dmz_ragnarok.core.npc.done")),
                () -> Minecraft.getInstance().setScreen(parent));
    }

    // remove one selection: server deletes + resyncs; mirror locally so the list stays accurate
    private void removeBox(int index)
    {
        if (parent.boxes.size() <= 1 || index < 0 || index >= parent.boxes.size())
            return;
        EditorScreens.runCommand("npcregion removebox " + parent.regionName() + " " + (index + 1));
        parent.boxes.remove(index);
        rebuildWidgets();
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
