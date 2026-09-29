package net.shurui.shuruisutilities.ragnarok;

import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * The ONE place that knows the five pre-merge namespaces and rewrites them to "dmz_ragnarok".
 *
 * <p>When the five DragonMineZ addons collapsed into one mod, every registry/asset id moved from its own namespace
 * (sdu, shuruisutilities, shuruis_dmz_dungeons, shuruis_raid_bosses, shuruis_dmz_tournaments) to "dmz_ragnarok" with
 * the same path. Level saves are healed at load by {@link NamespaceRemap} (the persisted-registry MissingMappings
 * remap). This helper heals the OTHER references: ids stored inside our own JSON/NBT config and save blobs, which the
 * registry remap never touches, plus plain-string comparisons where an installed registry alias would not help.
 *
 * <p>The policy the coordinator set:
 * <ul>
 *   <li>Normalize on READ: every place we parse one of OUR ids out of our own data calls {@link #normalize(String)}.
 *   <li>Write the NEW form on SAVE: because a normalized value is already dmz_ragnarok in memory, the next save writes
 *       it in the new form, so data converts itself as it is touched. No one-time migration pass, so it is idempotent,
 *       cannot half-apply, and covers hand-edited configs, restored backups and rolled-back worlds forever.
 * </ul>
 *
 * <p>Only OUR ids are rewritten. A dragonminez:, minecraft: or any other mod's id passes through untouched: a greedy
 * normalizer that rewrote another mod's id would be worse than the bug it fixes.
 *
 * <p>IMPORTANT: this helper is deliberately NOT applied to DIMENSION or BIOME ids. Those keep their old namespaces
 * (they are migrated separately with world-save rewriting), so a value like {@code shuruisutilities:planet_vegeta}
 * must be read WITHOUT calling this. Callers apply it only where they read a registry id (entity/item/block) or an
 * asset id (model/texture), never a dimension/biome id.
 */
public final class LegacyIds {

    public static final String NEW_NAMESPACE = "dmz_ragnarok";

    // The five pre-merge namespaces. A future rename edits this set in one place. NamespaceRemap reuses it.
    public static final Set<String> OLD_NAMESPACES = Set.of(
            "sdu",
            "shuruisutilities",
            "shuruis_dmz_dungeons",
            "shuruis_raid_bosses",
            "shuruis_dmz_tournaments");

    private LegacyIds() {
    }

    /**
     * If {@code id} is a "namespace:path" id whose namespace is one of the five pre-merge namespaces, return it with
     * the namespace swapped to dmz_ragnarok; otherwise return it unchanged. Null, blank and non-namespaced strings
     * pass through. Never touches dragonminez:, minecraft: or any other mod's id.
     */
    public static String normalize(String id) {
        if (id == null) {
            return null;
        }
        int colon = id.indexOf(':');
        if (colon <= 0) {
            return id;
        }
        String namespace = id.substring(0, colon);
        if (OLD_NAMESPACES.contains(namespace)) {
            return NEW_NAMESPACE + id.substring(colon);
        }
        return id;
    }

    /**
     * In-place normalization of the registry id(s) inside a vanilla ItemStack NBT tag before {@code ItemStack.of}
     * reads it, so a stored stack of one of our items (in an auction listing, trade escrow, claim entry or one of our
     * bag inventories) keeps resolving to the merged item instead of becoming empty. Recurses through nested item
     * containers (a stack's "tag", any "Items"/"Item" lists, a "BlockEntityTag") so items nested inside a shulker or a
     * bag are covered too. Returns the same tag for call chaining. Non-our ids are left alone.
     */
    public static CompoundTag normalizeItemTag(CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        if (tag.contains("id", Tag.TAG_STRING)) {
            tag.putString("id", normalize(tag.getString("id")));
        }
        for (String key : tag.getAllKeys()) {
            Tag child = tag.get(key);
            if (child instanceof CompoundTag childCompound) {
                normalizeItemTag(childCompound);
            } else if (child instanceof ListTag childList) {
                for (int i = 0; i < childList.size(); i++) {
                    if (childList.get(i) instanceof CompoundTag element) {
                        normalizeItemTag(element);
                    }
                }
            }
        }
        return tag;
    }
}
