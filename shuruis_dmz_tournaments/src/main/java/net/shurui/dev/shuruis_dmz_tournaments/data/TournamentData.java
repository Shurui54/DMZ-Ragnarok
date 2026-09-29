package net.shurui.dev.shuruis_dmz_tournaments.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.shurui.dev.shuruis_dmz_tournaments.reward.TitleDisplay;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentDef;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// persistent state: all defs, current holder of each title, last scheduled fire time per tournament
// (de-dupes auto-runs). live brackets stay in memory in TournamentManager.
public class TournamentData extends SavedData {
    private static final String NAME = "shuruis_dmz_tournaments";

    private final Map<String, TournamentDef> defs = new LinkedHashMap<>();
    private final Map<String, UUID> titleHolders = new LinkedHashMap<>();
    // Grant time per current holder, epoch millis. Tie-breaker that races a revoke against a re-grant across the
    // shard network by comparison with the tombstone times below. Lock-step with titleHolders.
    private final Map<String, Long> titleHolderSince = new LinkedHashMap<>();
    // Revocation tombstones: title id -> clear time, epoch millis. A dated marker so the removal TRAVELS with the
    // payload and a sibling still showing the old holder cannot merge it back. Aged out by TOMBSTONE_TTL_MILLIS.
    private final Map<String, Long> titleTombstones = new LinkedHashMap<>();
    private final Map<String, Long> lastScheduledRun = new LinkedHashMap<>();

    // Tombstone retention. Thirty days, matching the user-list and permission syncs: the window bounds correctness
    // across downtime. A shard offline for LESS adopts the revoke on return (the tombstone still contradicts its
    // stale holder); one offline for LONGER returns with the old grant and no tombstone to beat it, and re-seeds it.
    private static final long TOMBSTONE_TTL_MILLIS = 30L * 24L * 60L * 60L * 1000L;

    // "since" for a holder from an OLD save or old sibling payload that predates the field. Tiny constant, not
    // "now": means "granted before we tracked this", so any real clear or re-grant beats it while two migrated
    // copies compare equal and never churn. Never zero, so it stays distinct from an absent entry.
    private static final long MIGRATED_SINCE = 1L;

    public static TournamentData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld.getDataStorage().computeIfAbsent(TournamentData::load, TournamentData::new, NAME);
    }

    public TournamentDef getDef(String id) {
        return id == null ? null : defs.get(id.toLowerCase());
    }

    public void putDef(TournamentDef def) {
        defs.put(def.id.toLowerCase(), def);
        setDirty();
    }

    public void removeDef(String id) {
        if (defs.remove(id.toLowerCase()) != null) setDirty();
    }

    public Map<String, TournamentDef> allDefs() {
        return defs;
    }

    // Only the DEFINITIONS travel between servers (arena/stands/waiting regions, format, rewards), so grounds are one
    // agreed set network-wide. Title holders and the last-run clock do NOT: a holder is world state, the clock a
    // per-server timer, and carrying either would let one server stamp over another.

    // write path: defs only, so this can feed the state engine without dragging titles or schedule along.
    public CompoundTag saveDefsOnly(CompoundTag tag) {
        ListTag defList = new ListTag();
        defs.values().forEach(def -> defList.add(def.save()));
        tag.put("defs", defList);
        return tag;
    }

    // read path: replace the whole def set with a sibling's, so an added, edited or DELETED tournament all travel
    // (a merge would resurrect a deleted one). Titles and schedule untouched.
    public void loadDefsFrom(CompoundTag tag) {
        defs.clear();
        for (Tag t : tag.getList("defs", Tag.TAG_COMPOUND)) {
            TournamentDef def = TournamentDef.load((CompoundTag) t);
            if (def.id != null && !def.id.isBlank()) defs.put(def.id.toLowerCase(), def);
        }
        setDirty();
    }

    // Holders travel between shards: god_of_destruction and angel grant a real energy bar and abilities (TitleManager,
    // TitleDisplay), so one-holder-per-title has to hold network-wide. Belt only moves by admin grant, so
    // last-write-wins is fine.

    // write path: holders AND tombstones, so a clear travels as a dated marker, not a mere absence. Each holder
    // carries its grant time and each tombstone its clear time, so the far side decides a grant-vs-clear race by age
    // not arrival order. Tombstones are pruned on write, so an aged-out clear stops republishing.
    public CompoundTag saveTitlesOnly(CompoundTag tag) {
        pruneTombstones();
        ListTag titleList = new ListTag();
        titleHolders.forEach((id, holder) -> {
            CompoundTag e = new CompoundTag();
            e.putString("id", id);
            e.putUUID("holder", holder);
            e.putLong("since", titleHolderSince.getOrDefault(id, MIGRATED_SINCE));
            titleList.add(e);
        });
        tag.put("titles", titleList);

        ListTag tombList = new ListTag();
        titleTombstones.forEach((id, at) -> {
            CompoundTag e = new CompoundTag();
            e.putString("id", id);
            e.putLong("at", at);
            tombList.add(e);
        });
        tag.put("tombstones", tombList);
        return tag;
    }

    // read path: MERGE each title PER ID by AGE, honouring tombstones, so one-holder-per-title holds network-wide and
    // a revoke is not silently undone. A title id absent from the tag is left untouched (absent != deleted); a revoke
    // arrives as an explicit tombstone. Deliberately a merge, unlike loadDefsFrom's full replace: a def delete travels
    // wholesale, but a title must never be stripped just because one sibling has not seen the grant yet.
    //
    // Every ambiguous branch fails toward KEEPING the title, because this edits live shared data: a wrongly kept belt
    // is cosmetic, a wrongly revoked one silently strips a player's energy bar and abilities. So an incoming grant
    // wins a tie with our tombstone (grant.since >= tombstone.at), and an incoming tombstone wins only when STRICTLY
    // newer than our grant (tombstone.at > holder.since). A grant without a "since" (older sibling mid-rollout) is
    // treated as MIGRATED_SINCE: old enough a real clear beats it, present enough to be adopted not dropped.
    //
    // server is passed so a tombstone revoking a title held by someone online HERE clears their scoreboard tag at
    // once; the energy bar and abilities read this map live, so they stop on the next energy tick with no relog.
    public void loadTitlesFrom(MinecraftServer server, CompoundTag tag) {
        boolean changed = false;

        // Grants first. Adopted when at least as new as anything we hold or tombstoned for that id; adopting lifts
        // our own tombstone, since a re-grant after a clear must win.
        for (Tag t : tag.getList("titles", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            String id = e.getString("id");
            if (id == null || id.isBlank() || !e.hasUUID("holder")) continue;
            UUID holder = e.getUUID("holder");
            long since = e.contains("since") ? e.getLong("since") : MIGRATED_SINCE;

            long localTomb = titleTombstones.getOrDefault(id, Long.MIN_VALUE);
            long localSince = titleHolders.containsKey(id)
                    ? titleHolderSince.getOrDefault(id, MIGRATED_SINCE) : Long.MIN_VALUE;
            // Fail toward keeping: >= the tombstone, so a grant ties out in the title's favour.
            if (since < localTomb) continue;
            if (holder.equals(titleHolders.get(id)) && since <= localSince) continue;

            titleHolders.put(id, holder);
            titleHolderSince.put(id, since);
            titleTombstones.remove(id);
            changed = true;
        }

        // Then tombstones. A clear not strictly newer than the live grant is stale (a re-grant already won) and is
        // dropped whole, so a superseded clear neither revokes nor lingers. Otherwise recorded (max age) and revokes.
        for (Tag t : tag.getList("tombstones", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            String id = e.getString("id");
            if (id == null || id.isBlank() || !e.contains("at")) continue;
            long at = e.getLong("at");

            UUID holder = titleHolders.get(id);
            long localSince = holder == null ? Long.MIN_VALUE
                    : titleHolderSince.getOrDefault(id, MIGRATED_SINCE);
            // Keep on a tie: at == localSince keeps the title, so a clear must be STRICTLY newer to bite.
            if (holder != null && at <= localSince) continue;

            long localTomb = titleTombstones.getOrDefault(id, Long.MIN_VALUE);
            if (at > localTomb) {
                titleTombstones.put(id, at);
                changed = true;
            }
            if (holder != null) {
                titleHolders.remove(id);
                titleHolderSince.remove(id);
                changed = true;
                // Reached the holder's shard: strip the scoreboard tag now for anyone online. Energy bar and ability
                // checks read this map live, so they lapse on the next energy tick without a relog.
                if (server != null) {
                    ServerPlayer p = server.getPlayerList().getPlayer(holder);
                    if (p != null) TitleDisplay.clear(p);
                }
            }
        }
        if (changed) setDirty();
    }

    // Drop tombstones past TOMBSTONE_TTL_MILLIS. A pruned one stops republishing, so a clear older than the window no
    // longer holds down its id and a future grant on it is unobstructed.
    private void pruneTombstones() {
        long cutoff = System.currentTimeMillis() - TOMBSTONE_TTL_MILLIS;
        if (titleTombstones.values().removeIf(at -> at < cutoff)) setDirty();
    }

    public UUID getTitleHolder(String titleId) {
        return titleHolders.get(titleId);
    }

    public void setTitleHolder(String titleId, UUID holder) {
        if (holder == null) {
            // A clear leaves a dated tombstone so the removal travels and cannot be merged back. Single choke point
            // for both the admin clear command and clearAll, so both get the tombstone.
            titleHolders.remove(titleId);
            titleHolderSince.remove(titleId);
            titleTombstones.put(titleId, System.currentTimeMillis());
        } else {
            // A grant stamps its time and lifts any prior tombstone, so a re-grant out-dates an earlier clear and is
            // never clawed back by that clear's still-travelling marker.
            titleHolders.put(titleId, holder);
            titleHolderSince.put(titleId, System.currentTimeMillis());
            titleTombstones.remove(titleId);
        }
        setDirty();
    }

    /**
     * Title ids this player holds, from the holder map ALONE. The holder is world data (this SavedData); the title's
     * definition is a config OUTSIDE the save. Wiping that config while keeping the world leaves the holder intact
     * but absent from {@code Config.TITLE_DEFS}, so callers that walked the config reported nothing held: the
     * "players losing their titles" report. The map is the truth for who holds what; config is presentation only.
     * Do not reintroduce a config filter here.
     */
    public java.util.List<String> heldTitleIds(UUID player) {
        java.util.List<String> held = new java.util.ArrayList<>();
        if (player == null) return held;
        titleHolders.forEach((id, holder) -> {
            if (player.equals(holder)) held.add(id);
        });
        return held;
    }

    public long getLastScheduledRun(String defId) {
        return lastScheduledRun.getOrDefault(defId, 0L);
    }

    public void setLastScheduledRun(String defId, long epochMillis) {
        lastScheduledRun.put(defId, epochMillis);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag defList = new ListTag();
        defs.values().forEach(def -> defList.add(def.save()));
        tag.put("defs", defList);

        pruneTombstones();
        ListTag titleList = new ListTag();
        titleHolders.forEach((id, holder) -> {
            CompoundTag e = new CompoundTag();
            e.putString("id", id);
            e.putUUID("holder", holder);
            e.putLong("since", titleHolderSince.getOrDefault(id, MIGRATED_SINCE));
            titleList.add(e);
        });
        tag.put("titles", titleList);

        // Persist tombstones so a restart within the window still contradicts a stale holder a returning sibling
        // carries. Without this a clear would live in memory only and be lost on the next reboot.
        ListTag tombList = new ListTag();
        titleTombstones.forEach((id, at) -> {
            CompoundTag e = new CompoundTag();
            e.putString("id", id);
            e.putLong("at", at);
            tombList.add(e);
        });
        tag.put("tombstones", tombList);

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

    public static TournamentData load(CompoundTag tag) {
        TournamentData data = new TournamentData();
        for (Tag t : tag.getList("defs", Tag.TAG_COMPOUND)) {
            TournamentDef def = TournamentDef.load((CompoundTag) t);
            if (def.id != null && !def.id.isBlank()) data.defs.put(def.id.toLowerCase(), def);
        }
        for (Tag t : tag.getList("titles", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            String id = e.getString("id");
            data.titleHolders.put(id, e.getUUID("holder"));
            // Pre-field save migrates to MIGRATED_SINCE: old enough a real clear beats it, present enough the grant
            // is honoured until one arrives.
            data.titleHolderSince.put(id, e.contains("since") ? e.getLong("since") : MIGRATED_SINCE);
        }
        for (Tag t : tag.getList("tombstones", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            String id = e.getString("id");
            if (id != null && !id.isBlank() && e.contains("at")) data.titleTombstones.put(id, e.getLong("at"));
        }
        for (Tag t : tag.getList("schedule", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            data.lastScheduledRun.put(e.getString("id"), e.getLong("when"));
        }
        return data;
    }
}
