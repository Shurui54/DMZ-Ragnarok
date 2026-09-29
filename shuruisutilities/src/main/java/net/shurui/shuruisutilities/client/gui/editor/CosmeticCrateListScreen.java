package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.DmzDropdown;
import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * The cosmetic crate catalogue: every crate record an admin has authored.
 *
 * <p>Rows are {@code [crateName, displayName, enabled, entryCount, superPercent, magicPercent]}. A cosmetic crate
 * binds BY NAME to a crate-system crate, so making one used to require typing the name of a crate that already
 * existed elsewhere, with nothing guiding it. The footer now offers both halves directly: pick an EXISTING crate
 * from the dropdown to bind a cosmetic crate to it, or type a name and display name to CREATE a fresh crate (the
 * crate-system crate, keyed to the Halloween chest, and its cosmetic crate) in one step.
 *
 * <p>{@code meta = [availableCount, availableCrateNames...]}: the crate-system crates with no cosmetic crate yet.
 */
public class CosmeticCrateListScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;
    // Room for the three footer control rows (existing-crate dropdown, name/display fields, Create/Menu buttons).
    private static final int RESERVE_BELOW = 58;

    private final List<List<String>> rows;
    private final List<String> availableCrates = new ArrayList<>();
    private int scroll = 0;
    private EditBox nameBox;
    private EditBox displayBox;
    private DmzDropdown existingDrop;

    public CosmeticCrateListScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.cosmetic_crates"), UI_W, UI_H, null);
        this.rows = rows;
        int i = 0;
        int count = parseI(at(meta, i++, "0"), 0);
        for (int n = 0; n < count && i < meta.size(); n++)
            availableCrates.add(meta.get(i++));
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.core.cosmetic_crate.subtitle", rows.size());
        int maxRows = rowsThatFit(LIST_TOP, ROW_H, RESERVE_BELOW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - maxRows)));
        int end = Math.min(rows.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            List<String> row = rows.get(i);
            final String name = row.get(0);
            String display = at(row, 1, name);
            boolean enabled = Boolean.parseBoolean(at(row, 2, "true"));
            String count = at(row, 3, "0");
            String sup = at(row, 4, "0");
            String magic = at(row, 5, "0");
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            int delW = 52;
            int delX = rowControlRight() - delW;
            String right = tr("gui.dmz_ragnarok.core.cosmetic_crate.row_summary", count, sup, magic);
            rowBtn(14, ry, delX - 4 - 14, GuiTheme.ROW_HEIGHT, Component.literal(display),
                    () -> EditorScreens.act("cosmetic_crates", "open", name))
                    .color(enabled ? 0xFFF6E27A : 0xFF7A7A5A)
                    .right(Component.literal(right), 0xFFB0B0B0);
            btn(delX, ry, delW, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.delete"),
                    () -> EditorScreens.act("cosmetic_crates", "delete", name));
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, rows.size(), scroll, v ->
        {
            scroll = v;
            rebuildWidgets();
        });

        // Row 1: bind a cosmetic crate to an EXISTING crate-system crate.
        int row1 = footerY() - 36;
        if (availableCrates.isEmpty())
        {
            label(tr("gui.dmz_ragnarok.core.cosmetic_crate.no_available"), 14, row1 + 3, 0xFF808080);
        }
        else
        {
            List<Component> opts = new ArrayList<>();
            for (String n : availableCrates)
                opts.add(Component.literal(n));
            existingDrop = dropdown(14, row1, 150, opts, 0).searchable();
            btn(170, row1, 116, GuiTheme.BUTTON_HEIGHT,
                    Component.translatable("gui.dmz_ragnarok.core.cosmetic_crate.bind_existing"), () ->
                    {
                        int idx = existingDrop.getIndex();
                        if (idx >= 0 && idx < availableCrates.size())
                            EditorScreens.act("cosmetic_crates", "new", availableCrates.get(idx));
                    });
        }

        // Row 2: name + display for a brand new crate.
        int row2 = footerY() - 18;
        nameBox = field(14, row2, 132, "");
        nameBox.setHint(Component.translatable("gui.dmz_ragnarok.core.cosmetic_crate.new_hint"));
        nameBox.setMaxLength(64);
        displayBox = field(150, row2, 136, "");
        displayBox.setHint(Component.translatable("gui.dmz_ragnarok.core.cosmetic_crate.display_hint"));
        displayBox.setMaxLength(64);

        // Footer: Create (from the two fields above) on the left, Menu on the right.
        btn(14, footerY(), 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.cosmetic_crate.create"), () ->
                {
                    String name = nameBox.getValue().trim();
                    if (!name.isBlank())
                        EditorScreens.act("cosmetic_crates", "newfull", name, displayBox.getValue().trim());
                });
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private static String at(List<String> row, int index, String fallback)
    {
        return row.size() > index ? row.get(index) : fallback;
    }

    private static int parseI(String s, int def)
    {
        try
        {
            return Integer.parseInt(s.trim());
        }
        catch (Exception e)
        {
            return def;
        }
    }
}
