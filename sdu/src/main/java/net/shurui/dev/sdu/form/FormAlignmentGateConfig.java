package net.shurui.dev.sdu.form;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import net.shurui.dev.sdu.DmzNpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-form ALIGNMENT gate: which DMZ alignment values a character must sit at to UNLOCK (buy) a form, and to
 * USE (transform into, or stay in) it. A third axis alongside the minimum-level gate ({@link FormLevelGateConfig})
 * and the quest gate ({@link FormQuestGateConfig}), keyed the same way, loaded the same way, and synced the same way.
 *
 * <p>DMZ alignment is an int the range {@code 0..100} ({@code Resources.getAlignment()}, clamped by DMZ). New
 * characters start at 100; DMZ's own bands read {@code >60} as GOOD, {@code 41..60} as NEUTRAL, {@code <=40} as EVIL.
 * The majin effect forces alignment to 0. This gate makes no assumption about those bands: an operator picks raw
 * numbers, so a form can be locked to the good end (e.g. {@code useMin: 61}), the evil end (e.g. {@code useMax: 40}),
 * or a window in the middle.
 *
 * <p>Stored in a sidecar rather than the DMZ form JSON because {@code FormFileManager.save} rewrites each group
 * through DMZ's own {@code FormConfig} serializer, which drops unknown fields; the same reason the form-combat,
 * aura, unlock-cost, quest-gate and level-gate values all live in sdu sidecars. Persisted at
 * {@code config/sdu/form_alignment_gates.json}, keyed by the lower-cased {@code group.form} pair (e.g.
 * {@code godforms.supersaiyangod}, {@code ultimate.ultimate}) so a form name shared across two groups can carry
 * different bounds, and so a stack form keys the same way as a race form.
 *
 * <p>Each entry may set any of {@code unlockMin}, {@code unlockMax} (alignment needed to buy or otherwise unlock
 * the form) and {@code useMin}, {@code useMax} (alignment needed to transform into or stay in it); any field may be
 * omitted, and an omitted bound means "no bound on that side" ({@code min} defaults to 0, {@code max} to 100). An
 * entry with no field set (or a missing entry) is NO gate, so nothing changes until an operator sets one.
 *
 * <p>{@code config/sdu} is a shard-sync root, so this file travels between servers on its own; a per-key merge
 * entry in {@code ConfigMerge} keeps two admins editing different forms from clobbering each other. Synced to
 * clients on login and after a form edit ({@link net.shurui.dev.sdu.network.FormAlignmentGateSyncPacket}) so the
 * skills GUI can show the requirement.
 */
public final class FormAlignmentGateConfig {

    /** DMZ's alignment range, clamped by {@code Resources.setAlignment}. Bounds are held to this window. */
    public static final int MIN_ALIGNMENT = 0;
    public static final int MAX_ALIGNMENT = 100;

    /**
     * One form's alignment bounds. Each of the four is optional ({@code null} = "no bound on that side"). A
     * bound present is held to {@code 0..100}. Immutable; the map holds one per gated form.
     */
    public static final class Bounds {
        public final Integer unlockMin;
        public final Integer unlockMax;
        public final Integer useMin;
        public final Integer useMax;

        public Bounds(Integer unlockMin, Integer unlockMax, Integer useMin, Integer useMax) {
            this.unlockMin = clampOrNull(unlockMin);
            this.unlockMax = clampOrNull(unlockMax);
            this.useMin = clampOrNull(useMin);
            this.useMax = clampOrNull(useMax);
        }

        private static Integer clampOrNull(Integer v) {
            if (v == null) {
                return null;
            }
            return Math.max(MIN_ALIGNMENT, Math.min(MAX_ALIGNMENT, v));
        }

        /** No bound at all: this gate does nothing and is not stored. */
        public boolean isEmpty() {
            return unlockMin == null && unlockMax == null && useMin == null && useMax == null;
        }

        public boolean hasUnlockGate() {
            return unlockMin != null || unlockMax != null;
        }

        public boolean hasUseGate() {
            return useMin != null || useMax != null;
        }

        public int effUnlockMin() {
            return unlockMin == null ? MIN_ALIGNMENT : unlockMin;
        }

        public int effUnlockMax() {
            return unlockMax == null ? MAX_ALIGNMENT : unlockMax;
        }

        public int effUseMin() {
            return useMin == null ? MIN_ALIGNMENT : useMin;
        }

        public int effUseMax() {
            return useMax == null ? MAX_ALIGNMENT : useMax;
        }

        /** True when {@code alignment} is inside the unlock window (or there is no unlock gate). */
        public boolean allowsUnlock(int alignment) {
            if (!hasUnlockGate()) {
                return true;
            }
            return alignment >= effUnlockMin() && alignment <= effUnlockMax();
        }

        /** True when {@code alignment} is inside the use window (or there is no use gate). */
        public boolean allowsUse(int alignment) {
            if (!hasUseGate()) {
                return true;
            }
            return alignment >= effUseMin() && alignment <= effUseMax();
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            if (unlockMin != null) {
                o.addProperty("unlockMin", unlockMin);
            }
            if (unlockMax != null) {
                o.addProperty("unlockMax", unlockMax);
            }
            if (useMin != null) {
                o.addProperty("useMin", useMin);
            }
            if (useMax != null) {
                o.addProperty("useMax", useMax);
            }
            return o;
        }

        static Bounds fromJson(JsonObject o) {
            return new Bounds(optInt(o, "unlockMin"), optInt(o, "unlockMax"), optInt(o, "useMin"), optInt(o, "useMax"));
        }

        private static Integer optInt(JsonObject o, String key) {
            try {
                if (o.has(key) && o.get(key).isJsonPrimitive() && !o.get(key).getAsJsonPrimitive().isString()) {
                    return o.get(key).getAsInt();
                }
                // tolerate a string number too, since a hand editor might quote it
                if (o.has(key) && o.get(key).isJsonPrimitive()) {
                    String s = o.get(key).getAsString().trim();
                    if (!s.isEmpty()) {
                        return Integer.parseInt(s);
                    }
                }
            } catch (Throwable ignored) {
                // absent / unparseable = no bound on that side
            }
            return null;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int CONFIG_VERSION = 1;
    /** on-disk example block key; ignored when loading real gates */
    private static final String EXAMPLE_KEY = "_example";

    /** group.form (lower-cased) -> alignment bounds */
    private static final Map<String, Bounds> BY_FORM = new LinkedHashMap<>();
    private static boolean loaded = false;

    private FormAlignmentGateConfig() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("sdu").resolve("form_alignment_gates.json");
    }

    /** Bounds for {@code group.form}, or null (no gate). */
    public static Bounds get(String group, String form) {
        return getByKey(key(group, form));
    }

    /** Bounds for a pre-joined {@code group.form} key, or null (no gate). */
    public static Bounds getByKey(String formKey) {
        ensureLoaded();
        return BY_FORM.get(key(formKey));
    }

    public static synchronized void load() {
        BY_FORM.clear();
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            writeExampleFile(path);
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (root != null && root.has("gates") && root.get("gates").isJsonObject()) {
                JsonObject gates = root.getAsJsonObject("gates");
                for (String name : gates.keySet()) {
                    if (EXAMPLE_KEY.equals(name) || !gates.get(name).isJsonObject()) {
                        continue;
                    }
                    Bounds b = Bounds.fromJson(gates.getAsJsonObject(name));
                    if (!b.isEmpty()) {
                        BY_FORM.put(key(name), b);
                    }
                }
            }
            DmzNpc.LOGGER.info("[{}] Loaded form-alignment-gate config: {} gated form(s).", DmzNpc.MODID, BY_FORM.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to read form-alignment-gate config: {}", DmzNpc.MODID, e.toString());
        }
    }

    /** snapshot of every gate for server -> client sync */
    public static synchronized Map<String, Bounds> all() {
        ensureLoaded();
        return new LinkedHashMap<>(BY_FORM);
    }

    /**
     * Drop every gate under {@code group} (keys {@code group.*}), so the form editor's save path can re-derive a
     * group's bounds from the forms it actually holds without leaving a stale entry for a deleted/cleared form.
     * Does not persist; caller batches and calls {@link #save()}. True if the map changed.
     */
    public static synchronized boolean removeGroupNoSave(String group) {
        ensureLoaded();
        if (group == null || group.isBlank()) {
            return false;
        }
        String prefix = group.trim().toLowerCase(Locale.ROOT) + ".";
        return BY_FORM.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Set {@code group.form}'s bounds (an empty/null Bounds removes it). Does not persist; caller runs
     * {@link #save()} once. True if the map changed. */
    public static synchronized boolean putNoSave(String group, String form, Bounds bounds) {
        ensureLoaded();
        String k = key(group, form);
        if (k.isEmpty() || k.indexOf('.') <= 0) {
            return false;
        }
        if (bounds == null || bounds.isEmpty()) {
            return BY_FORM.remove(k) != null;
        }
        Bounds prev = BY_FORM.put(k, bounds);
        return prev == null || !sameBounds(prev, bounds);
    }

    private static boolean sameBounds(Bounds a, Bounds b) {
        return java.util.Objects.equals(a.unlockMin, b.unlockMin)
                && java.util.Objects.equals(a.unlockMax, b.unlockMax)
                && java.util.Objects.equals(a.useMin, b.useMin)
                && java.util.Objects.equals(a.useMax, b.useMax);
    }

    /** persist to disk, keeping the self-documenting comment block */
    public static synchronized void save() {
        Path path = file();
        try {
            JsonObject root = new JsonObject();
            root.addProperty("_comment", comment());
            root.addProperty("configVersion", CONFIG_VERSION);
            JsonObject gates = new JsonObject();
            for (Map.Entry<String, Bounds> e : BY_FORM.entrySet()) {
                gates.add(e.getKey(), e.getValue().toJson());
            }
            root.add("gates", gates);
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
            DmzNpc.LOGGER.info("[{}] Saved form-alignment-gate config: {} gated form(s).", DmzNpc.MODID, BY_FORM.size());
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to save form-alignment-gate config: {}", DmzNpc.MODID, e.toString());
        }
    }

    /** replace gates from a server sync (client-side; its own config has none) */
    public static synchronized void applySynced(Map<String, Bounds> data) {
        BY_FORM.clear();
        loaded = true;
        if (data != null) {
            for (Map.Entry<String, Bounds> e : data.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    BY_FORM.put(key(e.getKey()), e.getValue());
                }
            }
        }
        DmzNpc.LOGGER.info("[{}] Applied synced form-alignment-gate config: {} gated form(s).", DmzNpc.MODID, BY_FORM.size());
    }

    /** write a starter file with a commented example (never loaded as a real gate) */
    private static void writeExampleFile(Path path) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("_comment", comment());
            root.addProperty("configVersion", CONFIG_VERSION);
            JsonObject gates = new JsonObject();
            gates.add(EXAMPLE_KEY, new Bounds(61, 100, 61, 100).toJson());
            root.add("gates", gates);
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
            DmzNpc.LOGGER.info("[{}] Wrote example form-alignment-gate config to {}", DmzNpc.MODID, path);
        } catch (Exception e) {
            DmzNpc.LOGGER.error("[{}] Failed to write example form-alignment-gate config: {}", DmzNpc.MODID, e.toString());
        }
    }

    private static String comment() {
        return "DMZ ALIGNMENT gate per form. Key each entry by the lower-cased 'group.form' pair (e.g. "
                + "'godforms.supersaiyangod', 'ultimate.ultimate'). Alignment is 0..100 (Resources.getAlignment); "
                + "DMZ reads >60 as GOOD, 41..60 as NEUTRAL, <=40 as EVIL, and majin forces 0. Each entry may set "
                + "unlockMin/unlockMax (alignment needed to buy or otherwise unlock the form) and useMin/useMax "
                + "(alignment needed to transform into or stay in it); any field may be omitted, and an omitted "
                + "bound means no bound on that side (min defaults 0, max 100). A player outside the use window "
                + "cannot transform and is dropped out of the form if already in it; a player outside the unlock "
                + "window cannot buy it. Set from the form editor's alignment fields. Delete the '_example' entry "
                + "(it is ignored) and add real ones under 'gates'. An entry with no field set means no gate.";
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static String key(String group, String form) {
        String g = group == null ? "" : group.trim().toLowerCase(Locale.ROOT);
        String f = form == null ? "" : form.trim().toLowerCase(Locale.ROOT);
        if (g.isEmpty() || f.isEmpty()) {
            return "";
        }
        return g + "." + f;
    }

    private static String key(String formKey) {
        return formKey == null ? "" : formKey.trim().toLowerCase(Locale.ROOT);
    }
}
