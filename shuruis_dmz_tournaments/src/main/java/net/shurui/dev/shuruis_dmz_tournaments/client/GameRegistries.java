package net.shurui.dev.shuruis_dmz_tournaments.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.shurui.dev.shuruis_dmz_tournaments.Config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Client-side lists for the editor's searchable dropdowns: every entity type, item, DragonMineZ skill,
 * and configured title id. Entities and items span every loaded mod (vanilla, DMZ, sdu, etc.).
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
     * Every ragnarok NPC model id, for the host/boss look pickers. All 386 characters share one entity
     * type ({@code dmz_ragnarok:rgnpc}), so the entity dropdown alone only ever produces the default
     * look; this list restores the whole cast, ninjin included. Full table, not the key-filtered one:
     * operator-facing editor, and the server re-resolves the choice at spawn time.
     */
    public static List<String> rgNpcModelIds() {
        if (rgNpcModelIds == null) {
            // No "none" of our own: df() already puts a blank first option in front of every list, and
            // blank is what these fields store for "leave the entity's own look alone".
            // SORTED: the table is in manifest order, which under a sorted entity list and a six-row
            // window reads as "most of them are missing". Only the picker changes; the stored value is
            // the id string, not an index.
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

    /**
     * Title ids the server recognises. Prefers the SYNCED list ({@link TitleListClient}, pushed on login) so the
     * dropdowns match the server even when this client's own common config is stale, which is the whole point: the
     * definitions live in a COMMON config that is never shipped from server to client. Falls back to the local config
     * only when NO sync has arrived (single player, or a server too old to send the packet); that fallback is
     * deliberate and checked explicitly via {@link TitleListClient#received()}, not left to an empty synced list.
     */
    public static List<String> titleIds() {
        if (TitleListClient.received()) {
            return TitleListClient.ids();
        }
        List<String> list = new ArrayList<>();
        for (String def : Config.TITLE_DEFS.get()) {
            int sep = def.indexOf('|');
            list.add(sep > 0 ? def.substring(0, sep) : def);
        }
        return list;
    }
}
