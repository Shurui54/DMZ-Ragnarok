package net.shurui.dev.shuruis_raid_bosses.rift;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import net.shurui.dev.shuruis_raid_bosses.data.RaidData;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef;

/**
 * Persisted store of every {@link RiftDef}. Its own {@link SavedData}, not folded into {@link RaidData},
 * so a rift edit cannot rewrite the raid file and a corrupt one cannot take the raids down with it. NAME
 * below is the on-disk file name and must NEVER change: renaming it silently orphans every configured
 * rift, and the server comes up with no tears and no error.
 */
public class RiftDefs extends SavedData {

    private static final String NAME = "shuruis_raid_bosses_rifts";

    private final Map<String, RiftDef> defs = new LinkedHashMap<>();

    public static RiftDefs get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(RiftDefs::load, RiftDefs::new, NAME);
    }

    public RiftDef getDef(String id) {
        return id == null ? null : defs.get(id.toLowerCase(Locale.ROOT));
    }

    public void putDef(RiftDef def) {
        if (def == null || def.id == null || def.id.isBlank()) {
            return;
        }
        defs.put(def.id.toLowerCase(Locale.ROOT), def);
        setDirty();
    }

    public void removeDef(String id) {
        if (id != null && defs.remove(id.toLowerCase(Locale.ROOT)) != null) {
            setDirty();
        }
    }

    public Map<String, RiftDef> allDefs() {
        return defs;
    }

    /**
     * The rifts that may roll now: enabled, and naming a raid def that still exists. The raid-def check is
     * load-bearing: a rift whose raid was deleted or renamed would open a tear into an arena with no boss
     * and no way to win, looking like a tear bug rather than a dangling reference. {@link #brokenRaidIds}
     * reports the ones skipped.
     */
    public List<RiftDef> runnable(MinecraftServer server) {
        RaidData raids = RaidData.get(server);
        List<RiftDef> out = new ArrayList<>();
        for (RiftDef def : defs.values()) {
            if (isRunnable(def, raids)) {
                out.add(def);
            }
        }
        return out;
    }

    /** Enabled rifts whose {@link RiftDef#raidId} does not resolve, for operator-facing diagnostics. */
    public List<RiftDef> brokenRaidIds(MinecraftServer server) {
        RaidData raids = RaidData.get(server);
        List<RiftDef> out = new ArrayList<>();
        for (RiftDef def : defs.values()) {
            if (def.enabled && resolveRaid(def, raids) == null) {
                out.add(def);
            }
        }
        return out;
    }

    public static boolean isRunnable(RiftDef def, RaidData raids) {
        if (def == null || !def.enabled || resolveRaid(def, raids) == null) {
            return false;
        }
        // An eventOnly rift rolls only while an active timed event lists it, asked through the private key's
        // EventHooks.riftRunnable. Keyless the hook's default is false, so an eventOnly rift never opens a tear.
        return !def.eventOnly || net.shurui.dev.sdu.api.key.EventHooks.get().riftRunnable(def.id);
    }

    /**
     * The encounter a rift leads to: its OWN definition when it has one, else the stored raid it names.
     * The own encounter wins outright, never merged. The raid-id fallback is for rifts made before rifts
     * could own an encounter; opening the encounter editor on one gives it a copy to own from then on.
     */
    public static RaidBossDef resolveRaid(RiftDef def, RaidData raids) {
        if (def == null) {
            return null;
        }
        if (def.encounter != null) {
            return def.encounter;
        }
        if (raids == null || def.raidId == null || def.raidId.isBlank()) {
            return null;
        }
        return raids.getDef(def.raidId);
    }

    // cross-server sync write: REPLACE the rift set with a sibling's, so add/edit/DELETE all travel (a
    // merge would resurrect a deleted rift). The arena CELL (RiftArenaData) is NOT synced: it points at
    // terrain in one server's own dimension.
    public void loadInto(CompoundTag tag) {
        defs.clear();
        for (Tag t : tag.getList("rifts", Tag.TAG_COMPOUND)) {
            RiftDef def = RiftDef.load((CompoundTag) t);
            if (def.id != null && !def.id.isBlank()) {
                defs.put(def.id.toLowerCase(Locale.ROOT), def);
            }
        }
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        defs.values().forEach(def -> list.add(def.save()));
        tag.put("rifts", list);
        return tag;
    }

    public static RiftDefs load(CompoundTag tag) {
        RiftDefs data = new RiftDefs();
        for (Tag t : tag.getList("rifts", Tag.TAG_COMPOUND)) {
            RiftDef def = RiftDef.load((CompoundTag) t);
            if (def.id != null && !def.id.isBlank()) {
                data.defs.put(def.id.toLowerCase(Locale.ROOT), def);
            }
        }
        return data;
    }
}
