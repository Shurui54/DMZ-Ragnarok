package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

// warps menu: scrollable list, each with a Warp button (/warp <name>). managers also get per-row Delete + a
// footer add box (both dispatch /warp ...). meta = [canSet, canDelete] so options match perms.
public class WarpsScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;

    private final List<List<String>> rows;
    private final boolean canSet;
    private final boolean canDelete;
    private int scroll = 0;
    private EditBox setBox;

    public WarpsScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.menu.warps"), UI_W, UI_H, null);
        this.rows = rows;
        this.canSet = meta.size() > 0 && Boolean.parseBoolean(meta.get(0));
        this.canDelete = meta.size() > 1 && Boolean.parseBoolean(meta.get(1));
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.core.warps.subtitle", rows.size());
        // Reserve holds the footer-level "set warp" field (drawn at UI_H - 24) clear of the list.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H, 10);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            final List<String> row = rows.get(i);
            final String name = row.get(0);
            // Older servers send a one column row, so anything without a kind is a warp. That keeps a client
            // talking to a server that predates server rows from misreading every warp as one.
            final boolean isServer = row.size() > 1 && "server".equals(row.get(1));
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            if (isServer)
            {
                // Coloured apart from the warps, because going to another server is a bigger thing than a warp
                // within this one: the world changes, and everyone around you does too.
                label("§b" + name, 14, ry + 4, 0xFFFFFFFF);
                btn(150, ry, 46, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.warps.server"),
                        () -> { EditorScreens.runCommand("server " + name); onClose(); });
                continue;   // never deletable: it is not a warp anybody set
            }
            label("§f" + name, 14, ry + 4, 0xFFFFFFFF);
            btn(150, ry, 46, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.warps.warp"), () -> { EditorScreens.runCommand("warp " + name); onClose(); });
            if (canDelete)
                btn(200, ry, 46, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.delete"),
                        () -> { EditorScreens.runCommand("warp delete " + name); EditorScreens.reopen("warps"); });
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        if (canSet)
        {
            setBox = field(14, UI_H - 24, 140, "");
            setBox.setHint(Component.translatable("gui.dmz_ragnarok.core.warps.set_hint"));
            setBox.setMaxLength(64);
            btn(158, footerY(), 56, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.warps.set"), () -> {
                String v = setBox.getValue().trim();
                if (!v.isBlank())
                {
                    EditorScreens.runCommand("warp set " + v);
                    EditorScreens.reopen("warps");
                }
            });
        }
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openPlayerHub);
    }
}
