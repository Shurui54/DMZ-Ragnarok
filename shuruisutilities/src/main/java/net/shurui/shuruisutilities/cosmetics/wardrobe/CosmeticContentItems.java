package net.shurui.shuruisutilities.cosmetics.wardrobe;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The bundled Halloween 2026 cosmetic art, as plain registered items.
 *
 * <p>ONE item per cosmetic, pet icon, mount icon and crate prop. They carry NO behaviour: each is a plain
 * {@link Item} whose only job is to own a baked model and a texture so an icon can be drawn in the wardrobe,
 * the editor's model dropdown and (once M4 rendering lands) on the player. They are obtained through the
 * wardrobe and the catalogue, never as loot or a recipe, so they are deliberately kept out of every creative
 * tab.
 *
 * <h2>Registration is UNCONDITIONAL</h2>
 * {@link #ITEMS} is bound to the mod bus from {@code ShuruisUtilities} regardless of any key tier or the
 * Cosmetics module switch, because a registry must be identical on the client and the server or the two
 * cannot connect. What a key or the switchboard gates is whether a cosmetic may be WORN or SPAWNED, decided
 * later against the catalogue, never whether the item exists.
 *
 * <p>Art provenance and the bundle each id came from are recorded in {@code CosmeticHalloweenDefaults}, which
 * seeds one catalogue entry per wearable, pet and mount id below. The two crate props are registered here but
 * are NOT catalogue entries: they are art held ready for the cosmetic crate feature.
 */
public final class CosmeticContentItems
{
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    private static RegistryObject<Item> reg(String name)
    {
        return ITEMS.register(name, () -> new Item(new Item.Properties()));
    }

    /**
     * The ONE tradeable token item, shared by every cosmetic. The copy it stands for is in its NBT, so there is one
     * registered item rather than one per cosmetic. Registered unconditionally with the rest: the registry must
     * match on both sides, and whether a token may be minted or moved is decided later against the catalogue and
     * the ledger, never by the item's existence. See {@link CosmeticTokenItem} and {@link CosmeticTransferGate}.
     */
    public static final RegistryObject<Item> COSMETIC_TOKEN = ITEMS.register("cosmetic_token", CosmeticTokenItem::new);

    // Wearables: head, back, accessory
    public static final RegistryObject<Item> HW_COFFIN_HAT = reg("hw_coffin_hat");
    public static final RegistryObject<Item> HW_PHANTOM_HAT = reg("hw_phantom_hat");
    public static final RegistryObject<Item> HW_PUMPKIN_HAT = reg("hw_pumpkin_hat");
    public static final RegistryObject<Item> HW_COFFIN_BACKWEAR = reg("hw_coffin_backwear");
    public static final RegistryObject<Item> HW_PHANTOM_WINGS = reg("hw_phantom_wings");
    public static final RegistryObject<Item> HW_PUMPKIN_BAG = reg("hw_pumpkin_bag");
    public static final RegistryObject<Item> HW_PHANTOM_SCYTHE = reg("hw_phantom_scythe");
    public static final RegistryObject<Item> HW_PUMPKIN_SWORD = reg("hw_pumpkin_sword");
    public static final RegistryObject<Item> HW_SPIRIT_WAND = reg("hw_spirit_wand");
    public static final RegistryObject<Item> HW_BAT_HAT = reg("hw_bat_hat");
    public static final RegistryObject<Item> HW_WITCH_CAT_HAT = reg("hw_witch_cat_hat");
    public static final RegistryObject<Item> HW_PUMPKIN_HEAD = reg("hw_pumpkin_head");
    public static final RegistryObject<Item> HW_BAT_COAT = reg("hw_bat_coat");
    public static final RegistryObject<Item> HW_PUMPKIN_WEAR = reg("hw_pumpkin_wear");
    public static final RegistryObject<Item> HW_WITCH_CAT = reg("hw_witch_cat");
    public static final RegistryObject<Item> HW_BAT_CANDY_STAFF = reg("hw_bat_candy_staff");
    public static final RegistryObject<Item> HW_PUMPKIN_CANDY = reg("hw_pumpkin_candy");
    public static final RegistryObject<Item> HW_WITCH_BROOM = reg("hw_witch_broom");
    public static final RegistryObject<Item> HW_CANDY_PUMPKIN_BERET = reg("hw_candy_pumpkin_beret");
    public static final RegistryObject<Item> HW_SPOOKY_PUMPKIN_HAT = reg("hw_spooky_pumpkin_hat");
    public static final RegistryObject<Item> HW_WITCH_HAT = reg("hw_witch_hat");
    public static final RegistryObject<Item> HW_CANDY_PUMPKIN_BACKPACK = reg("hw_candy_pumpkin_backpack");
    public static final RegistryObject<Item> HW_SPOOKY_PUMPKIN_WINGS = reg("hw_spooky_pumpkin_wings");
    public static final RegistryObject<Item> HW_WITCH_CAULDRON = reg("hw_witch_cauldron");
    public static final RegistryObject<Item> HW_CANDY_PUMPKIN_BASKET = reg("hw_candy_pumpkin_basket");
    public static final RegistryObject<Item> HW_CANDY_PUMPKIN_BALLOON = reg("hw_candy_pumpkin_balloon");
    public static final RegistryObject<Item> HW_SPOOKY_PUMPKIN_BALLOON = reg("hw_spooky_pumpkin_balloon");
    public static final RegistryObject<Item> HW_GHOST_BALLOON = reg("hw_ghost_balloon");
    public static final RegistryObject<Item> HW_SPOOKY_PUMPKIN_STAFF = reg("hw_spooky_pumpkin_staff");
    public static final RegistryObject<Item> HW_SPOOKY_WITCH_BROOM = reg("hw_spooky_witch_broom");

    // Pet icons
    public static final RegistryObject<Item> HW_PET_DEVIL = reg("hw_pet_devil");
    public static final RegistryObject<Item> HW_PET_FRANK = reg("hw_pet_frank");
    public static final RegistryObject<Item> HW_PET_MUMMY = reg("hw_pet_mummy");
    public static final RegistryObject<Item> HW_PET_SCARECROW = reg("hw_pet_scarecrow");

    // Mount icons
    public static final RegistryObject<Item> HW_MOUNT_BROOMSTICK = reg("hw_mount_broomstick");
    public static final RegistryObject<Item> HW_MOUNT_DEADHORSE = reg("hw_mount_deadhorse");
    public static final RegistryObject<Item> HW_MOUNT_GHOSTSHIP = reg("hw_mount_ghostship");
    public static final RegistryObject<Item> HW_MOUNT_SKELEPTOR = reg("hw_mount_skeleptor");
    public static final RegistryObject<Item> HW_MOUNT_WATCHER = reg("hw_mount_watcher");
    public static final RegistryObject<Item> HW_MOUNT_PUMPKIN_HOUND = reg("hw_mount_pumpkin_hound");
    public static final RegistryObject<Item> HW_MOUNT_PUMPKIN_SPIDER = reg("hw_mount_pumpkin_spider");

    // Crate props (not catalogue entries)
    public static final RegistryObject<Item> HW_CRATE_CHEST = reg("hw_crate_chest");
    public static final RegistryObject<Item> HW_CRATE_KEY = reg("hw_crate_key");

    // Triggered animation icons. One per bundled Halloween animation, seeded as catalogue entries in
    // CosmeticAnimationDefaults. Plain icon items, drawn in the wardrobe and picker; the animation itself is a
    // client FX, not a worn model. Their models parent a themed vanilla item, so no new texture is needed.
    public static final RegistryObject<Item> FX_CADAVERCURSE = reg("fx_cadavercurse");
    public static final RegistryObject<Item> FX_HELLGATE = reg("fx_hellgate");
    public static final RegistryObject<Item> FX_MIDNIGHTLORD = reg("fx_midnightlord");
    public static final RegistryObject<Item> FX_RAGDOLLPOSSESSION = reg("fx_ragdollpossession");
    public static final RegistryObject<Item> FX_RISENDREAD = reg("fx_risendread");

    private CosmeticContentItems()
    {
    }
}
