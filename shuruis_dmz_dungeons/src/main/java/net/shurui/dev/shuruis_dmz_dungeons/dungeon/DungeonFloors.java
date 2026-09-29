package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.shurui.dev.shuruis_dmz_dungeons.Config;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.gen.DungeonRoomLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// per-world dungeon floor model, persisted as SavedData on the DUNGEON level (mirroring DungeonRules) so it
// travels with the save and stays dungeon-scoped. holds:
//   * floors:    an ordered list of DungeonFloorConfig, index 0 = floor 1. the list SIZE is the number of floors,
//                so "configurable NUMBER of floors, each with its own theme" is just this list.
//   * generated: the set of floor NUMBERS (1-based) whose terrain has already been stamped. keyed by floor number,
//                NOT list position, because floor N's terrain always sits at the same fixed cell (see
//                DungeonFloorLayout); shrinking then re-growing the floor count must not lose the fact that a
//                cell was already built.
//
// a floor is marked generated ONLY when its barrier box finishes, so an interrupted build re-runs cleanly, and
// once marked it is NEVER re-built, which is what lets an admin hand-build detail into a floor and have it persist.
public class DungeonFloors extends SavedData {

    // Pinned literal: this is the on-disk data/<NAME>.dat filename, and deriving it from MODID would silently orphan saved floor state if MODID is ever renamed.
    public static final String NAME = "shuruis_dmz_dungeons_dungeon_floors";

    // persistence schema version. bump this whenever a change makes previously persisted generation state
    // meaningless. Version history:
    //   (absent / 0) - the pre-pivot scheme: floors were STAMPED into the single flat dungeon dim at x = N * 65536.
    //   1            - the themed-dimension pivot: floors live in per-theme noise/flat dims at x = N * 100000, and
    //                  terrain comes from the dimension generator, not a stamp. Any old "generated" flag or rolled
    //                  layout describes terrain in the WRONG place (orphaned but harmless in the old flat dim), so
    //                  on load from an older version we clear both and keep only the floor CONFIGS, letting floors
    //                  re-roll cleanly into the new dimensions.
    public static final int SCHEMA_VERSION = 1;

    private final List<DungeonFloorConfig> floors = new ArrayList<>();
    private final Set<Integer> generated = new HashSet<>();
    // floor NUMBERS (1-based) whose guardian boss has been defeated. keyed by floor number like `generated`, so it
    // survives a floor-count change and a restart. presence means "this floor's next-floor portal is unlocked";
    // absence means the boss still guards the descent. a ticket bypasses this set by design (see FloorTicketItem).
    private final Set<Integer> bossDefeated = new HashSet<>();
    // the persisted room layout per floor NUMBER (1-based), keyed the same way as generated (fixed cell per floor).
    // presence means "rolled"; a layout is never re-rolled once here (run-once, hand-detailed floors). the placed
    // flag inside a layout means "written into the world" and is set only when the tick-budgeted writer finishes.
    private final Map<Integer, DungeonRoomLayout> layouts = new HashMap<>();

    // the RELOCATION EPOCH per floor NUMBER (1-based). This is a location pointer, NOT terrain state: it decides
    // WHERE floor N's next build lands (DungeonFloorLayout.cellCentre offsets Z by epoch * CELL_SPACING), while the
    // generated flag / layout above are the geometry that build produced. Absent means epoch 0 (the Z = 0 row every
    // pre-relocation floor was built on). Bumped by relocateFloor when a floor is removed or retyped, which ALSO
    // drops that floor's geometry so the next visit rebuilds on the fresh ground the new epoch points at and the old
    // build is abandoned rather than re-stamped over. Unlike the geometry, this DOES travel in the shared config
    // (saveSharedConfig), because every shard must agree where floor N currently is even though each builds its own
    // copy of the terrain there. Entries are kept for retired numbers too, so a floor later re-created at a number
    // that was removed starts on new ground instead of on top of the removed floor's leftovers.
    private final Map<Integer, Integer> relocationEpochs = new HashMap<>();

    // the ServerLevel this instance was resolved through, set by get(...), so the per-floor methods can reach the
    // separate DungeonEventFloorState for a VIRTUAL event floor number (>= EVENT_FLOOR_BASE). NOT persisted and NOT
    // part of any saved or synced form: it is only a handle used to route event-floor reads/writes to their own
    // local store, keeping the shared floor list and dungeons:floors byte-identical.
    private transient ServerLevel owningLevel;

    // fresh-world constructor: seed the configured number of floors with their defaults, exactly the way
    // DungeonRules seeds its fields from Config on first creation.
    public DungeonFloors() {
        int count = Math.max(0, Config.dungeonFloorCount);
        for (int i = 1; i <= count; i++) {
            floors.add(DungeonFloorConfig.defaultFor(i));
        }
    }

    public static DungeonFloors get(ServerLevel dungeonLevel) {
        DungeonFloors f = dungeonLevel.getDataStorage().computeIfAbsent(DungeonFloors::load, DungeonFloors::new, NAME);
        f.owningLevel = dungeonLevel;
        return f;
    }

    // the separate LOCAL store for event floors (never synced), or null if this instance has no owning level yet.
    // The per-floor methods below route a VIRTUAL floor number (>= EVENT_FLOOR_BASE) here instead of to the shared
    // floor list, so an event floor's config/geometry/boss state lives entirely outside the shared, synced data.
    private DungeonEventFloorState events() {
        return owningLevel == null ? null : DungeonEventFloorState.get(owningLevel);
    }

    public static DungeonFloors get(MinecraftServer server) {
        ServerLevel dungeon = DungeonDimensions.level(server);
        return dungeon == null ? null : get(dungeon);
    }

    // Force-creating resolver for the cross-server WRITE path. A floor edit made on a dungeon-having server must land
    // on a server that has never materialised the legacy dungeon dim, or the edit is dropped. get() uses the
    // NON-creating level lookup on purpose (the read/publish path must send nothing rather than spin up a dim on an
    // idle server), so the write path calls this instead: it materialises the dim exactly when a real edit needs it.
    // Returns null only when the dimension has no LevelStem at all, which the caller must treat as "retry later".
    public static DungeonFloors getOrCreate(MinecraftServer server) {
        ServerLevel dungeon = DungeonDimensions.getOrCreateLevel(server, DungeonDimensions.DUNGEON);
        return dungeon == null ? null : get(dungeon);
    }

    public int count() {
        return floors.size();
    }

    public boolean isValidFloor(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            return ev != null && ev.has(floorNumber);
        }
        return floorNumber >= 1 && floorNumber <= floors.size();
    }

    // config for a floor number (1-based), or null if out of range. A virtual event floor number (>= EVENT_FLOOR_BASE)
    // reads from the separate event store instead of the shared list.
    public DungeonFloorConfig get(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            return ev == null ? null : ev.config(floorNumber);
        }
        return isValidFloor(floorNumber) ? floors.get(floorNumber - 1) : null;
    }

    // set the number of floors. growing appends default floors; shrinking drops from the end AND relocates each
    // dropped number (see relocateFloor), which both forgets its geometry and RETIRES its location by bumping its
    // epoch. So a floor later re-created at that number is genuinely new: a fresh config on fresh ground, never
    // inheriting the removed floor's generated flag, rolled layout, or its build's location.
    public void setCount(int newCount) {
        int target = Math.max(0, newCount);
        boolean changed = false;
        while (floors.size() < target) {
            floors.add(DungeonFloorConfig.defaultFor(floors.size() + 1));
            changed = true;
        }
        while (floors.size() > target) {
            relocateFloor(floors.size());
            floors.remove(floors.size() - 1);
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    // low-level positional apply: grow/shrink the ordered config list to match the incoming one, then overwrite each
    // floor's config positionally. A shrink DROPS the tail numbers' GEOMETRY (purgeFloor) but does NOT touch their
    // epochs, so this is a pure "make the list look like `incoming`" primitive with no relocation policy of its own.
    // The two policy-bearing callers wrap it:
    //   * applyConfigsFromEditor (an admin edit ORIGINATED here) decides which floors relocate and bumps their epoch.
    //   * applySharedConfig (a sibling shard's edit REPLAYED here) adopts the epochs the origin already chose.
    // Kept private so no path can change the floor list without going through one of those two.
    private void applyConfigs(java.util.List<DungeonFloorConfig> incoming) {
        int target = Math.max(0, incoming.size());
        while (floors.size() < target) {
            floors.add(DungeonFloorConfig.defaultFor(floors.size() + 1));
        }
        while (floors.size() > target) {
            // a number that no longer has a floor must not keep that floor's geometry, or the next floor created at
            // it comes back already-generated and with the old layout. Epoch is handled by the caller.
            purgeFloor(floors.size());
            floors.remove(floors.size() - 1);
        }
        for (int i = 0; i < target; i++) {
            DungeonFloorConfig c = incoming.get(i);
            if (c != null) {
                floors.set(i, c);
            }
        }
        setDirty();
    }

    // GUI/editor ORIGIN path: apply an admin-edited floor list, relocating anything whose build changed so the world
    // is never re-stamped on top of an old build.
    //   * A SURVIVING floor whose BUILD IDENTITY (theme, type or layout style) changed is relocated: its epoch is
    //     bumped and its geometry dropped, so its next visit builds a fresh floor on new ground and the old build is
    //     left orphaned. A non-build edit (requireTicket, boss / enemy / crate config, size or depth) never relocates:
    //     the floor keeps its build and its place, and size/depth keep the existing "applies on next generation"
    //     contract (an explicit /rg dungeon floor regen, which now also relocates, is how you rebuild them).
    //   * A REMOVED tail floor is relocated too, retiring its location, so a floor later re-created at that number is
    //     new. This is the same policy setCount uses on a shrink.
    // Because the relocation (epoch bump) happens HERE, at the origin, the bumped epoch then travels to the other
    // shards through the shared config, where applySharedConfig adopts it without bumping again.
    public void applyConfigsFromEditor(java.util.List<DungeonFloorConfig> incoming) {
        int target = Math.max(0, incoming.size());
        int survivors = Math.min(floors.size(), target);
        for (int i = 0; i < survivors; i++) {
            DungeonFloorConfig old = floors.get(i);
            DungeonFloorConfig inc = incoming.get(i);
            if (inc != null && buildIdentityDiffers(old, inc)) {
                relocateFloor(i + 1);
            }
        }
        for (int n = floors.size(); n > target; n--) {
            relocateFloor(n);
        }
        applyConfigs(incoming);
    }

    // whether two configs describe a DIFFERENT build, i.e. one that must be re-generated on fresh ground rather than
    // edited in place. Theme picks the dimension and material family, type picks NORMAL rooms vs a BOSS arena, and
    // layout style picks the room algorithm; a change to any of the three means the existing geometry no longer
    // matches the config. Size and depth are deliberately NOT here: they keep the existing lazy contract (a size
    // change takes effect only on the next generation), so tweaking a floor's size does not silently discard a
    // hand-built floor. Compared through the canonicalisers so a case-only difference is not treated as a change.
    private static boolean buildIdentityDiffers(DungeonFloorConfig a, DungeonFloorConfig b) {
        if (a == null || b == null) {
            return true;
        }
        String themeA = DungeonFloorConfig.canonicalTheme(a.theme);
        String themeB = DungeonFloorConfig.canonicalTheme(b.theme);
        String typeA = DungeonFloorConfig.canonicalType(a.type);
        String typeB = DungeonFloorConfig.canonicalType(b.type);
        String styleA = a.layoutStyle == null ? "" : a.layoutStyle.trim();
        String styleB = b.layoutStyle == null ? "" : b.layoutStyle.trim();
        return !themeA.equals(themeB) || !typeA.equals(typeB) || !styleA.equalsIgnoreCase(styleB);
    }

    // cross-server SYNC receiver: adopt a sibling shard's floor list AND its relocation-epoch map. The epoch is an
    // absolute location pointer set by whoever made the edit, so this NEVER re-bumps (which would diverge): it just
    // takes the higher of the two per number and, for any floor whose epoch rose, drops this shard's own geometry so
    // it rebuilds at the new place on its next visit. That way both shards land floor N in the same location while
    // each keeps building its own copy of the terrain there.
    public void applySharedConfig(java.util.List<DungeonFloorConfig> incoming,
                                  Map<Integer, Integer> incomingEpochs) {
        applyConfigs(incoming);
        if (incomingEpochs != null) {
            for (Map.Entry<Integer, Integer> e : incomingEpochs.entrySet()) {
                adoptEpoch(e.getKey(), e.getValue());
            }
        }
    }

    // replace a floor's whole config (used by the later GUI save path). no-op if out of range.
    public void setFloor(int floorNumber, DungeonFloorConfig config) {
        if (isValidFloor(floorNumber) && config != null) {
            floors.set(floorNumber - 1, config);
            setDirty();
        }
    }

    // in-place field setters used by the /rg dungeon floor subcommands. each marks dirty so the change persists.
    public boolean setTheme(int floorNumber, String theme) {
        DungeonFloorConfig c = get(floorNumber);
        if (c == null) {
            return false;
        }
        c.theme = theme;
        setDirty();
        return true;
    }

    public boolean setSize(int floorNumber, int size) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            if (ev == null || !ev.has(floorNumber)) {
                return false;
            }
            ev.setSize(floorNumber, size);
            return true;
        }
        DungeonFloorConfig c = get(floorNumber);
        if (c == null) {
            return false;
        }
        c.size = size;
        setDirty();
        return true;
    }

    public boolean setDepth(int floorNumber, int depth) {
        DungeonFloorConfig c = get(floorNumber);
        if (c == null) {
            return false;
        }
        c.depth = depth;
        setDirty();
        return true;
    }

    public boolean setRequireTicket(int floorNumber, boolean require) {
        DungeonFloorConfig c = get(floorNumber);
        if (c == null) {
            return false;
        }
        c.requireTicket = require;
        setDirty();
        return true;
    }

    // take a floor in or out of rotation. Purely a progression-gating flag: it never touches the floor's terrain,
    // generated flag, layout or boss-defeat state, so a floor can be pulled from rotation and put back with nothing
    // lost. Travels in saveSharedConfig (it is written by DungeonFloorConfig.save), so an edit here reaches every shard.
    public boolean setInRotation(int floorNumber, boolean inRotation) {
        DungeonFloorConfig c = get(floorNumber);
        if (c == null) {
            return false;
        }
        c.inRotation = inRotation;
        setDirty();
        return true;
    }

    public boolean isGenerated(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            return ev != null && ev.isGenerated(floorNumber);
        }
        return generated.contains(floorNumber);
    }

    public void markGenerated(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            if (ev != null) {
                ev.markGenerated(floorNumber);
            }
            return;
        }
        if (generated.add(floorNumber)) {
            setDirty();
        }
    }

    /**
     * Forget EVERYTHING remembered about a floor number: its generated flag, its rolled layout and its boss-defeat
     * state. This is what makes deleting a floor actually delete it.
     *
     * <p>All three sets are keyed by floor NUMBER, not by config identity, and dropping a floor only shortened the
     * config list. So the number was left carrying the old floor's state, and the next floor created at that number
     * inherited it: already marked generated (therefore never re-stamped) and still holding the previous rolled
     * layout. That is why a deleted floor came back, and came back with the same broken geometry.
     *
     * <p>The old behaviour was deliberate, to stop a shrink-then-grow from re-stamping over hand-built detail. That
     * protection is right for a floor that still exists and wrong for one that has been removed, which is the
     * distinction that was missing.
     */
    public void purgeFloor(int floorNumber) {
        boolean changed = generated.remove(floorNumber);
        changed |= bossDefeated.remove(floorNumber);
        changed |= layouts.remove(floorNumber) != null;
        if (changed) {
            setDirty();
        }
    }

    // the current relocation epoch for a floor number (0 if never relocated). DungeonFloorLayout.cellCentre turns
    // this into the floor's Z offset, so every caller that needs a floor's world origin must pass this.
    public int relocationEpoch(int floorNumber) {
        // event floors never relocate: their column is fixed on the negative-z lane (cellCentre ignores the epoch for
        // them), and their store is dropped and rebuilt rather than moved. So the epoch is always 0.
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            return 0;
        }
        return relocationEpochs.getOrDefault(floorNumber, 0);
    }

    /**
     * Send a floor to fresh, never-built ground: bump its relocation epoch (which moves its next build a full
     * CELL_SPACING along +Z) and drop ALL of its geometry (generated flag, rolled layout, boss-defeat state) so the
     * next visit rebuilds from scratch there. The floor's OLD build is deliberately left where it stood rather than
     * erased in place, which is why this only moves the pointer and clears the flags: erasing an arbitrary stamped
     * layout cleanly is far harder and riskier than walking to empty ground.
     *
     * <p>This is the single origin of an epoch bump. It is called when a floor is REMOVED (setCount / editor shrink),
     * when it is RETYPED (theme, type or layout style changed through the editor or a command), and by the regen
     * command. The epoch and the geometry are cleared together ON PURPOSE: an epoch that moved without the layout
     * being dropped would leave the standing build unaddressable, so the two must never be separated.
     *
     * <p>Because the bump happens here, on the server that made the edit, the new epoch then rides the shared config
     * to every other shard, where applySharedConfig adopts it (see adoptEpoch) instead of bumping again.
     */
    public void relocateFloor(int floorNumber) {
        int next = DungeonFloorLayout.clampedEpoch(relocationEpochs.getOrDefault(floorNumber, 0) + 1);
        relocationEpochs.put(floorNumber, next);
        generated.remove(floorNumber);
        bossDefeated.remove(floorNumber);
        layouts.remove(floorNumber);
        setDirty();
    }

    // SYNC-only: adopt an epoch chosen by another shard. Absolute set-to-the-higher-value, never an increment, so
    // replaying the same edit converges instead of diverging. When the epoch actually rises, this shard's own
    // geometry for that floor is dropped so it rebuilds at the new location (each shard builds its own terrain).
    private void adoptEpoch(int floorNumber, int epoch) {
        int clamped = DungeonFloorLayout.clampedEpoch(epoch);
        if (clamped > relocationEpochs.getOrDefault(floorNumber, 0)) {
            relocationEpochs.put(floorNumber, clamped);
            generated.remove(floorNumber);
            bossDefeated.remove(floorNumber);
            layouts.remove(floorNumber);
            setDirty();
        }
    }

    // admin override: clear a floor's generated flag so the next visit re-stamps fresh terrain. NOT used in normal
    // operation (a generated floor is never re-stamped); exposed only for the /rg dungeon floor regen testing command.
    public void clearGenerated(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            if (ev != null) {
                ev.clearGenerated(floorNumber);
            }
            return;
        }
        if (generated.remove(floorNumber)) {
            setDirty();
        }
    }

    /**
     * Drop a floor's rolled layout so the next generation rolls a NEW one. Clearing the generated flag alone only
     * re-stamps the same rooms, which is why regenerating a floor whose layout came out wrong reproduced the same
     * wrong floor.
     */
    public boolean clearLayout(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            return ev != null && ev.clearLayout(floorNumber);
        }
        if (layouts.remove(floorNumber) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    // has this floor's guardian boss been defeated? unlocks the floor's next-floor portal.
    public boolean isBossDefeated(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            return ev != null && ev.isBossDefeated(floorNumber);
        }
        return bossDefeated.contains(floorNumber);
    }

    // mark this floor's boss defeated (called from the boss-death handler); persists so the descent stays unlocked.
    public void markBossDefeated(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            if (ev != null) {
                ev.markBossDefeated(floorNumber);
            }
            return;
        }
        if (bossDefeated.add(floorNumber)) {
            setDirty();
        }
    }

    // admin override: re-lock a floor so its boss respawns on the next visit and its next-floor portal seals again.
    public boolean clearBossDefeated(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            if (ev == null || !ev.isBossDefeated(floorNumber)) {
                return false;
            }
            ev.clearBossDefeated(floorNumber);
            return true;
        }
        if (bossDefeated.remove(floorNumber)) {
            setDirty();
            return true;
        }
        return false;
    }

    // the rolled layout for a floor, or null if it has never been rolled. presence is the "never re-roll" guard.
    public DungeonRoomLayout getLayout(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            return ev == null ? null : ev.getLayout(floorNumber);
        }
        return layouts.get(floorNumber);
    }

    // record a freshly rolled layout. called ONCE per floor, ever; the caller only rolls when getLayout returned null.
    public void setLayout(int floorNumber, DungeonRoomLayout layout) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            if (ev != null) {
                ev.setLayout(floorNumber, layout);
            }
            return;
        }
        if (layout != null) {
            layouts.put(floorNumber, layout);
            setDirty();
        }
    }

    // flip a floor's layout to placed (blocks fully written), so the room layer is never re-placed on a later visit.
    public void markLayoutPlaced(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            if (ev != null) {
                ev.markLayoutPlaced(floorNumber);
            }
            return;
        }
        DungeonRoomLayout layout = layouts.get(floorNumber);
        if (layout != null && !layout.placed) {
            layout.placed = true;
            setDirty();
        }
    }

    // flip a floor's layout to sealed (the post-placement enclosure pass finished: doorways widened, outward faces
    // sealed, inter-level shaft opened), so that pass is never re-run on a later visit.
    // flip a floor's layout to grounded (the ground pass finished: every open column between its rooms is covered),
    // so the pass is never re-run on a later visit.
    public void markLayoutGrounded(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            if (ev != null) {
                ev.markLayoutGrounded(floorNumber);
            }
            return;
        }
        DungeonRoomLayout layout = layouts.get(floorNumber);
        if (layout != null && !layout.grounded) {
            layout.grounded = true;
            setDirty();
        }
    }

    public void markLayoutSealed(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            if (ev != null) {
                ev.markLayoutSealed(floorNumber);
            }
            return;
        }
        DungeonRoomLayout layout = layouts.get(floorNumber);
        if (layout != null && !layout.sealed) {
            layout.sealed = true;
            setDirty();
        }
    }

    // flip a floor's layout to connected (the post-placement block-connection pass finished: neighbour-dependent block
    // states recomputed and floating attachments fixed/removed), so that pass is never re-run on a later visit.
    public void markLayoutConnected(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            if (ev != null) {
                ev.markLayoutConnected(floorNumber);
            }
            return;
        }
        DungeonRoomLayout layout = layouts.get(floorNumber);
        if (layout != null && !layout.connected) {
            layout.connected = true;
            setDirty();
        }
    }

    // flip a floor's layout to crated (the post-connection crate-conversion pass finished: every placed vanilla chest
    // turned into a crate_chest and the promoted share of barrels into a crate_barrel). Kept separate so a floor built
    // before crate blocks existed converts on its next visit, and so an interrupted pass re-runs (only real vanilla
    // chests/barrels are ever touched, so re-running never double-converts).
    public void markLayoutCrated(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            if (ev != null) {
                ev.markLayoutCrated(floorNumber);
            }
            return;
        }
        DungeonRoomLayout layout = layouts.get(floorNumber);
        if (layout != null && !layout.crated) {
            layout.crated = true;
            setDirty();
        }
    }

    // flip a floor's layout to spawned (the post-crate spawner-placement pass finished: an advanced spawn block dropped
    // into the floor block under each harvested enemy marker, disguised as that block and configured from one of
    // the floor's weighted enemy presets). Kept separate so a floor built before spawner placement existed populates on
    // its next visit, and so an interrupted pass re-runs (a marker whose floor block is already a spawner is skipped).
    public void markLayoutSpawned(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            if (ev != null) {
                ev.markLayoutSpawned(floorNumber);
            }
            return;
        }
        DungeonRoomLayout layout = layouts.get(floorNumber);
        if (layout != null && !layout.spawned) {
            layout.spawned = true;
            setDirty();
        }
    }

    // admin override: clear a floor's crated flag so the crate-conversion pass re-runs on the next visit. Used by
    // /rg dungeon floor recrate to pick up any vanilla containers a config change or a hand-edit added. It never re-rolls the
    // tier of an ALREADY-converted crate (those are no longer vanilla chests, so the pass skips them). Returns true if
    // a layout existed and its flag was cleared (or was already clear).
    public boolean clearCrated(int floorNumber) {
        DungeonRoomLayout layout = getLayout(floorNumber);
        if (layout == null) {
            return false;
        }
        if (layout.crated) {
            layout.crated = false;
            markDirtyFor(floorNumber);
        }
        return true;
    }

    // admin override: clear a floor's sealed flag so the enclosure pass (opening carve + outward-face seal) re-runs on
    // the next visit. Also re-arms the block-connection pass, since resealing edits walls that the connection states
    // depend on. The layout itself is untouched (rooms never re-roll); only the once-per-floor passes are re-armed.
    // exposed for the /rg dungeon floor reseal testing command. Returns true if a layout existed and its flag was cleared.
    public boolean clearSealed(int floorNumber) {
        DungeonRoomLayout layout = getLayout(floorNumber);
        if (layout == null) {
            return false;
        }
        if (layout.sealed || layout.connected) {
            layout.sealed = false;
            layout.connected = false;
            markDirtyFor(floorNumber);
        }
        return true;
    }

    // set dirty on whichever store owns this floor number, so an in-place layout-flag edit persists whether the floor
    // is an ordinary floor (this SavedData) or a virtual event floor (the separate event store).
    private void markDirtyFor(int floorNumber) {
        if (floorNumber >= DungeonFloorLayout.EVENT_FLOOR_BASE) {
            DungeonEventFloorState ev = events();
            if (ev != null) {
                ev.setDirty();
            }
            return;
        }
        setDirty();
    }

    // The ADMIN-editable half of this SavedData, and ONLY that half: the ordered floor-config list (theme, size,
    // depth, requireTicket and the floor count implied by its length). Deliberately excludes the generated flags,
    // rolled layouts and boss-defeat state, which are this world's own terrain and per-run progress: those describe
    // blocks that exist on THIS server and would be a lie carried onto another (a floor marked built with no terrain
    // under it). So a floor edit made in the menu travels between servers while each server keeps its own geometry.
    public CompoundTag saveSharedConfig(CompoundTag tag) {
        ListTag list = new ListTag();
        for (DungeonFloorConfig c : floors) {
            list.add(c.save(new CompoundTag()));
        }
        tag.put("floors", list);
        // the relocation epochs travel WITH the config (see the field doc): every shard must agree where floor N
        // currently is. The FULL map is written, including retired numbers past the current floor count, so a floor
        // re-created at a removed number lands on the same fresh ground on every shard.
        tag.put("relocationEpochs", saveEpochs());
        return tag;
    }

    // cross-server state sync write path: take the floor configs a sibling server edited and apply them to THIS live
    // instance through applyConfigs, which already grows/shrinks the list and leaves each surviving floor's terrain
    // and progress untouched (an edited theme/depth only takes effect when that floor is next generated). Nothing
    // outside the config list is read, so incoming generation state, if any is present, is ignored.
    public void loadInto(CompoundTag tag) {
        List<DungeonFloorConfig> incoming = new ArrayList<>();
        ListTag list = tag.getList("floors", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            incoming.add(DungeonFloorConfig.load(list.getCompound(i)));
        }
        // adopt the sibling shard's epochs too (older shards send none, which reads as an empty map: those floors
        // simply stay at whatever epoch this shard already has, i.e. wherever it already built them).
        applySharedConfig(incoming, loadEpochs(tag));
    }

    // written as a list of {floor,epoch} pairs (never a compound keyed by number), so the wire and disk form match
    // the layouts list above and there is no string-int key parsing. Zero entries are skipped: an absent epoch reads
    // back as 0, so writing them would only bloat the tag.
    private ListTag saveEpochs() {
        ListTag list = new ListTag();
        // SORTED BY FLOOR, not HashMap order, for the same reason DungeonCrateData.save sorts: this runs inside
        // saveSharedConfig, which is the read supplier for the cross server state sync, and that publishes whenever
        // the serialised BYTES change. HashMap iteration order depends on insertion history, so two servers holding
        // the SAME epochs wrote them in different orders, each read the other as a change, and republished at each
        // other every poll for ever. Seen live between smp and OW1 writing [8,1,2,3,4,5] against [1,2,3,4,5,8].
        // Order carries no meaning on load (loadEpochs reads pairs into a map), so sorting is free.
        List<Integer> numbers = new ArrayList<>(relocationEpochs.keySet());
        Collections.sort(numbers);
        for (Integer floorNumber : numbers) {
            Integer epoch = relocationEpochs.get(floorNumber);
            if (epoch == null || epoch <= 0) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("floor", floorNumber);
            entry.putInt("epoch", epoch);
            list.add(entry);
        }
        return list;
    }

    private static Map<Integer, Integer> loadEpochs(CompoundTag tag) {
        Map<Integer, Integer> epochs = new HashMap<>();
        ListTag list = tag.getList("relocationEpochs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            epochs.put(entry.getInt("floor"), entry.getInt("epoch"));
        }
        return epochs;
    }

    public static DungeonFloors load(CompoundTag tag) {
        DungeonFloors f = new DungeonFloors();
        // the fresh-world constructor seeded default floors; replace them wholesale with the persisted list so a
        // saved world takes exactly what was stored (an empty stored list means zero floors, which is honoured).
        f.floors.clear();
        ListTag list = tag.getList("floors", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            f.floors.add(DungeonFloorConfig.load(list.getCompound(i)));
        }
        // relocation epochs are pure location pointers, not terrain, so they are loaded regardless of the terrain
        // schema below and are NOT cleared by the schema migration: a pre-relocation save simply has none (every
        // floor at epoch 0, where it was built). Loaded before the schema block so both the migrate and the normal
        // path keep them.
        f.relocationEpochs.clear();
        f.relocationEpochs.putAll(loadEpochs(tag));
        // schema migration: an absent version means the pre-pivot (stamp-into-flat-dim) scheme. Any stored
        // generation state describes terrain at the old x = N * 65536 coordinates in the old flat dim, which is
        // now orphaned. Drop the generated flags and the rolled layouts so floors re-roll into the themed dims;
        // keep the floor configs (theme/size/depth) so admin edits survive the pivot.
        int storedVersion = tag.contains("schema") ? tag.getInt("schema") : 0;
        if (storedVersion < SCHEMA_VERSION) {
            Shuruis_dmz_dungeons.LOGGER.info(
                    "[{}] Migrating dungeon floor data from schema {} to {}: clearing stale generation flags and "
                            + "layouts so floors re-roll into the themed dimensions (floor configs kept).",
                    Shuruis_dmz_dungeons.MODID, storedVersion, SCHEMA_VERSION);
            f.generated.clear();
            f.layouts.clear();
            f.setDirty();
            return f;
        }

        f.generated.clear();
        for (int n : tag.getIntArray("generated")) {
            f.generated.add(n);
        }
        f.bossDefeated.clear();
        for (int n : tag.getIntArray("bossDefeated")) {
            f.bossDefeated.add(n);
        }
        f.layouts.clear();
        ListTag layoutList = tag.getList("layouts", Tag.TAG_COMPOUND);
        for (int i = 0; i < layoutList.size(); i++) {
            CompoundTag entry = layoutList.getCompound(i);
            int floor = entry.getInt("floor");
            DungeonRoomLayout layout = DungeonRoomLayout.load(entry.getCompound("layout"));
            // the RAMPARTS and BLACK_BRIDGE archetypes were removed, so their room NBTs are gone. Migrate a saved layout
            // that used either:
            //   * not yet written into the world -> DROP it so beginFloor re-rolls this floor under the config default
            //     (placing the now-deleted pieces would just yield the magenta-wool fallback);
            //   * already placed -> KEEP the rooms already standing in the world (never re-rolled) but relabel the
            //     style to a SURVIVING archetype so the enclosure pass and the reports resolve to a live one. RAMPARTS
            //     (an enclosed type) becomes SPINE_AND_RIBS; BLACK_BRIDGE (an OPEN arena) becomes ROADS_AND_BUILDINGS,
            //     the surviving open archetype, so the enclosure pass keeps treating the standing arena as unsealed
            //     (both are sealsOutward=false) rather than trying to wall in an open floor.
            boolean wasRamparts = "RAMPARTS".equalsIgnoreCase(layout.style);
            boolean wasBlackBridge = "BLACK_BRIDGE".equalsIgnoreCase(layout.style);
            if (wasRamparts || wasBlackBridge) {
                String removed = layout.style;
                String fallback = wasBlackBridge ? "ROADS_AND_BUILDINGS" : "SPINE_AND_RIBS";
                if (!layout.placed) {
                    Shuruis_dmz_dungeons.LOGGER.warn(
                            "[{}] Floor {} had an unplaced {} layout (archetype removed); dropping it so the floor "
                                    + "re-rolls under the config default.", Shuruis_dmz_dungeons.MODID, floor, removed);
                    f.setDirty();
                    continue;
                }
                Shuruis_dmz_dungeons.LOGGER.warn(
                        "[{}] Floor {} had a placed {} layout (archetype removed); keeping the built rooms and "
                                + "relabelling its style to {}.", Shuruis_dmz_dungeons.MODID, floor, removed, fallback);
                DungeonRoomLayout migrated = new DungeonRoomLayout(layout.seed, fallback, layout.placements,
                        layout.placed, layout.originY);
                migrated.sealed = layout.sealed;
                migrated.connected = layout.connected;
                migrated.crated = layout.crated;
                layout = migrated;
                f.setDirty();
            }
            f.layouts.put(floor, layout);
        }
        return f;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("schema", SCHEMA_VERSION);
        ListTag list = new ListTag();
        for (DungeonFloorConfig c : floors) {
            list.add(c.save(new CompoundTag()));
        }
        tag.put("floors", list);
        int[] gen = new int[generated.size()];
        int i = 0;
        for (int n : generated) {
            gen[i++] = n;
        }
        tag.putIntArray("generated", gen);
        int[] defeated = new int[bossDefeated.size()];
        int di = 0;
        for (int n : bossDefeated) {
            defeated[di++] = n;
        }
        tag.putIntArray("bossDefeated", defeated);
        ListTag layoutList = new ListTag();
        for (Map.Entry<Integer, DungeonRoomLayout> e : layouts.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("floor", e.getKey());
            entry.put("layout", e.getValue().save());
            layoutList.add(entry);
        }
        tag.put("layouts", layoutList);
        tag.put("relocationEpochs", saveEpochs());
        return tag;
    }
}
