package net.shurui.shuruisutilities.ragnarok;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.MissingMappingsEvent;

/**
 * Central id-namespace remap for the five-addon merge into a single "dmz_ragnarok" mod.
 *
 * <p>When the five addons collapse into one mod, every registry id that used to live under one of the five old
 * namespaces (sdu, shuruisutilities, shuruis_dmz_dungeons, shuruis_raid_bosses, shuruis_dmz_tournaments) is now
 * registered under "dmz_ragnarok" with the same path. Existing saves still reference the OLD ids. Without a remap,
 * every such block reverts to air, and every stored item and every spawned entity vanishes on world load. This
 * handler rewrites the five PERSISTED registries so those saves keep their content:
 * BLOCKS, ITEMS, ENTITY_TYPES, BLOCK_ENTITY_TYPES and SOUND_EVENTS. The addons also register into MENU_TYPES and
 * CREATIVE_MODE_TAB, but those never enter a world's saved registry mapping list (a menu is opened transiently and a
 * creative tab is a client/creative-screen concern; neither is stored in a save), so they need no remap. This was
 * confirmed at the failing boot: a full namespace change would have listed missing menu/tab ids alongside the missing
 * sound ids had they been persisted, and only sound ids appeared. DAMAGE_TYPE ids are datapack-driven and referenced
 * by key, not part of this numeric-id mapping snapshot, and are brand-new under dmz_ragnarok with no legacy save refs.
 *
 * <p>{@link MissingMappingsEvent} is a FORGE-bus event (see its Forge javadoc), so this subscriber is on the forge
 * bus. It fires once per world load, only for ids present in the save but absent from the current registry, and
 * {@code remap()} rewrites them in place. The map is purely {@code <oldNamespace>:path -> dmz_ragnarok:path}; the
 * path never changes here, and the target is verified to exist before remapping so a genuinely removed id is left
 * to Forge's normal handling rather than pointed at nothing.
 *
 * <p>One path-renaming special case from SU's earlier July 2026 rename (shuruisutilities:ninjin_npc -> rgnpc) is
 * owned by {@link RgNpcEntities.Remap}. It is skipped here so the two handlers never both act on the same mapping:
 * for that key the plain namespace swap has no target (dmz_ragnarok:ninjin_npc does not exist), so the
 * target-exists guard would skip it anyway; the explicit skip just makes the division of labour obvious. The other
 * old rename family, the record_dbcN music discs, is gone: the record items were removed in the 2.0 debloat, so both
 * record_dbcN and record_rgN are retired (ignored) below rather than remapped.
 *
 * <p>NOTE: dimensions, dimension types, biomes and noise settings are deliberately NOT part of this remap. They
 * keep their old namespaces and are migrated separately together with world-save rewriting, because unlike the
 * four registries here they cannot be remapped by this event.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NamespaceRemap {

    private NamespaceRemap() {
    }

    /**
     * Ids this addon USED to register and no longer does. Forge would otherwise treat each as a missing registry
     * entry and refuse the world; ignoring them drops the stacks quietly instead. Only ever add something here that
     * was genuinely removed, never something merely renamed: a rename belongs in the remap below, where the item a
     * player is holding survives.
     */
    private static final java.util.Set<String> RETIRED_ITEMS = java.util.Set.of(
            // Five decorative placeholder "runes" that did nothing and wore the real armour runes' art.
            "rune_lime", "rune_navy", "rune_orange", "rune_pink", "rune_purple",
            // The 232 plain placeholder content items removed in the 2.0 debloat (ContentItems held them all;
            // only ocarina_of_wind and ss_ticket survive). They did nothing in-world, so a save that still
            // names one loads clean instead of prompting a missing registry entry. All BLOCKS were kept.
            "3507", "5343", "angel_gem", "banshofan", "battlearmorboots02", "battlearmorchest02",
            "battlearmorlegs02", "bdsword", "beanplant", "belt1", "belt2", "belt3", "belt4", "belt5", "belt6",
            "belt7", "big_mushroom", "big_pearl", "bike_voucher", "blackform", "blackpiece", "blackshard",
            "bluedragonsword", "bluescouter", "boots", "candycane", "card_key", "chainmail_chestplate",
            "champion_belt", "champion_belt_2", "champion_belt_3", "chickenleg", "com_kinstone_blue",
            "com_kinstone_green", "com_kinstone_purple", "com_kinstone_red", "day_crystal", "dbform", "dbpiece",
            "dbshard", "emiyasw1", "emiyasw2", "escape_rope", "eternal_crystal", "evil_gem", "fresh_water",
            "gem_of_good", "gemevil", "gemflight", "gi_black_chest", "gi_black_leggings", "gi_blue_chest",
            "gi_blue_leggings", "gi_gray_chest", "gi_gray_leggins", "gi_green_chest", "gi_green_leggins",
            "gi_orange_chest", "gi_orange_leggins", "gi_pink_chest", "gi_pink_leggins", "gi_purple_chest",
            "gi_purple_leggins", "gi_red_chest", "gi_red_leggins", "gi_yellow_chest", "gi_yellow_leggins",
            "godsmushroom", "gohantrainingsword", "gokugt_boots", "gold_teeth", "greenform", "greenpiece",
            "greenscouter", "greenshard", "hamburger", "heart_medicine", "hyliantunic2", "hyliantunictop",
            "itembarbersnc", "itembraveswordblade", "itembraveswordhandle", "itemdinomeat", "itemdinomeatbig",
            "itemdinomeatcooked", "itemdinomeatcookedbig", "itemfabric", "itemjanembaessence", "itemstrongfabric",
            "itemwarenai", "itemwarenaifabric", "jelly_red", "jelly_yellow", "kibascookie", "kinstone_bag",
            "kinstone_bag_giant", "kinstone_bag_medium", "kinstone_bag_small", "kinstone_blue", "kinstone_gold",
            "kinstone_green", "kinstone_purple", "kinstone_red", "kinstone_triforce", "lantern1", "lbform",
            "lbpiece", "lbshard", "lemonade", "lesserpole", "library_book_a_history_of_masks",
            "library_book_a_hyrulean_bestiary", "library_book_legend_of_the_picori", "lift_key", "mform",
            "misosoup", "mpiece", "mshard", "mystic_water", "mystic_water_celestial", "mystic_water_luminous",
            "mystic_water_vital", "night_crystal", "nugget", "oaks_parcel", "oform", "opiece", "ordon_iron",
            "oshard", "pearl", "pearlblue", "pearlgreen", "pearlred", "pendant_courage", "pendant_power",
            "pendant_wisdom", "pform", "pinkscouter", "pole2", "poletier3", "power_pole", "powerpole", "ppiece",
            "pshard", "purplescouter", "r_1", "r_10", "r_100", "r_1000", "r_20", "r_5", "r_50", "r_500", "r_5000",
            "radish_legs", "raditz_boots", "raditz_chest", "record_rg1", "record_rg2", "record_rg3", "record_rg4",
            "record_rg5", "redscouter", "rform", "riceball", "ringbox2", "ringbox3", "rocs_feather",
            "royal_broadsword", "royal_guardsword", "royalguardsword", "rpiece", "rshard", "rusty_broadsword",
            "sacredflame_din", "sacredflame_farore", "sacredflame_nayru", "secret_key", "senzubean", "senzugold",
            "senzuhp", "senzuinvis", "senzuki", "senzustamina", "senzustrength", "senzuswiftness", "silph_scope",
            "soda_pop", "star_piece", "stardust", "swords_axe", "swords_hoe", "swords_pickaxe", "swords_shovel",
            "swords_sword", "tapion_boots", "tapion_chest", "tapion_head", "tapion_legs", "tea",
            "thragg_chestplate", "thraggleggings", "tiny_mushroom", "tonicdivine", "toniclegendary", "tonicmystic",
            "tonicrevive", "tp_body", "tp_boots", "tp_head", "tp_leggings", "travelers_sword", "uubchest",
            "uubleg", "vanity_d_5", "vanity_d_6", "viltrim_leggings", "viltrimboots", "viltrimite_body",
            "viltrimite_boots", "viltrimite_chest", "viltrimite_head", "viltrimite_leggings", "wallet", "wallet_2",
            "wallet_upgrade", "weapon_3507", "wolfsburger", "yajirobe_katana_item", "yform", "ypiece", "yshard",
            // The five pre-rename music-disc ids, now retired on BOTH sides: record_dbcN was the pre-July-2026
            // name (formerly remapped to record_rgN by RgNpcEntities.Remap) and record_rgN is in the list above,
            // so both families are ignored on load now that the discs are gone.
            "record_dbc1", "record_dbc2", "record_dbc3", "record_dbc4", "record_dbc5");

    @SubscribeEvent
    public static void onMissingMappings(MissingMappingsEvent event) {
        ignoreRetired(event);
        remapNamespace(event, ForgeRegistries.Keys.BLOCKS, ForgeRegistries.BLOCKS);
        remapNamespace(event, ForgeRegistries.Keys.ITEMS, ForgeRegistries.ITEMS);
        remapNamespace(event, ForgeRegistries.Keys.ENTITY_TYPES, ForgeRegistries.ENTITY_TYPES);
        remapNamespace(event, ForgeRegistries.Keys.BLOCK_ENTITY_TYPES, ForgeRegistries.BLOCK_ENTITY_TYPES);
        remapNamespace(event, ForgeRegistries.Keys.SOUND_EVENTS, ForgeRegistries.SOUND_EVENTS);
    }

    // Drop the retired ids, in this addon's namespace or either of its old ones, so a save that still names one
    // loads without a missing-registry prompt.
    private static void ignoreRetired(MissingMappingsEvent event) {
        for (MissingMappingsEvent.Mapping<net.minecraft.world.item.Item> mapping
                : event.getAllMappings(ForgeRegistries.Keys.ITEMS)) {
            ResourceLocation key = mapping.getKey();
            boolean ours = LegacyIds.NEW_NAMESPACE.equals(key.getNamespace())
                    || LegacyIds.OLD_NAMESPACES.contains(key.getNamespace());
            if (ours && RETIRED_ITEMS.contains(key.getPath())) {
                mapping.ignore();
            }
        }
    }

    private static <T> void remapNamespace(MissingMappingsEvent event,
                                           ResourceKey<? extends Registry<T>> registryKey,
                                           IForgeRegistry<T> registry) {
        for (MissingMappingsEvent.Mapping<T> mapping : event.getAllMappings(registryKey)) {
            ResourceLocation key = mapping.getKey();
            if (!LegacyIds.OLD_NAMESPACES.contains(key.getNamespace())) {
                continue;
            }
            if (isRgNpcLegacy(key)) {
                // Path-renaming cases owned by RgNpcEntities.Remap; leave them to that handler.
                continue;
            }
            T current = registry.getValue(ResourceLocation.fromNamespaceAndPath(LegacyIds.NEW_NAMESPACE, key.getPath()));
            if (current != null) {
                mapping.remap(current);
            }
        }
    }

    // The SU legacy entity id that changes PATH (not just namespace) on load, remapped by RgNpcEntities.Remap.
    // The record_dbcN discs used to belong here too, but the record items are gone in the 2.0 debloat, so both
    // record_dbcN and record_rgN are now retired (ignored above) rather than path-remapped.
    // Package-private so ModuleAbsenceGuard can exclude this already-handled remap from its missing-id recording.
    static boolean isRgNpcLegacy(ResourceLocation key) {
        if (!"shuruisutilities".equals(key.getNamespace())) {
            return false;
        }
        return "ninjin_npc".equals(key.getPath());
    }

    // ---- Helpers shared with ModuleAbsenceGuard (same package) --------------------------------------------
    // ModuleAbsenceGuard records the missing ids this handler does NOT heal (a genuinely removed module's
    // registrations), so it must reuse the SAME "is this ours" and "is this retired" tests, in one place, or the
    // two could drift and either double-report a remapped id or miss a retired one.

    /** True when the id lives in the merged namespace or one of the five pre-merge namespaces. */
    static boolean isOurNamespace(ResourceLocation key) {
        return LegacyIds.NEW_NAMESPACE.equals(key.getNamespace())
                || LegacyIds.OLD_NAMESPACES.contains(key.getNamespace());
    }

    /** True when the id is one of the deliberately-removed placeholder ids that {@link #ignoreRetired} drops. */
    static boolean isRetired(ResourceLocation key) {
        return isOurNamespace(key) && RETIRED_ITEMS.contains(key.getPath());
    }

    /** Count of retired placeholder ids, for the guard's boot diagnostics. */
    static int retiredCount() {
        return RETIRED_ITEMS.size();
    }
}
