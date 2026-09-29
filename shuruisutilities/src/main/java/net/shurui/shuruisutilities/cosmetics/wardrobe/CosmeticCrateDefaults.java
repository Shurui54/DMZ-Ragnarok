package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.List;

/**
 * The bundled Halloween 2026 cosmetic CRATE, as a SEED record.
 *
 * <p>SEED CONTENT, exactly like {@link CosmeticHalloweenDefaults} and {@link CosmeticEffectDefaults}:
 * {@link CosmeticCatalog#seedDefaults()} puts this {@link CosmeticCrate} into a catalogue that has never held a
 * crate of this name, once, guarded PER crate name on the {@code crate:} stamp namespace. From that moment it is
 * an ordinary admin-owned record, editable and deletable in game and synced across shards, and an admin who
 * deletes it leaves a tombstone so it does not grow back. Seeded at stamp ZERO so two shards that both seed
 * converge silently. Changing a line here does NOT change a live server's copy.
 *
 * <h2>Bound by NAME to the world crate</h2>
 * {@link CosmeticCrate#crateName} is {@code halloween}, which must equal the {@code Crate.name} the SU crate
 * system seeds (see {@code CrateManager.seedDefaults}) so the physical Halloween crate block opens as a cosmetic
 * roll. Both halves seed the SAME lowercase name deterministically on every shard, which is how a network gets
 * the crate everywhere even though {@code crates.json} does not travel between shards.
 *
 * <h2>The odds shipped</h2>
 * Thirty wearables (heads, backs and accessories) at weight 100, with the six showpieces (the wings, the cauldron
 * and the three balloons) at weight 40 so they land less often, plus the four pets at weight 15 because the PET
 * slot is live. Mounts are left out because {@link CosmeticSlot#MOUNT} is disabled: a mount would roll but could
 * not be summoned yet, so adding them is a one-line data edit for whoever turns the mount slot on. Super is 10%
 * and Magic is 2%, applied only to a cosmetic that allows them (the wearables do, the pets do not), and a Magic
 * roll draws from {@code pool_hollow}, the shipped Halloween effect pool.
 */
public final class CosmeticCrateDefaults
{
    /** The crate name both halves of the seed agree on. Lowercase so the binding lookup matches exactly. */
    public static final String CRATE_NAME = "halloween";

    /** Shown on the odds screen and the editor. */
    public static final String DISPLAY_NAME = "Halloween Crate";

    /** The shipped Halloween Magic pool a Magic roll draws from. See {@link CosmeticEffectDefaults}. */
    public static final String MAGIC_POOL = CosmeticHalloweenDefaults.MAGIC_POOL;

    /** Common weight for a wearable. */
    private static final int W_COMMON = 100;

    /** Rarer weight for the animated showpieces. */
    private static final int W_SHOWPIECE = 40;

    /** Pet weight: low, because a pet is a bonus rather than the point of a Halloween crate. */
    private static final int W_PET = 15;

    private CosmeticCrateDefaults()
    {
    }

    /** Fresh instances every call. The catalogue takes ownership of what it is handed. */
    public static List<CosmeticCrate> crates()
    {
        List<CosmeticCrate> out = new ArrayList<>();
        out.add(halloween());
        return out;
    }

    private static CosmeticCrate halloween()
    {
        CosmeticCrate c = new CosmeticCrate(CRATE_NAME);
        c.displayName = DISPLAY_NAME;
        c.enabled = true;
        c.superPercent = 10;
        c.magicPercent = 2;
        c.magicPoolId = MAGIC_POOL;

        // Heads
        c.entries.add(new CosmeticCrate.Entry("hw_coffin_hat", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_phantom_hat", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_pumpkin_hat", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_bat_hat", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_witch_cat_hat", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_pumpkin_head", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_candy_pumpkin_beret", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_spooky_pumpkin_hat", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_witch_hat", W_COMMON));

        // Backs
        c.entries.add(new CosmeticCrate.Entry("hw_coffin_backwear", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_phantom_wings", W_SHOWPIECE));
        c.entries.add(new CosmeticCrate.Entry("hw_pumpkin_bag", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_bat_coat", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_pumpkin_wear", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_witch_cat", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_candy_pumpkin_backpack", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_spooky_pumpkin_wings", W_SHOWPIECE));
        c.entries.add(new CosmeticCrate.Entry("hw_witch_cauldron", W_SHOWPIECE));

        // Accessories
        c.entries.add(new CosmeticCrate.Entry("hw_phantom_scythe", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_pumpkin_sword", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_spirit_wand", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_bat_candy_staff", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_pumpkin_candy", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_witch_broom", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_candy_pumpkin_basket", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_candy_pumpkin_balloon", W_SHOWPIECE));
        c.entries.add(new CosmeticCrate.Entry("hw_spooky_pumpkin_balloon", W_SHOWPIECE));
        c.entries.add(new CosmeticCrate.Entry("hw_ghost_balloon", W_SHOWPIECE));
        c.entries.add(new CosmeticCrate.Entry("hw_spooky_pumpkin_staff", W_COMMON));
        c.entries.add(new CosmeticCrate.Entry("hw_spooky_witch_broom", W_COMMON));

        // Pets (Normal only; a Super/Magic roll cannot upgrade them because they do not allow it).
        c.entries.add(new CosmeticCrate.Entry("hw_pet_devil", W_PET));
        c.entries.add(new CosmeticCrate.Entry("hw_pet_frank", W_PET));
        c.entries.add(new CosmeticCrate.Entry("hw_pet_mummy", W_PET));
        c.entries.add(new CosmeticCrate.Entry("hw_pet_scarecrow", W_PET));

        return c.normalise();
    }
}
