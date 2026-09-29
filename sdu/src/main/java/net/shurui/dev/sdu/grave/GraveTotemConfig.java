package net.shurui.dev.sdu.grave;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.fml.loading.FMLPaths;
import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Operator-settable lifetime, in minutes, for the death grave totems that {@code shuruisutilities} builds and
 * sweeps. One global server-side record in {@code config/sdu/grave_totem.json} (write deferred until an admin
 * sets one, so a fresh server keeps the {@link #DEFAULT_MINUTES} default without a file).
 *
 * <h2>Why this lives in sdu</h2>
 * The setting is written by {@code /rg npc totem time}, which hangs off the {@code /rg} command tree that sdu
 * owns, and it is read by the grave expiry sweep, which lives in {@code shuruisutilities}. sdu is the one tree
 * both sides can share without inverting the "sdu imports nothing from shuruisutilities" invariant: the command
 * sets it here directly, and the sweep reads it here.
 *
 * <h2>Persistence and cross-shard sync</h2>
 * The value must survive a restart AND read the same on every shard. Local restart persistence is this JSON file,
 * the same shape {@code HtcDestination} uses. Cross-shard sync is {@link #saveState()} / {@link #mergeState},
 * registered with {@code ShardStateSync} under {@code su:cfg_grave_totem} from {@code ShuruisUtilities}, so an
 * operator setting it on one shard sets it everywhere. Whole-record last-write-wins, the same trade the other
 * {@code su:cfg_*} knobs make; there is only one field, so nothing per-key can be dropped.
 */
public final class GraveTotemConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 1;

    /** Lifetime a fresh server uses until an operator changes it. */
    public static final int DEFAULT_MINUTES = 10;
    /** Floor: a 0 or negative lifetime would sweep every grave the instant it is set, so one minute is the minimum. */
    public static final int MIN_MINUTES = 1;
    /** Ceiling: twenty-four hours. Long enough for any legitimate "give me time to fetch it" case, bounded so a fat
     *  finger cannot leave graves standing effectively forever. */
    public static final int MAX_MINUTES = 1440;

    // Live in-memory value: the single source of truth read by the grave expiry sweep.
    private static int minutes = DEFAULT_MINUTES;
    private static boolean loaded = false;

    private GraveTotemConfig() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("grave_totem.json");
    }

    /** The current grave/totem despawn lifetime in minutes. */
    public static int minutes() {
        ensureLoaded();
        return minutes;
    }

    /** The same lifetime expressed in game ticks, which is what the sweep compares against. */
    public static long maxAgeTicks() {
        return minutes() * 60L * 20L;
    }

    /**
     * Store a new lifetime and persist it. Clamped to {@code [MIN_MINUTES, MAX_MINUTES]} defensively (the command
     * already refuses out-of-range input at parse, but the shard-sync path arrives here too).
     *
     * @return the value actually stored after clamping
     */
    public static synchronized int set(int newMinutes) {
        ensureLoaded();
        minutes = clamp(newMinutes);
        save();
        return minutes;
    }

    private static int clamp(int v) {
        return v < MIN_MINUTES ? MIN_MINUTES : Math.min(v, MAX_MINUTES);
    }

    public static synchronized void load() {
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            // no file = never set; keep the default, do not write.
            minutes = DEFAULT_MINUTES;
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (root != null && root.has("minutes")) {
                minutes = clamp(root.get("minutes").getAsInt());
            }
            DmzNpc.LOGGER.info("[{}] Loaded grave totem lifetime: {} minute(s).", DmzNpc.MODID, minutes);
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read grave totem lifetime: {}", DmzNpc.MODID, e.toString());
            minutes = DEFAULT_MINUTES;
        }
    }

    private static synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("configVersion", CONFIG_VERSION);
            root.addProperty("minutes", minutes);
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save grave totem lifetime: {}", DmzNpc.MODID, e.toString());
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    /** Snapshot the value for {@code ShardStateSync}. Deterministic, so the hash only moves on a real edit. */
    public static CompoundTag saveState() {
        ensureLoaded();
        CompoundTag t = new CompoundTag();
        t.putInt("minutes", minutes);
        return t;
    }

    /**
     * Apply a sibling shard's value through the same clamp-and-persist path the local command uses, so the value
     * is bounded, written to this shard's own JSON, and live for the next sweep with no restart. Whole-record
     * last-write-wins; there is nothing per-key to drop.
     */
    public static void mergeState(CompoundTag t) {
        if (t == null) {
            throw new IllegalArgumentException("null grave totem config state");
        }
        if (!t.contains("minutes")) {
            return;
        }
        set(t.getInt("minutes"));
    }
}
