package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * The shop listing catalogue: every offer an admin has authored.
 *
 * <p>Rows are {@code [id, catalogId, price, enabled, category, limit]}. A listing id is NOT a cosmetic id: two
 * listings may sell one cosmetic, so the "new" here takes a fresh listing id and the cosmetic is picked in the
 * edit screen.
 */
public class ShopListingListScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;

    private final List<List<String>> rows;
    private int scroll = 0;
    private EditBox newBox;

    public ShopListingListScreen(List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.cosmetic_shop"), UI_W, UI_H, null);
        this.rows = rows;
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.core.shop_listing.subtitle", rows.size());
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            List<String> row = rows.get(i);
            final String id = row.get(0);
            // Row is [id, type, productSummary, price, enabled, category, limit]. The right-hand summary shows the
            // product (a cosmetic id, key:crate, or set(n)) and the price.
            String product = at(row, 2, "");
            String price = at(row, 3, "0");
            boolean enabled = Boolean.parseBoolean(at(row, 4, "true"));
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            int delW = 52;
            int delX = rowControlRight() - delW;
            String right = tr("gui.dmz_ragnarok.core.shop_listing.row_summary", product, price);
            rowBtn(14, ry, delX - 4 - 14, GuiTheme.ROW_HEIGHT, Component.literal(id),
                    () -> EditorScreens.act("cosmetic_shop", "open", id))
                    .color(enabled ? 0xFFF6E27A : 0xFF7A7A5A)
                    .right(Component.literal(right), 0xFFB0B0B0);
            btn(delX, ry, delW, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.delete"),
                    () -> EditorScreens.act("cosmetic_shop", "delete", id));
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll, v ->
        {
            scroll = v;
            rebuildWidgets();
        });

        newBox = field(14, UI_H - 24, 150, "");
        newBox.setHint(Component.translatable("gui.dmz_ragnarok.core.shop_listing.new_hint"));
        newBox.setMaxLength(64);
        btn(168, footerY(), 56, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.new"), () ->
        {
            String v = newBox.getValue().trim();
            if (!v.isBlank())
                EditorScreens.act("cosmetic_shop", "new", v);
        });
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private static String at(List<String> row, int index, String fallback)
    {
        return row.size() > index ? row.get(index) : fallback;
    }
}
