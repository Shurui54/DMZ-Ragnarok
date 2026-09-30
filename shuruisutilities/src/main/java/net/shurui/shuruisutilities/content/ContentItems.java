package net.shurui.shuruisutilities.content;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.shuruisutilities.core.ShuruisUtilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The two content items that survive the 2.0 debloat, registered in the dmz_ragnarok namespace.
 *
 * <p>This class used to register 234 plain placeholder items (no behaviour, display names from the lang
 * pipeline) into the five unified creative tabs. 232 of those were removed in the 2.0 debloat: they did nothing
 * in-world and only bloated the mod and the creative menu. In 1.5.1 the nine rupee currency items ({@code r_1}
 * through {@code r_5000}) and the three wallets ({@code wallet}, {@code wallet_2}, {@code wallet_upgrade}) were
 * restored, because they were in active use as a currency set; the other 220 stay retired. The remaining retired
 * ids are ignored on world load by {@link net.shurui.shuruisutilities.ragnarok.NamespaceRemap} (RETIRED_ITEMS +
 * mapping.ignore), so existing saves that still name one load clean with no missing-registry prompt. Every BLOCK
 * and block item was kept (they live in their own registers, not here).
 *
 * <p>The two survivors are real, functional items:
 * <ul>
 *   <li>{@code ocarina_of_wind}, the wind racial's instrument (handed out by the Ragnarok Key's
 *       {@code OcarinaService} and taken back when the song ends), so it is
 *       registered but NOT listed in a creative tab: a second inert copy in the menu is exactly the placeholder
 *       this replaced.</li>
 *   <li>{@code ss_ticket} (display name "Ragnarok Ticket"), the raid / dungeon-floor ticket reused by the guild
 *       raid (CommandGuild / GuildRaid) and the dungeons floor-ticket compat. Its registry id stays
 *       {@code ss_ticket} for data safety; only the display name changed.</li>
 * </ul>
 *
 * <p>Ownership note: these items and the five tabs used to live in the sdu addon. They were moved here so
 * shuruisutilities owns the shared item/tab content; sibling addons still insert their own real items into
 * those tabs (see {@link ContentTabs} and the sibling ModCreativeTabs injectors).
 */
public final class ContentItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    // Lookup by registry name so creative tabs can pick an icon by name (see ContentTabs).
    private static final Map<String, RegistryObject<Item>> BY_NAME = new HashMap<>();

    // The five per-tab lists are kept so ContentTabs' loops stay unchanged. GEMS_SOULS holds the nine rupees and
    // BLOCKS_MISC the ticket plus the three wallets; the other three are empty here and are populated only by the
    // sibling-addon tab injectors.
    public static final List<RegistryObject<Item>> GEMS_SOULS = new ArrayList<>();

    public static final List<RegistryObject<Item>> DRAGON_BALLS = new ArrayList<>();

    public static final List<RegistryObject<Item>> EQUIPMENT = new ArrayList<>();

    public static final List<RegistryObject<Item>> CONSUMABLES = new ArrayList<>();

    public static final List<RegistryObject<Item>> BLOCKS_MISC = new ArrayList<>();

    /**
     * The single reserve event-collectible item ({@code dmz_ragnarok:event_token}). Registered on every key tier
     * (registration must not vary by key), but deliberately NOT added to a static tab list: its creative-tab entry
     * is added conditionally in {@link ContentTabs} gated on {@code ClientGate.feature("events")}, so a keyless
     * client never sees it. Its per-event look comes from NBT (Variant + CustomModelData), so one item serves every
     * event. See {@link net.shurui.shuruisutilities.events.item.EventTokenItem}.
     */
    public static final RegistryObject<Item> EVENT_TOKEN =
            reg("event_token", () -> new net.shurui.shuruisutilities.events.item.EventTokenItem(new Item.Properties()));

    /**
     * The two Halloween inventory loot boxes: right click opens one, granting a random configured reward. Both are
     * {@link net.shurui.shuruisutilities.events.item.LootBoxItem}, told apart by their box type. Like the event
     * token they are registered on every key tier but only work (and only show) where the event feature is present:
     * the roll/grant/announce is the Ragnarok Key's, and their creative-tab entry is gated on
     * {@code ClientGate.feature("events")}. The {@code halloween_pumpkin_bag} loot bag is a DIFFERENT item from the
     * existing {@code hw_pumpkin_bag} wardrobe cosmetic.
     */
    public static final RegistryObject<Item> HALLOWEEN_BOX =
            reg("halloween_box", () -> new net.shurui.shuruisutilities.events.item.LootBoxItem(
                    new Item.Properties(), "halloween_box"));

    public static final RegistryObject<Item> HALLOWEEN_PUMPKIN_BAG =
            reg("halloween_pumpkin_bag", () -> new net.shurui.shuruisutilities.events.item.LootBoxItem(
                    new Item.Properties(), "pumpkin_bag"));

    static {
        // The ocarina is the racial ability's instrument, handed out by it and taken back when the song ends, so it
        // is registered but NOT listed in a creative tab: a second, inert copy in the menu is exactly the
        // placeholder this replaced.
        reg("ocarina_of_wind");

        // The rupee currency items (R 1 through R 5000) and the wallets. Restored in 1.5.1: the 1.5.0 debloat swept
        // them out with the placeholders, but they were in active use as a currency set, so they come back with their
        // original registry ids, models, animated textures and creative-tab placement. Rupees go in the gems_souls
        // tab, wallets in the blocks_misc tab, exactly as before.
        GEMS_SOULS.add(reg("r_1"));
        GEMS_SOULS.add(reg("r_5"));
        GEMS_SOULS.add(reg("r_10"));
        GEMS_SOULS.add(reg("r_20"));
        GEMS_SOULS.add(reg("r_50"));
        GEMS_SOULS.add(reg("r_100"));
        GEMS_SOULS.add(reg("r_500"));
        GEMS_SOULS.add(reg("r_1000"));
        GEMS_SOULS.add(reg("r_5000"));

        // The raid / dungeon-floor ticket (display name "Ragnarok Ticket"). Shown in the blocks_misc tab.
        BLOCKS_MISC.add(reg("ss_ticket"));

        BLOCKS_MISC.add(reg("wallet"));
        BLOCKS_MISC.add(reg("wallet_2"));
        BLOCKS_MISC.add(reg("wallet_upgrade"));
    }

    private static RegistryObject<Item> reg(String name) {
        return reg(name, () -> new Item(new Item.Properties()));
    }

    private static RegistryObject<Item> reg(String name, java.util.function.Supplier<Item> factory) {
        RegistryObject<Item> obj = ITEMS.register(name, factory);
        BY_NAME.put(name, obj);
        return obj;
    }

    public static Item byName(String name) {
        return BY_NAME.get(name).get();
    }

    private ContentItems() {
    }
}
