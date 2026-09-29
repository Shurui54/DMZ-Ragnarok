package net.shurui.shuruisutilities.ragnarok;

import java.util.HashMap;
import java.util.Map;

import net.shurui.shuruisutilities.api.permissions.AreaZone;
import net.shurui.shuruisutilities.api.permissions.ServerZone;
import net.shurui.shuruisutilities.api.permissions.WorldZone;
import net.shurui.shuruisutilities.api.permissions.Zone;
import net.shurui.shuruisutilities.api.permissions.Zone.PermissionList;

/**
 * Migrates stored {@code command.*} permission grants from the pre-rg branded command roots onto the new {@code /rg}
 * tree, so a grant survives the command rename instead of being orphaned.
 *
 * <p>Follows the {@link LegacyIds} precedent the coordinator set: normalize on READ (the permission zone is rewritten
 * the moment it is loaded, in {@code ZonedPermissionHelper.load}) and let the next save write the NEW form (the zone is
 * already migrated in memory). So the data converts itself as it is touched: idempotent, cannot half-apply, and covers
 * hand-edited files, restored backups and rolled-back saves. This is deliberately NOT a one-time script.
 *
 * <p>Mapping:
 * <ul>
 *   <li>Branded subcommand roots collapse onto their {@code /rg} segment: {@code command.sdu.*} to {@code command.rg.npc.*},
 *       {@code sdd} to {@code rg.dungeon}, {@code srb} and the {@code raidboss} alias to {@code rg.raid},
 *       {@code sdt} and the {@code tournament} alias to {@code rg.tourney}.</li>
 *   <li>Branded standalone {@code su*} leaf commands become {@code rg*}: {@code command.sugui} to {@code command.rggui},
 *       and so on. The {@code suconfig} secondary alias folds onto {@code rgsettings} like its primary.</li>
 * </ul>
 *
 * <p>Only {@code command.*} nodes whose head is a renamed root/leaf are touched. The {@code su.*} permission namespace
 * (the {@code ShuruisUtilities.PERM} constant) is out of scope and passes through untouched, as does any other command.
 */
public final class PermissionNodeMigration {

    // branded subcommand roots -> the /rg segment perm-node prefix their hidden redirect now lands on.
    private static final Map<String, String> ROOT_SEGMENT = new HashMap<>();
    // branded standalone leaf commands -> their new rg* perm-node head.
    private static final Map<String, String> LEAF = new HashMap<>();

    static {
        ROOT_SEGMENT.put("sdu", "rg.npc");
        ROOT_SEGMENT.put("sdd", "rg.dungeon");
        ROOT_SEGMENT.put("srb", "rg.raid");
        ROOT_SEGMENT.put("raidboss", "rg.raid");
        ROOT_SEGMENT.put("sdt", "rg.tourney");
        ROOT_SEGMENT.put("tournament", "rg.tourney");

        LEAF.put("sugui", "rggui");
        LEAF.put("suinfo", "rginfo");
        LEAF.put("surace", "rgrace");
        LEAF.put("sureload", "rgreload");
        LEAF.put("sureset", "rgreset");
        LEAF.put("susettings", "rgsettings");
        LEAF.put("suconfig", "rgsettings"); // pre-rg secondary alias of susettings
        LEAF.put("sutesting", "rgtesting");
        LEAF.put("suworldinfo", "rgworldinfo");
        LEAF.put("suentity", "rgentity");
        LEAF.put("sugrave", "rggrave");
    }

    private PermissionNodeMigration() {
    }

    /**
     * Rewrite one permission key onto the new command node, or return it unchanged if it is not a renamed command node.
     * Only the head token (the part right after {@code command.}) is remapped; the rest of the node is preserved.
     */
    public static String migrateKey(String key) {
        if (key == null || !key.startsWith("command.")) {
            return key;
        }
        String rest = key.substring("command.".length());
        int dot = rest.indexOf('.');
        String head = dot < 0 ? rest : rest.substring(0, dot);
        String tail = dot < 0 ? "" : rest.substring(dot); // includes the leading '.'
        String seg = ROOT_SEGMENT.get(head);
        if (seg != null) {
            return "command." + seg + tail;
        }
        String leaf = LEAF.get(head);
        if (leaf != null) {
            return "command." + leaf + tail;
        }
        return key;
    }

    /**
     * Migrate every group and player permission list in the server zone and all of its world/area sub-zones in place.
     * Returns the number of keys rewritten.
     */
    public static int migrate(ServerZone serverZone) {
        if (serverZone == null) {
            return 0;
        }
        int[] count = { 0 };
        migrateZone(serverZone, count);
        for (WorldZone worldZone : serverZone.getWorldZones().values()) {
            migrateZone(worldZone, count);
            for (AreaZone areaZone : worldZone.getAreaZones()) {
                migrateZone(areaZone, count);
            }
        }
        return count[0];
    }

    private static void migrateZone(Zone zone, int[] count) {
        for (PermissionList list : zone.getGroupPermissions().values()) {
            migrateList(list, count);
        }
        for (PermissionList list : zone.getPlayerPermissions().values()) {
            migrateList(list, count);
        }
    }

    private static void migrateList(PermissionList list, int[] count) {
        // Collect renames first, then apply, so we never mutate the map while iterating its keys.
        Map<String, String> renames = new HashMap<>();
        for (String key : list.keySet()) {
            String newKey = migrateKey(key);
            if (!newKey.equals(key)) {
                renames.put(key, newKey);
            }
        }
        for (Map.Entry<String, String> rename : renames.entrySet()) {
            String value = list.remove(rename.getKey());
            // If both the old and the new node were granted, keep the explicit new-form grant already present.
            list.putIfAbsent(rename.getValue(), value);
            count[0]++;
        }
    }
}
