package net.shurui.shuruisutilities.cosmetics.wardrobe;

/**
 * Wire constants shared by the private cosmetic crate and shop editors (their servers live in the Ragnarok Key since
 * S17a) and the client screens that stay in core ({@code CrateOddsScreen}, {@code CosmeticCrateEditScreen},
 * {@code ShopListingEditScreen}). Each is a contract with one screen: never change a value.
 */
public final class CosmeticWire
{
    private CosmeticWire() {}

    /** Row marker on the crate odds screen: a cosmetic drop line. */
    public static final String CRATE_ODDS_COSMETIC = "c";

    /** Row marker on the crate odds screen: a Magic-pool effect line. */
    public static final String CRATE_ODDS_EFFECT = "e";

    /** Row marker in the cosmetic crate editor: one weighted cosmetic entry. */
    public static final String CRATE_ROW_ENTRY = "e";

    /** How many fixed meta fields the shop listing editor sends before the counted dropdown tails. */
    public static final int SHOP_META_FIXED = 17;
}
