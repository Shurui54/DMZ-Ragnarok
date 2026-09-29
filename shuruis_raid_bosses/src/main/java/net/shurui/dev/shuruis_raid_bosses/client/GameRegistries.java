package net.shurui.dev.shuruis_raid_bosses.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Client-side lists that populate the editor's searchable dropdowns: every entity type, every item,
 * every sound, and every DragonMineZ skill. Entities/items include everything from every loaded mod
 * (vanilla, DMZ, sdu, etc.).
 */
public final class GameRegistries {
    private GameRegistries() {}

    private static List<String> entityIds;
    private static List<String> soundIds;
    private static List<ResourceLocation> itemIds;

    public static List<String> soundIds() {
        if (soundIds == null) {
            List<String> list = new ArrayList<>();
            for (ResourceLocation id : ForgeRegistries.SOUND_EVENTS.getKeys()) list.add(id.toString());
            list.sort(Comparator.naturalOrder());
            soundIds = list;
        }
        return soundIds;
    }

    public static List<String> entityIds() {
        if (entityIds == null) {
            List<String> list = new ArrayList<>();
            for (ResourceLocation id : ForgeRegistries.ENTITY_TYPES.getKeys()) list.add(id.toString());
            list.sort(Comparator.naturalOrder());
            entityIds = list;
        }
        return entityIds;
    }

    private static List<String> rgNpcModelIds;

    /**
     * Every ragnarok NPC model id, for the host/boss look pickers.
     *
     * <p>The entity dropdown lists TYPES, and all 386 characters share one ({@code dmz_ragnarok:rgnpc}), so
     * alone they produce only the default look. This list turns that single type back into the whole cast,
     * ninjin included.
     *
     * <p>The FULL table, not the key-filtered one: this is operator-facing and the server re-resolves the choice
     * at spawn time, so filtering here would only hide ids from the person configuring them.
     */
    public static List<String> rgNpcModelIds() {
        if (rgNpcModelIds == null) {
            // No "none" entry of our own: df() puts a blank first option on every list, and blank is what these
            // fields store for "leave the entity's own look alone". SORTED, not the manifest order the table
            // ships in: 386 ids in authoring order under a sorted entity list reads as "most are missing". Only
            // the picker changes; nothing persists an index, the stored value is the id string.
            rgNpcModelIds = new java.util.ArrayList<>(net.shurui.shuruisutilities.ragnarok.RgNpcModels.ids());
            rgNpcModelIds.sort(String::compareToIgnoreCase);
        }
        return rgNpcModelIds;
    }

    public static List<ResourceLocation> itemIds() {
        if (itemIds == null) {
            List<ResourceLocation> list = new ArrayList<>(ForgeRegistries.ITEMS.getKeys());
            list.sort(Comparator.comparing(ResourceLocation::toString));
            itemIds = list;
        }
        return itemIds;
    }

    /** DragonMineZ skill ids from its synced skills config (includes addon-registered skills). */
    public static List<String> skillIds() {
        List<String> list = new ArrayList<>();
        try {
            var config = com.dragonminez.common.config.ConfigManager.getSkillsConfig();
            if (config != null && config.getSkills() != null) list.addAll(config.getSkills().keySet());
        } catch (Throwable ignored) {
        }
        list.sort(Comparator.naturalOrder());
        return list;
    }
}
