package net.shurui.dev.sdu.saga;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Reads and writes DragonMine Z sagas/quests directly in the world save, matching the layout
 * {@code QuestRegistry} loads: {@code dragonminez/sagas/<id>.json} and
 * {@code dragonminez/quests/<questFolder>/NN_*.json}. Edits apply after a world reload (DMZ scans on
 * load and has no runtime disk-reload).
 */
public final class SagaFileManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** DMZ's built-in sagas (it regenerates them on load); hidden from the editor */
    private static final java.util.Set<String> DEFAULT_SAGA_IDS = java.util.Set.of(
            "saiyan_saga", "frieza_saga", "android_saga", "future_saga", "buu_saga", "movies_saga");

    private SagaFileManager() {
    }

    public static boolean isDefault(String sagaId) {
        return DEFAULT_SAGA_IDS.contains(sagaId);
    }

    private static Path base(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("dragonminez");
    }

    public static List<SagaData> loadAll(MinecraftServer server) {
        List<SagaData> result = new ArrayList<>();
        Path sagaDir = base(server).resolve("sagas");
        Path questsRoot = base(server).resolve("quests");
        if (!Files.isDirectory(sagaDir)) {
            return result;
        }
        Path branchesRoot = base(server).resolve("sidequests");
        try (Stream<Path> stream = Files.list(sagaDir)) {
            List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
            for (Path p : files) {
                try {
                    JsonObject sj = GSON.fromJson(Files.readString(p), JsonObject.class);
                    SagaData s = SagaData.fromSagaJson(sj);
                    loadQuests(questsRoot.resolve(s.questFolder), s);
                    loadBranchQuests(branchesRoot.resolve(s.questFolder), s);
                    loadDmzSideQuests(branchesRoot, s);
                    // AFTER both, because a branch may hang off a quest the other loader supplied.
                    relinkBranchParents(s);
                    result.add(s);
                } catch (Exception e) {
                    DmzNpc.LOGGER.error("[{}] Failed to read saga '{}': {}", DmzNpc.MODID, p.getFileName(), e.toString());
                }
            }
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to list sagas: {}", DmzNpc.MODID, e.toString());
        }
        return result;
    }

    private static void loadQuests(Path questFolder, SagaData saga) {
        if (!Files.isDirectory(questFolder)) {
            return;
        }
        try (Stream<Path> stream = Files.list(questFolder)) {
            List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
            for (Path p : files) {
                try {
                    saga.quests.add(SagaData.Quest.fromJson(GSON.fromJson(Files.readString(p), JsonObject.class)));
                } catch (Exception e) {
                    DmzNpc.LOGGER.error("[{}] Failed to read quest '{}': {}", DmzNpc.MODID, p.getFileName(), e.toString());
                }
            }
        } catch (Exception ignored) {
        }
        saga.quests.sort((a, b) -> Integer.compare(a.id, b.id));
    }

    /** Load this saga's branch quests (SIDEQUESTs we wrote to {@code sidequests/<questFolder>/}). */
    private static void loadBranchQuests(Path branchFolder, SagaData saga) {
        if (!Files.isDirectory(branchFolder)) {
            return;
        }
        try (Stream<Path> stream = Files.list(branchFolder)) {
            for (Path p : stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    SagaData.Quest q = SagaData.Quest.fromSideQuestJson(GSON.fromJson(Files.readString(p), JsonObject.class));
                    if (q != null) {
                        saga.quests.add(q);
                    }
                } catch (Exception e) {
                    DmzNpc.LOGGER.error("[{}] Failed to read branch quest '{}': {}", DmzNpc.MODID, p.getFileName(), e.toString());
                }
            }
        } catch (Exception ignored) {
        }
        saga.quests.sort((a, b) -> Integer.compare(a.id, b.id));
    }

    /**
     * Load DMZ <b>default</b> side quests that belong to this saga (via a SAGA_QUEST prerequisite to it)
     * so they show in the saga editor as branches, just like custom ones. Scans the whole
     * {@code sidequests/} tree, skipping our own branch folder and any {@code sdu_branch}-tagged file
     * (already loaded by {@link #loadBranchQuests}). Each is tagged with its source path so a save writes
     * it back in place without duplicating or corrupting it.
     */
    /**
     * Repoint every branch at its parent by the parent's real chain id, now that all of them are loaded.
     *
     * <p>A branch stores its parent twice: a numeric handle, which is only stable within one session because DMZ
     * default side quests are numbered synthetically in directory-walk order, and the parent's chain id, which is
     * whatever the parent's own file says and does not move. Where the two disagree the chain id wins, because the
     * number is the one that goes stale the moment a side quest file is added or removed.
     *
     * <p>Silent by design when a parent cannot be found: the branch keeps whatever handle it had, which is exactly
     * how it behaved before any of this existed.
     */
    private static void relinkBranchParents(SagaData saga) {
        for (SagaData.Quest q : saga.quests) {
            if (!q.branch || q.branchParentSid == null || q.branchParentSid.isBlank()) {
                continue;
            }
            for (SagaData.Quest candidate : saga.quests) {
                if (candidate == q || !candidate.chainId(saga.id).equals(q.branchParentSid)) {
                    continue;
                }
                for (SagaData.Condition c : q.prerequisites) {
                    if ("SAGA_QUEST".equals(c.type)) {
                        c.questId = candidate.id;
                    }
                }
                break;
            }
        }
    }

    private static void loadDmzSideQuests(Path sidequestsRoot, SagaData saga) {
        if (!Files.isDirectory(sidequestsRoot)) {
            return;
        }
        Path ownFolder = sidequestsRoot.resolve(saga.questFolder);
        int synthId = 10000;
        try (Stream<Path> walk = Files.walk(sidequestsRoot)) {
            for (Path p : walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".json"))
                    .filter(f -> !f.startsWith(ownFolder))
                    .sorted().toList()) {
                try {
                    JsonObject o = GSON.fromJson(Files.readString(p), JsonObject.class);
                    if (o == null || (o.has("sdu_branch") && o.get("sdu_branch").getAsBoolean())) {
                        continue;
                    }
                    if (SagaData.Quest.sideQuestSagaParent(o, saga.id) < 0) {
                        continue;
                    }
                    String rel = sidequestsRoot.relativize(p).toString().replace('\\', '/');
                    saga.quests.add(SagaData.Quest.fromDmzSideQuest(o, rel, synthId++));
                } catch (Exception e) {
                    DmzNpc.LOGGER.error("[{}] Failed to read DMZ side quest '{}': {}", DmzNpc.MODID, p.getFileName(), e.toString());
                }
            }
        } catch (Exception ignored) {
        }
        saga.quests.sort((a, b) -> Integer.compare(a.id, b.id));
    }

    /**
     * Rewrite a DMZ default side quest (loaded as a branch) back to its original file, updating only the
     * fields our editor safely round-trips (title/description/timer) and preserving everything else.
     * Returns the side quest's own id (for the timer config), or "" if it couldn't be written.
     */
    private static String writeMergedSideQuest(Path sidequestsRoot, SagaData.Quest q) throws java.io.IOException {
        // sourcePath is a player-supplied relative path from the editor bundle - validate + contain it.
        SafeFileNames.requireSafeRelJson(q.sourcePath, "side quest source path");
        Path file = SafeFileNames.resolveChecked(sidequestsRoot, q.sourcePath);
        if (!Files.isRegularFile(file)) {
            return ""; // original gone - don't recreate it in the wrong place
        }
        JsonObject orig = GSON.fromJson(Files.readString(file), JsonObject.class);
        if (orig == null) {
            return "";
        }
        orig.addProperty("title", q.title);
        orig.addProperty("description", q.description);
        orig.addProperty("sdu_time_limit", q.timeLimit);
        orig.addProperty("sdu_repeat_interval", q.repeatIntervalSeconds);
        Files.writeString(file, GSON.toJson(orig));
        return net.minecraft.util.GsonHelper.getAsString(orig, "id", "");
    }

    /** Write a saga's file and (re)write its quest folder from scratch. Returns null on success, else an error. */
    /**
     * Advisory only: warn (never reject) if a saga's quest prerequisite points at a saga/quest DMZ's live
     * registry does not know. It is advisory because DMZ has no runtime rescan, so a quest authored this
     * session is on disk but not yet in the registry until a restart; a hard reject here would falsely block a
     * valid gate. The editor's dependent dropdowns are the real author-time guard (they only offer real
     * sagas/quests), and the client fails OPEN on an unknown target so a bad gate can never silently lock a
     * saga forever.
     */
    private static void warnIfPrereqQuestUnknown(String gatedSagaId, SagaQuestGateConfig.Gate gate) {
        try {
            com.dragonminez.common.quest.Saga target =
                    com.dragonminez.common.quest.QuestRegistry.getSaga(gate.gateSaga);
            if (target == null) {
                DmzNpc.LOGGER.warn("[{}] Saga '{}' is gated on saga '{}' which the live registry does not know "
                        + "(pending restart?). The gate is stored; verify the id.", DmzNpc.MODID, gatedSagaId, gate.gateSaga);
            } else if (target.getQuestById(gate.gateQuest) == null) {
                DmzNpc.LOGGER.warn("[{}] Saga '{}' is gated on quest #{} in saga '{}' which the live registry "
                        + "does not know (pending restart?). The gate is stored; verify the quest id.",
                        DmzNpc.MODID, gatedSagaId, gate.gateQuest, gate.gateSaga);
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] saga prereq validation skipped: {}", DmzNpc.MODID, t.toString());
        }
    }

    public static String save(MinecraftServer server, SagaData saga) {
        try {
            // saga.id and saga.questFolder come from the player-supplied editor bundle - validate before
            // they touch the filesystem, then re-check every resolved path stays inside the data dir.
            SafeFileNames.requireSafeSegment(saga.id, "saga id");
            SafeFileNames.requireSafeSegment(saga.questFolder, "saga quest folder");
            Path sagaDir = base(server).resolve("sagas");
            Path questFolder = SafeFileNames.resolveChecked(base(server).resolve("quests"), saga.questFolder);
            Files.createDirectories(sagaDir);
            Files.createDirectories(questFolder);

            Files.writeString(SafeFileNames.resolveChecked(sagaDir, saga.id + ".json"),
                    GSON.toJson(saga.toSagaJson()));

            // Clear old quest files so removed/renamed quests don't linger.
            try (Stream<Path> old = Files.list(questFolder)) {
                for (Path p : old.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    Files.deleteIfExists(p);
                }
            }
            // Branch quests are written as SIDEQUESTs in a saga-specific subfolder; clear it too.
            Path branchFolder = SafeFileNames.resolveChecked(base(server).resolve("sidequests"), saga.questFolder);
            if (Files.isDirectory(branchFolder)) {
                try (Stream<Path> old = Files.list(branchFolder)) {
                    for (Path p : old.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
            Path sidequestsRoot = base(server).resolve("sidequests");
            saga.quests.sort((a, b) -> Integer.compare(a.id, b.id));
            int mainCount = 0;
            int branchCount = 0;
            for (SagaData.Quest q : saga.quests) {
                String questKey;
                if (q.branch && q.sourcePath != null && !q.sourcePath.isBlank()) {
                    // DMZ default side quest shown in this saga: merge our editable fields back into the
                    // original file (title/description/timer), preserving DMZ's objectives/prereqs/etc.
                    questKey = writeMergedSideQuest(sidequestsRoot, q);
                    branchCount++;
                } else if (q.branch) {
                    Files.createDirectories(branchFolder);
                    Files.writeString(branchFolder.resolve(q.branchSid(saga.id) + ".json"),
                            GSON.toJson(q.toSideQuestJson(saga)));
                    questKey = q.branchSid(saga.id);
                    branchCount++;
                } else {
                    String fileName = String.format(Locale.ROOT, "%02d_%s.json", q.id, net.shurui.dev.sdu.util.SduIds.sanitize(q.title));
                    Files.writeString(questFolder.resolve(fileName), GSON.toJson(q.toJson(saga.questFolder)));
                    questKey = com.dragonminez.common.quest.PlayerQuestData.sagaQuestKey(saga.id, q.id);
                    mainCount++;
                }
                // Mirror the addon-only time limit + repeat interval into the sidecar configs the runtime
                // reads (DMZ's loaded Quest objects don't carry our sdu_* fields).
                if (questKey != null && !questKey.isBlank()) {
                    net.shurui.dev.sdu.quest.QuestTimerConfig.put(questKey, q.timeLimit);
                    net.shurui.dev.sdu.quest.RepeatConfig.put(questKey, q.repeatIntervalSeconds);
                    net.shurui.dev.sdu.quest.QuestItemConsumeConfig.put(questKey, q.consumeItems);
                }
            }
            net.shurui.dev.sdu.quest.QuestTimerConfig.save();
            net.shurui.dev.sdu.quest.RepeatConfig.save();
            net.shurui.dev.sdu.quest.QuestItemConsumeConfig.save();
            // Mirror the addon-only saga quest prerequisite into its sidecar (DMZ's SagaRequirements record can
            // only hold previousSagaId) and push it to clients so the quest-tree lock updates without a relog.
            SagaQuestGateConfig.Gate gate = new SagaQuestGateConfig.Gate(saga.prereqQuestSaga, saga.prereqQuestId);
            if (gate.isValid()) {
                warnIfPrereqQuestUnknown(saga.id, gate);
            }
            SagaQuestGateConfig.put(saga.id, gate);
            SagaQuestGateConfig.save();
            net.shurui.dev.sdu.network.DmzNet.syncSagaQuestGatesToAll();
            // Mirror the addon-only region-flag bypass into its sidecar; the runtime region mixin reads it via
            // SagaRegionBypass (DMZ's loaded Saga object doesn't carry our sdu_ key). Server-side only, no sync.
            SagaRegionBypassConfig.put(saga.id, saga.allowStartInQuestBlockedRegion);
            SagaRegionBypassConfig.save();
            DmzNpc.LOGGER.info("[{}] Saved saga '{}' ({} main + {} branch quests)",
                    DmzNpc.MODID, saga.id, mainCount, branchCount);
            return null;
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save saga '{}': {}", DmzNpc.MODID, saga.id, e.toString());
            return e.getMessage();
        }
    }

    public static void delete(MinecraftServer server, String sagaId, String questFolder) {
        try {
            // Both ids are player-supplied (straight from DeleteSagaPacket) - validate + contain them so a
            // crafted id can't delete files outside the saga/quest folders.
            SafeFileNames.requireSafeSegment(sagaId, "saga id");
            SafeFileNames.requireSafeSegment(questFolder, "saga quest folder");
            Files.deleteIfExists(SafeFileNames.resolveChecked(base(server).resolve("sagas"), sagaId + ".json"));
            Path qf = SafeFileNames.resolveChecked(base(server).resolve("quests"), questFolder);
            if (Files.isDirectory(qf)) {
                try (Stream<Path> s = Files.list(qf)) {
                    for (Path p : s.toList()) {
                        Files.deleteIfExists(p);
                    }
                }
                Files.deleteIfExists(qf);
            }
            DmzNpc.LOGGER.info("[{}] Deleted saga '{}'", DmzNpc.MODID, sagaId);
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to delete saga '{}': {}", DmzNpc.MODID, sagaId, e.toString());
        }
    }
}
