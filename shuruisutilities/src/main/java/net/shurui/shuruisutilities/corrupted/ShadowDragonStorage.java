package net.shurui.shuruisutilities.corrupted;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Overworld-attached saved data for the wish-tracking subsystem. Mirrors {@link
 * net.shurui.shuruisutilities.commands.player.VanishStorage}: persisted with the overworld data storage.
 *
 * <p>Holds the server-wide lifetime count of dragon ball summons, the armed flag (threshold crossed, so the swap
 * set should be in play), the everFired flag, the positions of the placed swap blocks (so they can be found and
 * cleaned up later), the pending placements (star + X/Z chosen at arm time but not yet placed because their chunk
 * was not loaded, so a later pass can finish them), and the DMZ generateDragonBalls value as it was before arming
 * (so the disarm path can put the admin's original setting back instead of leaving it clobbered). The saved value
 * is -1 until a real 0 or 1 has been captured.
 *
 * <p>This is also the single home for the shadow dragon boss data: the seven per-slot {@link ShadowDragonDef}s
 * edited by the phase-3b GUI, and the live-encounter state that phase-3c will populate. Live state is persisted
 * (not memory-only) on purpose: the raid-bosses addon keeps its live fight state in memory and orphans its bosses
 * permanently on a restart, so here the live dragon UUIDs, the encounter start tick and per-player kill credit all
 * survive a restart so the despawn timeout and kill accounting can resume.
 */
public final class ShadowDragonStorage extends SavedData
{
    private static final String NAME = "shuruisutilities_shadow_dragon";

    private int uses;
    private boolean armed;
    private boolean everFired;
    // Ritual "destroyed set" flags, one per DMZ dragon-ball set. Set true when a player completes the 11th-wish ritual
    // on that set (the set is spent until recreated); read by the per-set radar swap so Earth and Namek dials change
    // independently. Distinct from {@link #armed} (the server-wide corrupted-balls abuse event, which is Earth-side).
    private boolean earthRitualDefiled;
    private boolean namekRitualDefiled;
    private final List<BlockPos> placed = new ArrayList<>();
    private final List<Pending> pending = new ArrayList<>();
    // -1 = unknown/not stored; 0 = was off; 1 = was on
    private int savedGenerateDragonBalls = -1;
    // UUID of the player who most recently defiled the dragon balls (triggered the corrupted event). null until the
    // first defiling. Recorded so the base shadow_dragon race can be granted to the defiler (rule 1): the qualifying
    // act is the defiling itself, not slaying a dragon. Persisted so a diagnostic readout survives a restart.
    private UUID lastDefiler;

    // The seven fixed shadow dragon boss slots (index 1..7 -> def). Lazily seeded from createDefault so the
    // editor always has seven slots to show. Ordered by slot for stable iteration.
    private static final int SLOT_COUNT = 7;
    private final Map<Integer, ShadowDragonDef> defs = new LinkedHashMap<>();

    // Currently-live dragon entity UUID -> the slot index it was spawned for.
    private final Map<UUID, Integer> liveDragons = new HashMap<>();
    // The server game-time (level day-time tick count) at which the current encounter started, so a despawn
    // timeout can be enforced across restarts. -1 = no encounter running.
    private long encounterStartTick = -1L;
    // Per-player LIFETIME kill credit: player UUID -> set of distinct slot indices that player has ever killed.
    // Accumulates across encounters (not per-event) and is deliberately never wiped, so it drives the omega shenron
    // race unlock (all seven distinct slots landed as the killing blow, over the player's lifetime).
    private final Map<UUID, Set<Integer>> killCredit = new HashMap<>();
    // Per-player LIFETIME kill credit earned WHILE the player was the shadow_dragon race: player UUID -> set of
    // distinct slots. A strict subset of killCredit, tracked separately because the shadow dragon transformation
    // unlock requires the seven kills to have happened as the shadow dragon race. Never wiped.
    private final Map<UUID, Set<Integer>> shadowDragonKillCredit = new HashMap<>();

    /**
     * A star whose X/Z has been chosen but whose block has not been placed yet, because its chunk was not loaded at
     * arm time. The surface Y is deliberately not stored: the heightmap of an unloaded chunk is meaningless, so Y is
     * resolved at placement time once the chunk is available.
     */
    public record Pending(int star, int x, int z) {}

    public static ShadowDragonStorage get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(ShadowDragonStorage::load, ShadowDragonStorage::new, NAME);
    }

    private static ShadowDragonStorage load(CompoundTag tag)
    {
        ShadowDragonStorage s = new ShadowDragonStorage();
        s.uses = tag.getInt("uses");
        s.armed = tag.getBoolean("armed");
        s.everFired = tag.getBoolean("everFired");
        s.earthRitualDefiled = tag.getBoolean("earthRitualDefiled");
        s.namekRitualDefiled = tag.getBoolean("namekRitualDefiled");
        s.savedGenerateDragonBalls = tag.contains("savedGen") ? tag.getInt("savedGen") : -1;
        if (tag.hasUUID("lastDefiler"))
            s.lastDefiler = tag.getUUID("lastDefiler");
        ListTag list = tag.getList("placed", Tag.TAG_LONG);
        for (int i = 0; i < list.size(); i++)
            s.placed.add(BlockPos.of(((net.minecraft.nbt.LongTag) list.get(i)).getAsLong()));
        ListTag pendingList = tag.getList("pending", Tag.TAG_COMPOUND);
        for (int i = 0; i < pendingList.size(); i++)
        {
            CompoundTag p = pendingList.getCompound(i);
            s.pending.add(new Pending(p.getInt("star"), p.getInt("x"), p.getInt("z")));
        }
        ListTag defList = tag.getList("dragonDefs", Tag.TAG_COMPOUND);
        for (int i = 0; i < defList.size(); i++)
        {
            CompoundTag defTag = defList.getCompound(i);
            // Detect the one-time migrations ShadowDragonDef.load performs, so we can persist them: the on-disk tag
            // still holds the stale values but the loaded def has been rewritten. One trigger now covers every case:
            // a tag written before the editor could PICK the entity type and the dragon models carries no schema tag
            // (or an older one), which is exactly the condition load() runs its placeholder rewrites under. Flagging
            // dirty rewrites the migrated fields, and the schema tag itself, to disk on the next save, so the
            // migration runs once rather than on every load.
            boolean preSchema = ShadowDragonDef.isLegacySchema(defTag);
            ShadowDragonDef d = ShadowDragonDef.load(defTag);
            s.defs.put(d.index, d);
            if (preSchema)
                s.setDirty(); // rewrite the migrated fields to disk on the next save
        }
        ListTag liveList = tag.getList("liveDragons", Tag.TAG_COMPOUND);
        for (int i = 0; i < liveList.size(); i++)
        {
            CompoundTag l = liveList.getCompound(i);
            s.liveDragons.put(l.getUUID("uuid"), l.getInt("slot"));
        }
        s.encounterStartTick = tag.contains("encounterStartTick") ? tag.getLong("encounterStartTick") : -1L;
        ListTag creditList = tag.getList("killCredit", Tag.TAG_COMPOUND);
        for (int i = 0; i < creditList.size(); i++)
        {
            CompoundTag c = creditList.getCompound(i);
            Set<Integer> slots = new HashSet<>();
            ListTag slotList = c.getList("slots", Tag.TAG_INT);
            for (int j = 0; j < slotList.size(); j++)
                slots.add(((IntTag) slotList.get(j)).getAsInt());
            s.killCredit.put(c.getUUID("player"), slots);
        }
        ListTag formCreditList = tag.getList("shadowDragonKillCredit", Tag.TAG_COMPOUND);
        for (int i = 0; i < formCreditList.size(); i++)
        {
            CompoundTag c = formCreditList.getCompound(i);
            Set<Integer> slots = new HashSet<>();
            ListTag slotList = c.getList("slots", Tag.TAG_INT);
            for (int j = 0; j < slotList.size(); j++)
                slots.add(((IntTag) slotList.get(j)).getAsInt());
            s.shadowDragonKillCredit.put(c.getUUID("player"), slots);
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        tag.putInt("uses", uses);
        tag.putBoolean("armed", armed);
        tag.putBoolean("everFired", everFired);
        tag.putBoolean("earthRitualDefiled", earthRitualDefiled);
        tag.putBoolean("namekRitualDefiled", namekRitualDefiled);
        tag.putInt("savedGen", savedGenerateDragonBalls);
        if (lastDefiler != null)
            tag.putUUID("lastDefiler", lastDefiler);
        ListTag list = new ListTag();
        for (BlockPos pos : placed)
            list.add(net.minecraft.nbt.LongTag.valueOf(pos.asLong()));
        tag.put("placed", list);
        ListTag pendingList = new ListTag();
        for (Pending p : pending)
        {
            CompoundTag t = new CompoundTag();
            t.putInt("star", p.star());
            t.putInt("x", p.x());
            t.putInt("z", p.z());
            pendingList.add(t);
        }
        tag.put("pending", pendingList);

        ListTag defList = new ListTag();
        for (ShadowDragonDef d : defs.values())
            defList.add(d.save());
        tag.put("dragonDefs", defList);

        ListTag liveList = new ListTag();
        for (Map.Entry<UUID, Integer> e : liveDragons.entrySet())
        {
            CompoundTag l = new CompoundTag();
            l.putUUID("uuid", e.getKey());
            l.putInt("slot", e.getValue());
            liveList.add(l);
        }
        tag.put("liveDragons", liveList);
        tag.putLong("encounterStartTick", encounterStartTick);

        ListTag creditList = new ListTag();
        for (Map.Entry<UUID, Set<Integer>> e : killCredit.entrySet())
        {
            CompoundTag c = new CompoundTag();
            c.putUUID("player", e.getKey());
            ListTag slotList = new ListTag();
            for (int slot : e.getValue())
                slotList.add(IntTag.valueOf(slot));
            c.put("slots", slotList);
            creditList.add(c);
        }
        tag.put("killCredit", creditList);

        ListTag formCreditList = new ListTag();
        for (Map.Entry<UUID, Set<Integer>> e : shadowDragonKillCredit.entrySet())
        {
            CompoundTag c = new CompoundTag();
            c.putUUID("player", e.getKey());
            ListTag slotList = new ListTag();
            for (int slot : e.getValue())
                slotList.add(IntTag.valueOf(slot));
            c.put("slots", slotList);
            formCreditList.add(c);
        }
        tag.put("shadowDragonKillCredit", formCreditList);
        return tag;
    }

    // TODO(phase3b): the editor GUI + packets read/write the seven defs below (getDef/allDefs/putDef).
    // TODO(phase3c): spawn/despawn/death-detection uses the live-encounter accessors below; the despawn
    // timeout compares (currentGameTime - getEncounterStartTick()) against the SwapPropLingerTicks-style
    // config option corruptedDragonLifetimeTicks in SUConfig.

    // Ensures all seven slots exist, seeding any missing one from createDefault. Called by the def accessors so
    // the editor always sees seven slots even on a fresh install. Marks dirty only when it actually seeds a slot.
    private void ensureDefsSeeded()
    {
        boolean seeded = false;
        for (int i = 1; i <= SLOT_COUNT; i++)
        {
            if (!defs.containsKey(i))
            {
                defs.put(i, ShadowDragonDef.createDefault(i));
                seeded = true;
            }
        }
        if (seeded)
            setDirty();
    }

    /** The def for a fixed slot (1..7), seeding defaults if absent. Returns null only for an out-of-range index. */
    public ShadowDragonDef getDef(int index)
    {
        if (index < 1 || index > SLOT_COUNT)
            return null;
        ensureDefsSeeded();
        return defs.get(index);
    }

    /** All seven defs in slot order, seeding defaults if absent. */
    public List<ShadowDragonDef> allDefs()
    {
        ensureDefsSeeded();
        List<ShadowDragonDef> out = new ArrayList<>();
        for (int i = 1; i <= SLOT_COUNT; i++)
            out.add(defs.get(i));
        return out;
    }

    /** Store an edited def under its own index. Ignores out-of-range indices. */
    public void putDef(ShadowDragonDef def)
    {
        if (def == null || def.index < 1 || def.index > SLOT_COUNT)
            return;
        defs.put(def.index, def);
        setDirty();
    }

    /** Live dragon entity UUID -> slot index, a copy for safe iteration. */
    public Map<UUID, Integer> getLiveDragons()
    {
        return new HashMap<>(liveDragons);
    }

    public boolean hasLiveDragons()
    {
        return !liveDragons.isEmpty();
    }

    // cheap membership check that avoids the defensive copy getLiveDragons() makes; used by the per-hit damage handler
    public boolean isLiveDragon(UUID entityId)
    {
        return liveDragons.containsKey(entityId);
    }

    public void addLiveDragon(UUID entityId, int slot)
    {
        liveDragons.put(entityId, slot);
        setDirty();
    }

    public void removeLiveDragon(UUID entityId)
    {
        if (liveDragons.remove(entityId) != null)
            setDirty();
    }

    public void clearLiveDragons()
    {
        if (liveDragons.isEmpty())
            return;
        liveDragons.clear();
        setDirty();
    }

    /** Game-time tick the current encounter started, or -1 when none is running. */
    public long getEncounterStartTick()
    {
        return encounterStartTick;
    }

    public void setEncounterStartTick(long tick)
    {
        this.encounterStartTick = tick;
        setDirty();
    }

    public boolean isEncounterActive()
    {
        return encounterStartTick >= 0L;
    }

    /** The set of distinct slot indices a player has ever landed the killing blow on, a copy for safe iteration. */
    public Set<Integer> getKillCredit(UUID player)
    {
        Set<Integer> slots = killCredit.get(player);
        return slots == null ? new HashSet<>() : new HashSet<>(slots);
    }

    public void addKillCredit(UUID player, int slot)
    {
        killCredit.computeIfAbsent(player, k -> new HashSet<>()).add(slot);
        setDirty();
    }

    /**
     * The set of distinct slots a player has landed the killing blow on WHILE playing the shadow_dragon race, a copy
     * for safe iteration. Drives the transformation unlock.
     */
    public Set<Integer> getShadowDragonKillCredit(UUID player)
    {
        Set<Integer> slots = shadowDragonKillCredit.get(player);
        return slots == null ? new HashSet<>() : new HashSet<>(slots);
    }

    public void addShadowDragonKillCredit(UUID player, int slot)
    {
        shadowDragonKillCredit.computeIfAbsent(player, k -> new HashSet<>()).add(slot);
        setDirty();
    }

    /** Wipe all live-encounter state (dragons, start tick, kill credit) at the end of an encounter. */
    public void clearEncounter()
    {
        if (liveDragons.isEmpty() && encounterStartTick < 0L && killCredit.isEmpty())
            return;
        liveDragons.clear();
        encounterStartTick = -1L;
        killCredit.clear();
        setDirty();
    }

    public int getUses()
    {
        return uses;
    }

    public void setUses(int uses)
    {
        this.uses = uses;
        setDirty();
    }

    public int incrementUses()
    {
        uses++;
        setDirty();
        return uses;
    }

    public boolean isArmed()
    {
        return armed;
    }

    public void setArmed(boolean armed)
    {
        this.armed = armed;
        setDirty();
    }

    /**
     * Whether defiled (corrupted) dragon balls of the given set are physically present in the world right now, which is
     * what the radar dial reflects: the shadow-dragon art shows while they are out there to find, the stock art
     * otherwise. {@code set} is the DMZ ball-set id ("earth" / "namek"); any other value returns false.
     *
     * <p>Only Earth ever has defiled balls in the world. The corrupted-balls abuse event scatters and clears them over
     * the overworld only (see {@link net.shurui.shuruisutilities.corrupted.CorruptedScatter} and
     * {@link net.shurui.shuruisutilities.compat.dmz.WishTrackingBridge}), so Earth reports presence off {@link #armed}
     * and Namek always reports false. The ritual "spent" flags do NOT feed this: a set spent by the 11th-wish ritual
     * has no balls in the world, so its dial stays normal. That spent state is a separate question, answered by
     * {@link #isSetRitualSpent(String)}.
     */
    public boolean hasDefiledBallsPresent(String set)
    {
        if ("earth".equalsIgnoreCase(set))
            return armed;
        return false;
    }

    /**
     * Whether the given DMZ ball set has been spent by the 11th-wish ritual (its balls consumed, an idol left behind),
     * so it can only be restored through the recreation ritual. {@code set} is the DMZ ball-set id ("earth" / "namek");
     * any other value returns false. This is the ritual lifecycle question, NOT the radar dial: a spent set has no
     * balls in the world, so it does not light the dial (see {@link #hasDefiledBallsPresent(String)} for that).
     */
    public boolean isSetRitualSpent(String set)
    {
        if ("namek".equalsIgnoreCase(set))
            return namekRitualDefiled;
        if ("earth".equalsIgnoreCase(set))
            return earthRitualDefiled;
        return false;
    }

    /** Set the ritual-defiled flag for one ball set ("earth" / "namek"). No-op on an unknown set. */
    public void setSetDefiled(String set, boolean defiled)
    {
        if ("namek".equalsIgnoreCase(set))
        {
            namekRitualDefiled = defiled;
            setDirty();
        }
        else if ("earth".equalsIgnoreCase(set))
        {
            earthRitualDefiled = defiled;
            setDirty();
        }
    }

    public boolean hasEverFired()
    {
        return everFired;
    }

    public void setEverFired(boolean everFired)
    {
        this.everFired = everFired;
        setDirty();
    }

    public List<BlockPos> getPlaced()
    {
        return new ArrayList<>(placed);
    }

    // cheap emptiness check that avoids the defensive copy getPlaced() makes; used by the retry throttle
    public boolean hasPlaced()
    {
        return !placed.isEmpty();
    }

    public void addPlaced(BlockPos pos)
    {
        placed.add(pos.immutable());
        setDirty();
    }

    public void clearPlaced()
    {
        if (placed.isEmpty())
            return;
        placed.clear();
        setDirty();
    }

    // replace the whole recorded set with the given positions. used by the clear pass to keep only the
    // positions it could not handle (chunk not loaded) instead of dropping every record. no-op when the new
    // set already equals the current one, so a retry that changes nothing does not mark the data dirty.
    public void setPlaced(List<BlockPos> positions)
    {
        if (placed.equals(positions))
            return;
        placed.clear();
        for (BlockPos pos : positions)
            placed.add(pos.immutable());
        setDirty();
    }

    public List<Pending> getPending()
    {
        return new ArrayList<>(pending);
    }

    // cheap emptiness check that avoids the defensive copy getPending() makes; used by the drain throttle
    public boolean hasPending()
    {
        return !pending.isEmpty();
    }

    public void addPending(Pending p)
    {
        pending.add(p);
        setDirty();
    }

    public void clearPending()
    {
        if (pending.isEmpty())
            return;
        pending.clear();
        setDirty();
    }

    // replace the whole pending set with the given entries. used by the drain to keep only the placements it could
    // not finish (chunk still not loaded) instead of dropping the record. no-op when the new set already equals the
    // current one, so a drain that changes nothing does not mark the data dirty.
    public void setPending(List<Pending> entries)
    {
        if (pending.equals(entries))
            return;
        pending.clear();
        pending.addAll(entries);
        setDirty();
    }

    /** UUID of the player who most recently defiled the dragon balls, or null before any defiling. */
    public UUID getLastDefiler()
    {
        return lastDefiler;
    }

    public void setLastDefiler(UUID uuid)
    {
        this.lastDefiler = uuid;
        setDirty();
    }

    public int getSavedGenerateDragonBalls()
    {
        return savedGenerateDragonBalls;
    }

    public void setSavedGenerateDragonBalls(int value)
    {
        this.savedGenerateDragonBalls = value;
        setDirty();
    }
}
