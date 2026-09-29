package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticListingType;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticWire;

import net.minecraft.network.chat.Component;

/**
 * One shop listing: WHAT it sells (a cosmetic, a crate key, or a bundle), the price in Shards, and the terms.
 *
 * <p>{@code meta = [id, type, catalogId, crateName, quantity, setMembersCsv, setRegrantOwned, displayName,
 * priceShards, enabled, startEpoch, endEpoch, category, sortOrder, limitPerPlayer, featured, descriptionOverride]},
 * then a counted cosmetic-id list, a counted cosmetic display-name list, and a counted crate-name list feeding the
 * three dropdowns. The listing id is not editable; a rename is a delete plus a new entry.
 *
 * <p>Every product's fields are shown regardless of the chosen type, because a dropdown change does not re-lay the
 * screen; the operator fills the fields that apply to the type and the server ignores the rest. Quality is
 * deliberately absent: a shop purchase is always Normal, decided server side with no parameter.
 */
public class ShopListingEditScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final String[] TABS = {
            "gui.dmz_ragnarok.core.shop_listing.tab_product",
            "gui.dmz_ragnarok.core.shop_listing.tab_pricing",
            "gui.dmz_ragnarok.core.shop_listing.tab_terms" };

    private final List<String> meta;
    private final List<String> cosmeticIds = new ArrayList<>();
    private final List<String> cosmeticLabels = new ArrayList<>();
    private final List<String> crateNames = new ArrayList<>();

    private int tab;

    private String type;
    private String catalogId;
    private String crateName;
    private String quantity;
    private final List<String> setMembers = new ArrayList<>();
    private boolean setRegrantOwned;
    private String displayName;
    private String price;
    private boolean enabled;
    private String startEpoch;
    private String endEpoch;
    private String category;
    private String sortOrder;
    private String limit;
    private boolean featured;
    private String description;

    public ShopListingEditScreen(List<String> meta)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.shop_listing.edit_title"), UI_W, UI_H, null);
        this.meta = meta;
        this.type = at(1, CosmeticListingType.COSMETIC.key);
        this.catalogId = at(2, "");
        this.crateName = at(3, "");
        this.quantity = at(4, "1");
        for (String m : at(5, "").split("[,\\s]+"))
            if (!m.isBlank())
                setMembers.add(m.trim());
        this.setRegrantOwned = Boolean.parseBoolean(at(6, "true"));
        this.displayName = at(7, "");
        this.price = at(8, "0");
        this.enabled = Boolean.parseBoolean(at(9, "true"));
        this.startEpoch = at(10, "0");
        this.endEpoch = at(11, "0");
        this.category = at(12, "");
        this.sortOrder = at(13, "0");
        this.limit = at(14, "0");
        this.featured = Boolean.parseBoolean(at(15, "false"));
        this.description = at(16, "");
        // Three counted tails after the fixed fields: cosmetic ids, their labels, then crate names.
        int i = CosmeticWire.SHOP_META_FIXED;
        int idCount = parseCount(at(i++, "0"));
        for (int n = 0; n < idCount && i < meta.size(); n++)
            cosmeticIds.add(meta.get(i++));
        int nameCount = parseCount(at(i++, "0"));
        for (int n = 0; n < nameCount && i < meta.size(); n++)
            cosmeticLabels.add(meta.get(i++));
        int crateCount = parseCount(at(i++, "0"));
        for (int n = 0; n < crateCount && i < meta.size(); n++)
            crateNames.add(meta.get(i++));
    }

    private static int parseCount(String s)
    {
        try
        {
            return Integer.parseInt(s.trim());
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    private String at(int index, String fallback)
    {
        return meta.size() > index ? meta.get(index) : fallback;
    }

    private String id()
    {
        return at(0, "");
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        rowY = buildNamedTabHeader(id(), trAll(TABS), tab, i ->
        {
            applyFields();
            tab = i;
            rebuildWidgets();
        });

        if (tab == 0)
            product();
        else if (tab == 1)
            pricing();
        else
            terms();

        btn(UI_W / 2 - 104, footerY(), 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.btn.save"), this::save);
        btn(UI_W / 2 + 4, footerY(), 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                () -> EditorScreens.act("cosmetic_shop", "back"));
    }

    private void product()
    {
        List<String> typeValues = CosmeticListingType.keys();
        List<String> typeLabels = new ArrayList<>();
        for (CosmeticListingType t : CosmeticListingType.values())
            typeLabels.add(tr(t.langKey()));
        dfNamed(tr("gui.dmz_ragnarok.core.shop_listing.field_type"), typeValues, typeLabels, type,
                v -> type = v == null || v.isBlank() ? CosmeticListingType.COSMETIC.key : v, "");
        tip(tr("gui.dmz_ragnarok.core.shop_listing.tip_type"));
        // COSMETIC
        dfNamed(tr("gui.dmz_ragnarok.core.shop_listing.field_cosmetic"), cosmeticIds, cosmeticLabels, catalogId,
                v -> catalogId = v, tr("gui.dmz_ragnarok.core.cosmetics.no_cosmetics"));
        // KEY
        dfNamed(tr("gui.dmz_ragnarok.core.shop_listing.field_crate"), crateNames, crateNames, crateName,
                v -> crateName = v, tr("gui.dmz_ragnarok.core.shop_listing.no_crates"));
        tf(tr("gui.dmz_ragnarok.core.shop_listing.field_quantity"), quantity, v -> quantity = v);
        tip(tr("gui.dmz_ragnarok.core.shop_listing.tip_quantity"));
        // SET
        dfMulti(tr("gui.dmz_ragnarok.core.shop_listing.field_set_members"), cosmeticIds, setMembers,
                v -> { setMembers.clear(); setMembers.addAll(v); });
        tip(tr("gui.dmz_ragnarok.core.shop_listing.tip_set_members"));
        bf(tr("gui.dmz_ragnarok.core.shop_listing.field_set_regrant"), setRegrantOwned,
                () -> setRegrantOwned = !setRegrantOwned);
        tip(tr("gui.dmz_ragnarok.core.shop_listing.tip_set_regrant"));
        tf(tr("gui.dmz_ragnarok.core.shop_listing.field_displayname"), displayName, v -> displayName = v);
        tip(tr("gui.dmz_ragnarok.core.shop_listing.tip_displayname"));
    }

    private void pricing()
    {
        tf(tr("gui.dmz_ragnarok.core.shop_listing.field_price"), price, v -> price = v);
        tip(tr("gui.dmz_ragnarok.core.shop_listing.tip_price"));
        bf(tr("gui.dmz_ragnarok.core.shop_listing.field_enabled"), enabled, () -> enabled = !enabled);
        tf(tr("gui.dmz_ragnarok.core.shop_listing.field_category"), category, v -> category = v);
        bf(tr("gui.dmz_ragnarok.core.shop_listing.field_featured"), featured, () -> featured = !featured);
        tf(tr("gui.dmz_ragnarok.core.shop_listing.field_desc"), description, v -> description = v);
    }

    private void terms()
    {
        tf(tr("gui.dmz_ragnarok.core.shop_listing.field_limit"), limit, v -> limit = v);
        tip(tr("gui.dmz_ragnarok.core.shop_listing.tip_limit"));
        tf(tr("gui.dmz_ragnarok.core.shop_listing.field_sort"), sortOrder, v -> sortOrder = v);
        tf(tr("gui.dmz_ragnarok.core.shop_listing.field_start"), startEpoch, v -> startEpoch = v);
        tip(tr("gui.dmz_ragnarok.core.shop_listing.tip_window"));
        tf(tr("gui.dmz_ragnarok.core.shop_listing.field_end"), endEpoch, v -> endEpoch = v);
    }

    private void save()
    {
        applyFields();
        List<String> args = new ArrayList<>();
        args.add(id());
        args.add(type == null || type.isBlank() ? CosmeticListingType.COSMETIC.key : type);
        args.add(catalogId == null ? "" : catalogId);
        args.add(crateName == null ? "" : crateName);
        args.add(quantity);
        args.add(String.join(",", setMembers));
        args.add(Boolean.toString(setRegrantOwned));
        args.add(displayName == null ? "" : displayName);
        args.add(price);
        args.add(Boolean.toString(enabled));
        args.add(startEpoch);
        args.add(endEpoch);
        args.add(category);
        args.add(sortOrder);
        args.add(limit);
        args.add(Boolean.toString(featured));
        args.add(description);
        EditorScreens.act("cosmetic_shop", "save", args);
    }
}
