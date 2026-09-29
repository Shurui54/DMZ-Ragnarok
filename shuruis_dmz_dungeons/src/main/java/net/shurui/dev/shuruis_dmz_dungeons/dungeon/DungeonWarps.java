package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// named warp points. stored on the OVERWORLD data storage so they're server-wide regardless of dim.
// set with /rg dungeon setwarp <name>, jumped to from the manager GUI or /rg dungeon warp <name>.
public class DungeonWarps extends SavedData {

    // Pinned literal: the on-disk data/<NAME>.dat filename. Deriving it from MODID would silently orphan saved warps if MODID is renamed.
    public static final String NAME = "shuruis_dmz_dungeons_dungeon_warps";

    public record Warp(String dimension, double x, double y, double z, float yaw, float pitch) {
    }

    private final Map<String, Warp> warps = new LinkedHashMap<>();

    public DungeonWarps() {
    }

    public static DungeonWarps get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(DungeonWarps::load, DungeonWarps::new, NAME);
    }

    public List<String> names() {
        return new ArrayList<>(warps.keySet());
    }

    public Warp find(String name) {
        if (name == null) {
            return null;
        }
        Warp exact = warps.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Warp> e : warps.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    public void set(String name, Warp warp) {
        warps.put(name, warp);
        setDirty();
    }

    public boolean remove(String name) {
        if (warps.remove(name) != null) {
            setDirty();
            return true;
        }
        // case-insensitive fallback
        String key = null;
        for (String k : warps.keySet()) {
            if (k.equalsIgnoreCase(name)) {
                key = k;
                break;
            }
        }
        if (key != null) {
            warps.remove(key);
            setDirty();
            return true;
        }
        return false;
    }

    // shared by /rg dungeon warp and the GUI buttons. false if the warp or its dim is missing.
    public static boolean teleport(net.minecraft.server.level.ServerPlayer player, String name) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        Warp warp = get(server).find(name);
        if (warp == null) {
            return false;
        }
        ServerLevel level = level(server, warp);
        if (level == null) {
            return false;
        }
        player.teleportTo(level, warp.x(), warp.y(), warp.z(), warp.yaw(), warp.pitch());
        return true;
    }

    public static ServerLevel level(MinecraftServer server, Warp warp) {
        ResourceLocation loc = ResourceLocation.tryParse(warp.dimension());
        if (loc == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
    }

    // cross-server state sync write path: replace the whole warp table with a sibling's, so an added, moved or DELETED
    // warp all travel (a merge would resurrect a warp an admin deleted elsewhere). Warps are deliberate admin edits, so
    // last-write-wins on the whole set is the right trade.
    public void loadInto(CompoundTag tag) {
        warps.clear();
        ListTag list = tag.getList("warps", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            warps.put(t.getString("name"), new Warp(
                    t.getString("dim"), t.getDouble("x"), t.getDouble("y"), t.getDouble("z"),
                    t.getFloat("yaw"), t.getFloat("pitch")));
        }
        setDirty();
    }

    public static DungeonWarps load(CompoundTag tag) {
        DungeonWarps w = new DungeonWarps();
        ListTag list = tag.getList("warps", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            w.warps.put(t.getString("name"), new Warp(
                    t.getString("dim"), t.getDouble("x"), t.getDouble("y"), t.getDouble("z"),
                    t.getFloat("yaw"), t.getFloat("pitch")));
        }
        return w;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Warp> e : warps.entrySet()) {
            Warp warp = e.getValue();
            CompoundTag t = new CompoundTag();
            t.putString("name", e.getKey());
            t.putString("dim", warp.dimension());
            t.putDouble("x", warp.x());
            t.putDouble("y", warp.y());
            t.putDouble("z", warp.z());
            t.putFloat("yaw", warp.yaw());
            t.putFloat("pitch", warp.pitch());
            list.add(t);
        }
        tag.put("warps", list);
        return tag;
    }
}
