package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.gen.DungeonRoomLayout;

/**
 * The LOCAL, never-synced store for ADDITIVE event dungeon floors. Where {@link DungeonFloors} holds the shared,
 * cross-shard floor list, this holds only the temporary floors an event stands up: a virtual floor number (from
 * {@link DungeonFloorLayout#EVENT_FLOOR_BASE}) per event slot key, plus that floor's own config, generated flag,
 * rolled layout and boss-defeat state. It is deliberately kept OUT of the shared config and the {@code dungeons:floors}
 * sync, so a world running no event is byte-identical: {@link DungeonFloors#saveSharedConfig} and its save never touch
 * this store.
 *
 * <p>Stored on the OVERWORLD (a global SavedData) rather than the dungeon level, so it is always loaded at boot and the
 * key's boot-time reap always finds it (a crash after an event's teardown must still be cleaned up). Its coordinates
 * (the negative-z lane, {@link DungeonFloorLayout#EVENT_LANE_Z}) never enter any shared data.
 *
 * <p>Slot keys are stable per event floor (the key forms {@code eventId#floorIndex}). A slot key maps to a virtual
 * floor number for the life of the event; {@link #reap} frees a number once its slot key is no longer live, so a
 * short-lived event never exhausts the lane. Because {@link DungeonFloorLayout#floorSeed} is deterministic, a slot
 * that later gets the same number rebuilds an identical floor over the abandoned one.
 */
public class DungeonEventFloorState extends SavedData {

    // Pinned literal (not derived from MODID) so a rename can never silently orphan a saved event-floor allocation.
    public static final String NAME = "shuruis_dmz_dungeons_event_floors";

    // slot key -> virtual floor number (>= EVENT_FLOOR_BASE). The allocation of a number to a slot key.
    private final Map<String, Integer> slotToNumber = new HashMap<>();
    // per virtual floor number: its config, its rolled layout, whether its barrier box is built, whether its boss fell.
    private final Map<Integer, DungeonFloorConfig> configs = new HashMap<>();
    private final Map<Integer, DungeonRoomLayout> layouts = new HashMap<>();
    private final Set<Integer> generated = new HashSet<>();
    private final Set<Integer> bossDefeated = new HashSet<>();

    public DungeonEventFloorState() {
    }

    public static DungeonEventFloorState get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(DungeonEventFloorState::load, DungeonEventFloorState::new, NAME);
    }

    public static DungeonEventFloorState get(MinecraftServer server) {
        return server == null ? null : get(server.overworld());
    }

    // ---- allocation -------------------------------------------------------------------------------------------

    // the virtual floor number for a slot key, allocating the lowest free number from EVENT_FLOOR_BASE up on first use.
    public int allocate(String slotKey) {
        Integer existing = slotToNumber.get(slotKey);
        if (existing != null) {
            return existing;
        }
        Set<Integer> used = new HashSet<>(slotToNumber.values());
        int n = DungeonFloorLayout.EVENT_FLOOR_BASE;
        while (used.contains(n)) {
            n++;
        }
        slotToNumber.put(slotKey, n);
        setDirty();
        return n;
    }

    public Integer numberFor(String slotKey) {
        return slotToNumber.get(slotKey);
    }

    // ---- per-floor state (all keyed by the VIRTUAL floor number) ----------------------------------------------

    public boolean has(int number) {
        return configs.containsKey(number);
    }

    public DungeonFloorConfig config(int number) {
        return configs.get(number);
    }

    public void setConfig(int number, DungeonFloorConfig config) {
        if (config != null) {
            configs.put(number, config);
            setDirty();
        }
    }

    public void setSize(int number, int size) {
        DungeonFloorConfig c = configs.get(number);
        if (c != null) {
            c.size = size;
            setDirty();
        }
    }

    public boolean isGenerated(int number) {
        return generated.contains(number);
    }

    public void markGenerated(int number) {
        if (generated.add(number)) {
            setDirty();
        }
    }

    public void clearGenerated(int number) {
        if (generated.remove(number)) {
            setDirty();
        }
    }

    public DungeonRoomLayout getLayout(int number) {
        return layouts.get(number);
    }

    public void setLayout(int number, DungeonRoomLayout layout) {
        if (layout != null) {
            layouts.put(number, layout);
            setDirty();
        }
    }

    public boolean clearLayout(int number) {
        if (layouts.remove(number) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public void markLayoutPlaced(int number) {
        DungeonRoomLayout l = layouts.get(number);
        if (l != null && !l.placed) {
            l.placed = true;
            setDirty();
        }
    }

    public void markLayoutGrounded(int number) {
        DungeonRoomLayout l = layouts.get(number);
        if (l != null && !l.grounded) {
            l.grounded = true;
            setDirty();
        }
    }

    public void markLayoutSealed(int number) {
        DungeonRoomLayout l = layouts.get(number);
        if (l != null && !l.sealed) {
            l.sealed = true;
            setDirty();
        }
    }

    public void markLayoutConnected(int number) {
        DungeonRoomLayout l = layouts.get(number);
        if (l != null && !l.connected) {
            l.connected = true;
            setDirty();
        }
    }

    public void markLayoutCrated(int number) {
        DungeonRoomLayout l = layouts.get(number);
        if (l != null && !l.crated) {
            l.crated = true;
            setDirty();
        }
    }

    public void markLayoutSpawned(int number) {
        DungeonRoomLayout l = layouts.get(number);
        if (l != null && !l.spawned) {
            l.spawned = true;
            setDirty();
        }
    }

    public boolean isBossDefeated(int number) {
        return bossDefeated.contains(number);
    }

    public void markBossDefeated(int number) {
        if (bossDefeated.add(number)) {
            setDirty();
        }
    }

    public void clearBossDefeated(int number) {
        if (bossDefeated.remove(number)) {
            setDirty();
        }
    }

    // ---- reap -------------------------------------------------------------------------------------------------

    /**
     * Drop every event floor whose slot key is not in {@code liveSlotKeys}, freeing its virtual number and all of its
     * state (config, layout, generated flag, boss-defeat). Returns the virtual numbers dropped, for logging. The
     * abandoned terrain on the negative-z lane is left where it stands (like a relocated ordinary floor); a later
     * reuse of the number rebuilds an identical floor over it.
     */
    public List<Integer> reap(Set<String> liveSlotKeys) {
        List<Integer> removed = new ArrayList<>();
        Iterator<Map.Entry<String, Integer>> it = slotToNumber.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Integer> e = it.next();
            if (liveSlotKeys != null && liveSlotKeys.contains(e.getKey())) {
                continue;
            }
            int number = e.getValue();
            it.remove();
            configs.remove(number);
            layouts.remove(number);
            generated.remove(number);
            bossDefeated.remove(number);
            removed.add(number);
        }
        if (!removed.isEmpty()) {
            setDirty();
        }
        return removed;
    }

    // ---- persistence ------------------------------------------------------------------------------------------

    public static DungeonEventFloorState load(CompoundTag tag) {
        DungeonEventFloorState s = new DungeonEventFloorState();
        ListTag slots = tag.getList("slots", Tag.TAG_COMPOUND);
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag entry = slots.getCompound(i);
            s.slotToNumber.put(entry.getString("key"), entry.getInt("number"));
        }
        ListTag cfgs = tag.getList("configs", Tag.TAG_COMPOUND);
        for (int i = 0; i < cfgs.size(); i++) {
            CompoundTag entry = cfgs.getCompound(i);
            s.configs.put(entry.getInt("number"), DungeonFloorConfig.load(entry.getCompound("config")));
        }
        ListTag lays = tag.getList("layouts", Tag.TAG_COMPOUND);
        for (int i = 0; i < lays.size(); i++) {
            CompoundTag entry = lays.getCompound(i);
            s.layouts.put(entry.getInt("number"), DungeonRoomLayout.load(entry.getCompound("layout")));
        }
        for (int n : tag.getIntArray("generated")) {
            s.generated.add(n);
        }
        for (int n : tag.getIntArray("bossDefeated")) {
            s.bossDefeated.add(n);
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag slots = new ListTag();
        for (Map.Entry<String, Integer> e : slotToNumber.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("key", e.getKey());
            entry.putInt("number", e.getValue());
            slots.add(entry);
        }
        tag.put("slots", slots);
        ListTag cfgs = new ListTag();
        for (Map.Entry<Integer, DungeonFloorConfig> e : configs.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("number", e.getKey());
            entry.put("config", e.getValue().save(new CompoundTag()));
            cfgs.add(entry);
        }
        tag.put("configs", cfgs);
        ListTag lays = new ListTag();
        for (Map.Entry<Integer, DungeonRoomLayout> e : layouts.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("number", e.getKey());
            entry.put("layout", e.getValue().save());
            lays.add(entry);
        }
        tag.put("layouts", lays);
        int[] gen = new int[generated.size()];
        int gi = 0;
        for (int n : generated) {
            gen[gi++] = n;
        }
        tag.putIntArray("generated", gen);
        int[] def = new int[bossDefeated.size()];
        int di = 0;
        for (int n : bossDefeated) {
            def[di++] = n;
        }
        tag.putIntArray("bossDefeated", def);
        return tag;
    }
}
