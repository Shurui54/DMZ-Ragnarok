package net.shurui.dev.sdu.saga;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.DmzNpc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which DragonMine Z quests should hold their KILL-objective NPC spawns back until the player reaches the
 * quest's COORDS location. A quest qualifies when it has both a COORDS objective (the location + radius) and
 * at least one KILL objective flagged {@link SagaData.Objective#deferSpawnUntilLocation}. Keyed by the same
 * quest key DMZ passes to {@code QuestService.spawnKillObjectivesForQuest} (mirrors {@link SagaSpawnBindings}:
 * {@code "<sagaId>:<questId>"} for saga quests, the string id for side quests).
 */
public final class DeferredSpawnRegistry {

    /** A location gate: the player must be within {@link #radius} of ({@link #x},{@link #y},{@link #z}). */
    public static final class Gate {
        public final int x;
        public final int y;
        public final int z;
        public final int radius;
        /** The quest's DIMENSION objective id, or null to accept any dimension. */
        public final String dimension;

        Gate(int x, int y, int z, int radius, String dimension) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.dimension = dimension;
        }

        /** True once the player is within range (and in the required dimension, if any). */
        public boolean reached(ServerPlayer player) {
            if (dimension != null && !dimension.isBlank()
                    && !dimension.equals(player.level().dimension().location().toString())) {
                return false;
            }
            double dx = player.getX() - (x + 0.5);
            double dy = player.getY() - (y + 0.5);
            double dz = player.getZ() - (z + 0.5);
            double r = Math.max(1, radius);
            return dx * dx + dy * dy + dz * dz <= r * r;
        }
    }

    private static final Map<String, Gate> GATES = new HashMap<>();

    /**
     * Quests with a {@code sdu_defer_spawn} KILL objective but NO COORDS objective to gate on: the flagged kill
     * is held back at accept and spawned when DMZ's sequencing unlocks it instead of at a location. Keys are
     * {@code "questKey|objectiveIndex"} for each flagged KILL, plus a set of the bare quest keys so the healer
     * can find them cheaply.
     */
    private static final Set<String> SEQUENCE_GATED = new HashSet<>();
    private static final Set<String> SEQUENCE_GATED_QUESTS = new HashSet<>();

    private DeferredSpawnRegistry() {
    }

    public static synchronized void loadFrom(MinecraftServer server) {
        GATES.clear();
        SEQUENCE_GATED.clear();
        SEQUENCE_GATED_QUESTS.clear();
        for (SagaData saga : SagaFileManager.loadAll(server)) {
            for (SagaData.Quest q : saga.quests) {
                index(saga.id + ":" + q.id, q.objectives);
            }
        }
        for (SideQuestData sq : SideQuestFileManager.loadAll(server)) {
            index(sq.id, sq.objectives);
        }
        DmzNpc.LOGGER.info("[{}] Loaded {} location-gated and {} sequence-gated quest spawn(s)",
                DmzNpc.MODID, GATES.size(), SEQUENCE_GATED_QUESTS.size());
    }

    /**
     * Register a quest's deferred KILL objectives. A flagged KILL with a COORDS objective is location-gated (the
     * mob is held until the player reaches the coords). A flagged KILL with no COORDS objective is
     * sequence-gated: the mob is held until DMZ's own sequencing unlocks that objective.
     */
    private static void index(String questKey, List<SagaData.Objective> objectives) {
        SagaData.Objective coords = null;
        String dimension = null;
        List<Integer> flaggedKills = new ArrayList<>();
        for (int i = 0; i < objectives.size(); i++) {
            SagaData.Objective o = objectives.get(i);
            if ("KILL".equals(o.type) && o.deferSpawnUntilLocation) {
                flaggedKills.add(i);
            } else if ("COORDS".equals(o.type) && coords == null) {
                coords = o;
            } else if ("DIMENSION".equals(o.type) && dimension == null) {
                dimension = o.dimension;
            }
        }
        if (flaggedKills.isEmpty()) {
            return;
        }
        if (coords != null) {
            GATES.put(questKey, new Gate(coords.coordX, coords.coordY, coords.coordZ, coords.radius, dimension));
        } else {
            SEQUENCE_GATED_QUESTS.add(questKey);
            for (int idx : flaggedKills) {
                SEQUENCE_GATED.add(questKey + "|" + idx);
            }
        }
    }

    /** The gate for a quest key, or null if that quest isn't location-gated. */
    public static Gate gateFor(String questKey) {
        return questKey == null ? null : GATES.get(questKey);
    }

    /** True when this KILL objective is sequence-gated: held back at accept until DMZ's sequencing unlocks it. */
    public static boolean deferUntilUnlocked(String questKey, int objectiveIndex) {
        return questKey != null && SEQUENCE_GATED.contains(questKey + "|" + objectiveIndex);
    }

    /** The quest keys that have at least one sequence-gated KILL objective (a small set, usually empty). */
    public static Set<String> sequenceGatedQuestKeys() {
        return SEQUENCE_GATED_QUESTS;
    }

    public static boolean isEmpty() {
        return GATES.isEmpty() && SEQUENCE_GATED.isEmpty();
    }
}
