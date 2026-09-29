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
 * pipeline) into the five unified creative tabs. Those 232 placeholders were removed: they did nothing in-world
 * and only bloated the mod and the creative menu. The 232 retired ids are ignored on world load by
 * {@link net.shurui.shuruisutilities.ragnarok.NamespaceRemap} (RETIRED_ITEMS + mapping.ignore), so existing
 * saves that still name one load clean with no missing-registry prompt. Every BLOCK and block item was kept
 * (they live in their own registers, not here).
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

    // The five per-tab lists are kept so ContentTabs' loops stay unchanged. Only BLOCKS_MISC holds a survivor
    // (ss_ticket); the other four are empty now and are populated only by the sibling-addon tab injectors.
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

    static {
        // The ocarina is the racial ability's instrument, handed out by it and taken back when the song ends, so it
        // is registered but NOT listed in a creative tab: a second, inert copy in the menu is exactly the
        // placeholder this replaced.
        reg("ocarina_of_wind");

        // The raid / dungeon-floor ticket (display name "Ragnarok Ticket"). Shown in the blocks_misc tab.
        BLOCKS_MISC.add(reg("ss_ticket"));
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
