package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a {@link CosmeticShopListing} sells: a single cosmetic, a crate key, or a bundle of cosmetics.
 *
 * <h2>One screen, three products</h2>
 * The shop began as a cosmetic-only offer. Rather than a second listing store for keys and a third for bundles, one
 * listing record carries a type, so the same editor authors all three and the same shop screen sells all three.
 * That keeps the sale window, the price, the category and the per-player terms in one place per product.
 *
 * <h2>The key, never the ordinal</h2>
 * Same discipline as {@link CosmeticQuality} and {@link CosmeticSlot}: every persisted and wire form is the stable
 * lowercase {@link #key}, so declaration order carries no meaning and adding a constant can never shift an existing
 * listing onto a different type. An absent or unknown key reads as {@link #COSMETIC}, which is what every listing
 * authored before this milestone is.
 */
public enum CosmeticListingType
{
    /** Sells one cosmetic, minted as a bound Normal copy on purchase. The original and default behaviour. */
    COSMETIC("cosmetic"),

    /** Sells a crate key: the exact stamped key {@code /crate givekey} hands out, for the crate named on the listing. */
    KEY("key"),

    /** Sells a bundle of cosmetics for one price. Buying grants every member (see the listing's re-grant toggle). */
    SET("set");

    /** Stable lowercase key. PERSISTED on every listing, so never rename these. */
    public final String key;

    CosmeticListingType(String key)
    {
        this.key = key;
    }

    private static final CosmeticListingType[] VALUES = values();

    /** Never throws: an unknown or absent key reads as COSMETIC rather than failing a load. */
    public static CosmeticListingType byKey(String key)
    {
        if (key != null)
        {
            String lower = key.trim().toLowerCase(Locale.ROOT);
            for (CosmeticListingType t : VALUES)
                if (t.key.equals(lower))
                    return t;
        }
        return COSMETIC;
    }

    public String langKey()
    {
        return "gui.dmz_ragnarok.core.shop_listing.type_" + key;
    }

    public static List<String> keys()
    {
        List<String> out = new ArrayList<>(VALUES.length);
        for (CosmeticListingType t : VALUES)
            out.add(t.key);
        return out;
    }
}
