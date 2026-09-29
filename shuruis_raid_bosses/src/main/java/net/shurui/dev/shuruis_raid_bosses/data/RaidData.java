package net.shurui.dev.shuruis_raid_bosses.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent: all {@link RaidBossDef}s and the last scheduled fire time per raid (to avoid duplicate
 * auto-runs). Live fights stay in memory in {@code RaidManager}.
 */
public class RaidData extends SavedData {
    private static final String NAME = "shuruis_raid_bosses";

    private final Map<String, RaidBossDef> defs = new LinkedHashMap<>();
    private final Map<String, Long> lastScheduledRun = new LinkedHashMap<>();

    public static RaidData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(RaidData::load, RaidData::new, NAME);
    }

    public RaidBossDef getDef(String id) {
        return id == null ? null : defs.get(id.toLowerCase());
    }

    public void putDef(RaidBossDef def) {
        defs.put(def.id.toLowerCase(), def);
        setDirty();
    }

    public void removeDef(String id) {
        if (defs.remove(id.toLowerCase()) != null) setDirty();
    }

    public Map<String, RaidBossDef> allDefs() {
        return defs;
    }

    public long getLastScheduledRun(String defId) {
        return lastScheduledRun.getOrDefault(defId, 0L);
    }

    public void setLastScheduledRun(String defId, long epochMillis) {
        lastScheduledRun.put(defId, epochMillis);
        setDirty();
    }

    // definitions only. lastScheduledRun stays out on purpose: it is this server's own auto-fire clock,
    // and carrying it would let one server's fire suppress another's or reopen a duplicate. Definitions
    // travel, scheduling stays local.
    public CompoundTag saveDefs(CompoundTag tag) {
        ListTag defList = new ListTag();
        defs.values().forEach(def -> defList.add(def.save()));
        tag.put("defs", defList);
        return tag;
    }

    // cross-server sync write: REPLACE the def set with a sibling's, so add/edit/DELETE all travel (a
    // merge would resurrect a deleted raid). Schedule map untouched, keeping each server's fire timing.
    public void loadDefsInto(CompoundTag tag) {
        defs.clear();
        for (Tag t : tag.getList("defs", Tag.TAG_COMPOUND)) {
            RaidBossDef def = RaidBossDef.load((CompoundTag) t);
            if (def.id != null && !def.id.isBlank()) defs.put(def.id.toLowerCase(), def);
        }
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag defList = new ListTag();
        defs.values().forEach(def -> defList.add(def.save()));
        tag.put("defs", defList);

        ListTag schedList = new ListTag();
        lastScheduledRun.forEach((id, when) -> {
            CompoundTag e = new CompoundTag();
            e.putString("id", id);
            e.putLong("when", when);
            schedList.add(e);
        });
        tag.put("schedule", schedList);
        return tag;
    }

    public static RaidData load(CompoundTag tag) {
        RaidData data = new RaidData();
        for (Tag t : tag.getList("defs", Tag.TAG_COMPOUND)) {
            RaidBossDef def = RaidBossDef.load((CompoundTag) t);
            if (def.id != null && !def.id.isBlank()) data.defs.put(def.id.toLowerCase(), def);
        }
        for (Tag t : tag.getList("schedule", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            data.lastScheduledRun.put(e.getString("id"), e.getLong("when"));
        }
        return data;
    }
}
