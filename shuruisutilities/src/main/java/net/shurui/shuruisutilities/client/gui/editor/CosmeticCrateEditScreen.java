package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticWire;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * One cosmetic crate record: the odds it rolls, and which cosmetics it may drop.
 *
 * <p>{@code meta = [crateName, displayName, enabled, superPercent, magicPercent, magicPoolId, poolCount, pools...,
 * cosmeticCount, cosmetics...]}. The two tail lists feed the pool dropdown and the add-entry dropdown with no
 * extra round trip. Rows are one per weighted entry: {@code [e, catalogId, weight]}.
 *
 * <p>The crate NAME is not editable, exactly as a cosmetic id is not: it binds the record to the world crate of
 * that name, so a rename is a delete plus a new entry.
 */
public class CosmeticCrateEditScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final String[] TABS = {
            "gui.dmz_ragnarok.core.cosmetic_crate.tab_odds",
            "gui.dmz_ragnarok.core.cosmetic_crate.tab_entries" };

    private final List<String> meta;
    private final List<List<String>> rows;
    private final List<String> poolIds = new ArrayList<>();
    private final List<String> cosmeticIds = new ArrayList<>();
    private final List<String> poolLabels = new ArrayList<>();
    private final List<String> cosmeticLabels = new ArrayList<>();

    private int tab;

    private String displayName;
    private boolean enabled;
    private String superPercent;
    private String magicPercent;
    private String magicPoolId;

    private String addEntryId = "";
    private EditBox addWeightBox;

    public CosmeticCrateEditScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.cosmetic_crate.edit_title"), UI_W, UI_H, null);
        this.meta = meta;
        this.rows = rows;
        this.displayName = at(1, "");
        this.enabled = Boolean.parseBoolean(at(2, "true"));
        this.superPercent = at(3, "0");
        this.magicPercent = at(4, "0");
        this.magicPoolId = at(5, "");
        int i = 6;
        int poolCount = parseI(at(i++, "0"), 0);
        for (int n = 0; n < poolCount && i < meta.size(); n++)
            poolIds.add(meta.get(i++));
        int cosmeticCount = parseI(at(i++, "0"), 0);
        for (int n = 0; n < cosmeticCount && i < meta.size(); n++)
            cosmeticIds.add(meta.get(i++));
        // The two parallel display-name lists the server appends at the tail (same counts, same order), so the
        // dropdowns show friendly names. Read defensively: an older server that did not send them leaves the
        // label lists empty and the dropdowns fall back to ids.
        int poolNameCount = parseI(at(i++, "0"), 0);
        for (int n = 0; n < poolNameCount && i < meta.size(); n++)
            poolLabels.add(meta.get(i++));
        int cosmeticNameCount = parseI(at(i++, "0"), 0);
        for (int n = 0; n < cosmeticNameCount && i < meta.size(); n++)
            cosmeticLabels.add(meta.get(i++));
    }

    /** The display name for a cosmetic id, from the parallel list, or the id when there is no name for it. */
    private String cosmeticLabel(String catalogId)
    {
        int idx = cosmeticIds.indexOf(catalogId);
        if (idx >= 0 && idx < cosmeticLabels.size() && !cosmeticLabels.get(idx).isBlank())
            return cosmeticLabels.get(idx);
        return catalogId;
    }

    private String at(int index, String fallback)
    {
        return meta.size() > index ? meta.get(index) : fallback;
    }

    private String crateName()
    {
        return at(0, "");
    }

    private List<List<String>> entryRows()
    {
        List<List<String>> out = new ArrayList<>();
        for (List<String> row : rows)
            if (!row.isEmpty() && CosmeticWire.CRATE_ROW_ENTRY.equals(row.get(0)))
                out.add(row);
        return out;
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        rowY = buildNamedTabHeader(crateName(), trAll(TABS), tab, i ->
        {
            applyFields();
            tab = i;
            rebuildWidgets();
        });

        if (tab == 0)
            odds();
        else
            entries();

        btn(UI_W / 2 - 104, footerY(), 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.btn.save"), this::save);
        btn(UI_W / 2 + 4, footerY(), 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                () -> EditorScreens.act("cosmetic_crates", "back"));
    }

    private void odds()
    {
        tf(tr("gui.dmz_ragnarok.core.cosmetic_crate.field_name"), displayName, v -> displayName = v);
        bf(tr("gui.dmz_ragnarok.core.cosmetic_crate.field_enabled"), enabled, () -> enabled = !enabled);
        tf(tr("gui.dmz_ragnarok.core.cosmetic_crate.field_super"), superPercent, v -> superPercent = v);
        tip(tr("gui.dmz_ragnarok.core.cosmetic_crate.tip_super"));
        tf(tr("gui.dmz_ragnarok.core.cosmetic_crate.field_magic"), magicPercent, v -> magicPercent = v);
        tip(tr("gui.dmz_ragnarok.core.cosmetic_crate.tip_magic"));
        dfNamed(tr("gui.dmz_ragnarok.core.cosmetic_crate.field_pool"), poolIds, poolLabels, magicPoolId,
                v -> magicPoolId = v, tr("gui.dmz_ragnarok.core.cosmetics.no_pools"));
        tip(tr("gui.dmz_ragnarok.core.cosmetic_crate.tip_pool"));
    }

    private void entries()
    {
        List<List<String>> list = entryRows();
        for (List<String> row : list)
        {
            final String catalogId = row.size() > 1 ? row.get(1) : "";
            String weight = row.size() > 2 ? row.get(2) : "0";
            int delW = 20;
            int delX = rowControlRight() - delW;
            rowBtn(14, rowY, delX - 4 - 14, GuiTheme.ROW_HEIGHT, Component.literal(cosmeticLabel(catalogId)), () ->
            {
            })
                    .color(0xFFF6E27A)
                    .right(Component.literal(tr("gui.dmz_ragnarok.core.cosmetic_crate.weight", weight)), 0xFFB0B0B0);
            btn(delX, rowY, delW, GuiTheme.ROW_HEIGHT, Component.literal("X"),
                    () -> EditorScreens.act("cosmetic_crates", "delentry", crateName(), catalogId));
            rowY += GuiTheme.ROW_HEIGHT;
        }
        rowY += 6;
        label(tr("gui.dmz_ragnarok.core.cosmetic_crate.add_entry"), 14, rowY + 2, 0xFFB0B0B0);
        // A searchable dropdown of the cosmetic ids the server sent, plus a weight field, plus Add. setentry adds
        // a new entry or retunes an existing one KEEPING its position, so re-adding an id changes its weight.
        dfNamedAt(fieldColX(), rowY, fieldColW() - 74, cosmeticIds, cosmeticLabels, addEntryId, v -> addEntryId = v);
        addWeightBox = rawField(fieldColX() + fieldColW() - 70, rowY + 1, 34, "10", v ->
        {
        });
        addWeightBox.setHint(Component.translatable("gui.dmz_ragnarok.core.cosmetic_crate.weight_hint"));
        addWeightBox.setMaxLength(6);
        btn(fieldColX() + fieldColW() - 32, rowY, 32, GuiTheme.BUTTON_HEIGHT,
                Component.translatable("gui.dmz_ragnarok.core.btn.add"), () ->
                {
                    applyFields();
                    String weight = addWeightBox == null ? "10" : addWeightBox.getValue().trim();
                    if (!addEntryId.isBlank())
                        EditorScreens.act("cosmetic_crates", "setentry", crateName(), addEntryId,
                                weight.isBlank() ? "0" : weight);
                });
    }

    private void save()
    {
        applyFields();
        List<String> args = new ArrayList<>();
        args.add(crateName());
        args.add(displayName);
        args.add(Boolean.toString(enabled));
        args.add(superPercent);
        args.add(magicPercent);
        args.add(magicPoolId == null ? "" : magicPoolId);
        EditorScreens.act("cosmetic_crates", "save", args);
    }
}
