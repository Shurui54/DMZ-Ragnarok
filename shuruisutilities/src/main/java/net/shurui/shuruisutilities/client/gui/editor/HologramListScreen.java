package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.SUHubScreen;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

// holograms list: each hologram + dim/pos, click to edit. footer creates one at the player's pos.
// rows: [name, dim, x, y, z, line...].
public class HologramListScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;

    private final List<List<String>> rows;
    private int scroll = 0;
    private EditBox newBox;

    /** The gif names the server has loaded, passed straight through to the edit screen's selector. */
    private final List<String> gifs;

    public HologramListScreen(List<String> gifs, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.holograms"), UI_W, UI_H, null);
        this.rows = rows;
        this.gifs = gifs;
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.core.holo.subtitle", rows.size());
        // the new-name field sits on the footer row (UI_H - 24 = contentBottom), so the list already stops above it.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            List<String> r = rows.get(i);
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            String coords = shortDim(r.get(1)) + "  " + round(r.get(2)) + ", " + round(r.get(3)) + ", " + round(r.get(4));
            rowBtn(14, ry, rowControlRight() - 14, GuiTheme.ROW_HEIGHT, Component.literal(r.get(0)),
                    () -> minecraft.setScreen(new HologramEditScreen(gifs, r))).color(0xFF9BE0AB)
                    .right(Component.literal(coords), 0xFFB0B0B0);
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        newBox = field(14, UI_H - 24, 150, "");
        newBox.setHint(Component.translatable("gui.dmz_ragnarok.core.holo.new_hint"));
        newBox.setMaxLength(64);
        btn(168, footerY(), 56, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.new"), () -> {
            String v = newBox.getValue().trim();
            if (!v.isBlank())
                EditorScreens.act("holograms", "new", v);
        });
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private static String shortDim(String dim)
    {
        int i = dim.indexOf(':');
        return i >= 0 ? dim.substring(i + 1) : dim;
    }

    private static String round(String d)
    {
        try
        {
            return Long.toString(Math.round(Double.parseDouble(d)));
        }
        catch (NumberFormatException e)
        {
            return d;
        }
    }
}
