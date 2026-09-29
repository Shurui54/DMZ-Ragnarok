package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.SUHubScreen;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

// banned-items editor: scrollable list of ids (name + Remove) plus an add box for an id like minecraft:tnt.
// changes act() to the server, which re-sends the list.
public class BanItemScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;

    private final List<List<String>> rows;
    private int scroll = 0;
    private EditBox addBox;

    public BanItemScreen(List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.banitem"), UI_W, UI_H, null);
        this.rows = rows;
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.core.banitem.subtitle", rows.size());
        // the add-id field sits on the footer row (UI_H - 24 = contentBottom), so the list already stops above it.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            final String id = rows.get(i).get(0);
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            label("§e" + id, 14, ry + 4, 0xFFFFFF55);
            label("§7" + displayName(id), 150, ry + 4, 0xFFB0B0B0);
            btn(rowControlRight() - 52, ry, 52, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.delete"),
                    () -> EditorScreens.act("banitem", "remove", id));
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        addBox = field(14, UI_H - 24, 150, "");
        addBox.setHint(Component.translatable("gui.dmz_ragnarok.core.banitem.hint"));
        addBox.setMaxLength(128);
        btn(168, footerY(), 56, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.banitem.ban"), () -> {
            String v = addBox.getValue().trim();
            if (!v.isBlank())
                EditorScreens.act("banitem", "add", v);
        });
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    // item id -> translated display name, or the id if unknown
    private static String displayName(String id)
    {
        try
        {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
            if (item != null)
                return item.getDescription().getString();
        }
        catch (Exception ignored)
        {
        }
        return id;
    }
}
