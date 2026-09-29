package net.shurui.dev.shuruis_dmz_dungeons.dungeon.gen;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Rotation;

import java.util.ArrayList;
import java.util.List;

// one placed room in a floor's persisted layout: grid position, rotation, the chosen variant path and the room-type
// key it came from, plus markers harvested from it. Once a floor's list is persisted it is never regenerated (see
// DungeonRoomLayout), so an admin can hand-detail a room and the layout survives.
//
// Markers are hooks for later stages (portals, enemies, crates, loot): enemy markers from minecells:spawner_rune
// blocks, chest markers from baked-in chest/barrel positions, portal markers from the room's portal anchor. Captured
// at layout time (LayoutGenerator) with the same rotation transform the placer uses, so they land on real positions.
public final class GridPlacement {

    // grid position. gx/gz are NOT final so the finished layout can be rigidly re-centred on the floor origin in one
    // final pass (LayoutGenerator.center) rather than bending the growth logic. gy is never translated.
    public int gx;
    public final int gy;
    public int gz;
    public final Rotation rotation;
    // room-type key (the pool path, e.g. "prison/cell"), kept for the deferred decoration hook and debug.
    public final String roomType;
    // the specific variant chosen from the pool (e.g. "prison/cell/2"), the NBT the placer reads.
    public final String templatePath;

    public final List<BlockPos> enemyMarkers = new ArrayList<>();
    public final List<BlockPos> chestMarkers = new ArrayList<>();
    public final List<BlockPos> portalMarkers = new ArrayList<>();

    public GridPlacement(int gx, int gy, int gz, Rotation rotation, String roomType, String templatePath) {
        this.gx = gx;
        this.gy = gy;
        this.gz = gz;
        this.rotation = rotation;
        this.roomType = roomType;
        this.templatePath = templatePath;
    }

    // rigidly shift this placement in the XZ plane. used ONCE, after a layout is fully grown, to re-centre it on the
    // floor origin (LayoutGenerator.center). markers are harvested AFTER this, so they use the final centred position.
    public void translate(int dx, int dz) {
        this.gx += dx;
        this.gz += dz;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("gx", gx);
        tag.putInt("gy", gy);
        tag.putInt("gz", gz);
        tag.putInt("rot", rotation.ordinal());
        tag.putString("type", roomType == null ? "" : roomType);
        tag.putString("template", templatePath == null ? "" : templatePath);
        tag.putLongArray("enemyMarkers", pack(enemyMarkers));
        tag.putLongArray("chestMarkers", pack(chestMarkers));
        tag.putLongArray("portalMarkers", pack(portalMarkers));
        return tag;
    }

    public static GridPlacement load(CompoundTag tag) {
        Rotation rot = Rotation.values()[Math.floorMod(tag.getInt("rot"), Rotation.values().length)];
        GridPlacement p = new GridPlacement(tag.getInt("gx"), tag.getInt("gy"), tag.getInt("gz"), rot,
                tag.getString("type"), tag.getString("template"));
        unpack(tag.getLongArray("enemyMarkers"), p.enemyMarkers);
        unpack(tag.getLongArray("chestMarkers"), p.chestMarkers);
        unpack(tag.getLongArray("portalMarkers"), p.portalMarkers);
        return p;
    }

    private static long[] pack(List<BlockPos> positions) {
        long[] out = new long[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            out[i] = positions.get(i).asLong();
        }
        return out;
    }

    private static void unpack(long[] data, List<BlockPos> into) {
        for (long v : data) {
            into.add(BlockPos.of(v));
        }
    }
}
