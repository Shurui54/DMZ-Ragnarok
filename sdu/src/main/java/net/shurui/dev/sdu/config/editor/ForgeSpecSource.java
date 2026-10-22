package net.shurui.dev.sdu.config.editor;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Every value of one {@link ForgeConfigSpec}, described straight from the spec: type from the default, bounds from
 * the declared range, the comment from the file, and the spec's own validator on save. Nothing is hand-listed, so a
 * value added to the spec tomorrow shows up in the editor by itself (in the source's default tab) unless a rule
 * excludes it.
 *
 * <p>Saving uses {@code ConfigValue.set} and {@code ForgeConfigSpec.save()}, the same calls Forge makes when it
 * corrects a file, so the file keeps its comments and layout and a hand edit afterwards works as before.
 */
public final class ForgeSpecSource extends ConfigSource {

    private final Supplier<ForgeConfigSpec> spec;
    private final Runnable afterCommit;
    private Function<String, String> tabFor;
    private Predicate<String> restartIf = p -> false;
    private Predicate<String> keyOnlyIf = p -> false;
    private final Map<String, List<String>> choices = new HashMap<>();
    private final Map<String, String> comments = new HashMap<>();

    private ForgeConfigSpec cachedSpec;
    private List<ConfigEntry> cached = List.of();

    /**
     * @param id          source id (option ids are {@code id:path})
     * @param file        the file as an operator finds it
     * @param spec        the spec (a supplier, because some specs are built after their owner registers)
     * @param defaultTab  tab for every value no rule places elsewhere
     * @param afterCommit run after the file is saved: bake the owner's static copies, push to clients, and so on
     */
    public ForgeSpecSource(String id, String file, Supplier<ForgeConfigSpec> spec, String defaultTab,
                           Runnable afterCommit) {
        super(id, file);
        this.spec = spec;
        this.afterCommit = afterCommit;
        this.tabFor = p -> defaultTab;
    }

    /** Tab for a path, or {@code null} to leave the value out of the editor. */
    public ForgeSpecSource tabs(Function<String, String> tabOrNull) {
        this.tabFor = tabOrNull;
        return this;
    }

    public ForgeSpecSource restartIf(Predicate<String> p) {
        this.restartIf = p;
        return this;
    }

    public ForgeSpecSource keyOnlyIf(Predicate<String> p) {
        this.keyOnlyIf = p;
        return this;
    }

    /** Offer a string value as a choice of fixed values. */
    public ForgeSpecSource choices(String path, List<String> values) {
        choices.put(path, List.copyOf(values));
        return this;
    }

    /** Extra hover text for a value whose file comment is missing or unclear. */
    public ForgeSpecSource comment(String path, String text) {
        comments.put(path, text);
        return this;
    }

    @Override
    public synchronized List<ConfigEntry> entries() {
        ForgeConfigSpec s = spec.get();
        if (s == null || !s.isLoaded()) {
            return List.of();
        }
        if (s != cachedSpec) {
            List<ConfigEntry> out = new ArrayList<>();
            walk(s, s.getValues(), out);
            cached = List.copyOf(out);
            cachedSpec = s;
        }
        return cached;
    }

    private void walk(ForgeConfigSpec s, UnmodifiableConfig values, List<ConfigEntry> out) {
        for (Map.Entry<String, Object> e : values.valueMap().entrySet()) {
            Object v = e.getValue();
            if (v instanceof UnmodifiableConfig sub) {
                walk(s, sub, out);
            } else if (v instanceof ForgeConfigSpec.ConfigValue<?> cv) {
                ConfigEntry entry = make(s, cv);
                if (entry != null) {
                    out.add(entry);
                }
            }
        }
    }

    private ConfigEntry make(ForgeConfigSpec s, ForgeConfigSpec.ConfigValue<?> cv) {
        List<String> parts = cv.getPath();
        String path = String.join(".", parts);
        if (ConfigLabels.looksSecret(path)) {
            return null;
        }
        String tab = tabFor.apply(path);
        if (tab == null) {
            return null;
        }
        Object raw = s.getSpec().get(parts);
        if (!(raw instanceof ForgeConfigSpec.ValueSpec vs)) {
            return null;
        }
        Object def = cv.getDefault();
        OptionType type;
        OptionType element = null;
        if (def instanceof Boolean) {
            type = OptionType.BOOL;
        } else if (def instanceof Integer) {
            type = OptionType.INT;
        } else if (def instanceof Long) {
            type = OptionType.LONG;
        } else if (def instanceof Double || def instanceof Float) {
            type = OptionType.DOUBLE;
        } else if (def instanceof Enum<?>) {
            type = OptionType.ENUM;
        } else if (def instanceof String) {
            type = choices.containsKey(path) ? OptionType.ENUM : OptionType.STRING;
        } else if (def instanceof List<?> l) {
            type = OptionType.LIST;
            Object sample = !l.isEmpty() ? l.get(0) : null;
            if (sample == null && cv.get() instanceof List<?> cur && !cur.isEmpty()) {
                sample = cur.get(0);
            }
            element = sample instanceof Integer ? OptionType.INT : sample instanceof Long ? OptionType.LONG
                    : sample instanceof Double || sample instanceof Float ? OptionType.DOUBLE : OptionType.STRING;
        } else {
            return null; // a type the editor does not handle (none in the suite today)
        }
        SpecEntry entry = new SpecEntry(id + ":" + path, path, file, type, cv, vs);
        if (element != null) {
            entry.elementType = element;
        }
        entry.tab = tab;
        int dot = path.lastIndexOf('.');
        entry.group = dot < 0 ? file : file + "  >  " + path.substring(0, dot);
        String extra = comments.get(path);
        String c = stripForgeLines(vs.getComment());
        entry.comment = extra == null ? c : (c.isEmpty() ? extra : c + "\n" + extra);
        if (vs.getTranslationKey() != null && !vs.getTranslationKey().isBlank()) {
            entry.labelKey = vs.getTranslationKey();
        }
        ForgeConfigSpec.Range<?> range = vs.getRange();
        if (range != null && range.getMin() instanceof Number lo && range.getMax() instanceof Number hi) {
            entry.min = lo.doubleValue();
            entry.max = hi.doubleValue();
        }
        if (def instanceof Enum<?> en) {
            List<String> names = new ArrayList<>();
            for (Object k : en.getDeclaringClass().getEnumConstants()) {
                if (vs.test(k)) {
                    names.add(((Enum<?>) k).name());
                }
            }
            entry.choices = names;
        } else if (choices.containsKey(path)) {
            entry.choices = choices.get(path);
        }
        entry.restart = vs.needsWorldRestart() || restartIf.test(path);
        entry.keyOnly = keyOnlyIf.test(path);
        return entry;
    }

    /**
     * Forge's config file autosaves on every {@code set} and its file watcher reloads it on another thread when it
     * changes. A reload that lands between two sets of one save replaces the in-memory values with the half-written
     * state (seen in a probe: the second value of a batch was lost). nightconfig's file config synchronises load and
     * save on itself, so holding that same monitor across the whole batch makes the watcher wait until the file is
     * complete; its reload then reads exactly what was saved.
     */
    @Override
    public void locked(Runnable work) {
        Object lock = fileLock(spec.get());
        if (lock == null) {
            work.run();
            return;
        }
        synchronized (lock) {
            work.run();
        }
    }

    private static Object fileLock(ForgeConfigSpec s) {
        if (s == null) {
            return null;
        }
        try {
            java.lang.reflect.Field f = ForgeConfigSpec.class.getDeclaredField("childConfig");
            f.setAccessible(true);
            Object child = f.get(s);
            if (child == null) {
                return null;
            }
            for (Class<?> c = child.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field inner = c.getDeclaredField("fileConfig");
                    inner.setAccessible(true);
                    Object file = inner.get(child);
                    return file != null ? file : child;
                } catch (NoSuchFieldException ignored) {
                    // not this level of the hierarchy
                }
            }
            return child;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The file comment without the "Range:" / "Allowed Values:" lines Forge appends (the editor shows its own). */
    private static String stripForgeLines(String comment) {
        if (comment == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : comment.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Range:") || t.startsWith("Allowed Values:")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t);
        }
        return sb.toString().trim();
    }

    @Override
    public void commit() {
        ForgeConfigSpec s = spec.get();
        if (s != null && s.isLoaded()) {
            s.save();
        }
        if (afterCommit != null) {
            afterCommit.run();
        }
    }

    private static final class SpecEntry extends ConfigEntry {
        private final ForgeConfigSpec.ConfigValue<?> cv;
        private final ForgeConfigSpec.ValueSpec vs;

        SpecEntry(String id, String path, String file, OptionType type, ForgeConfigSpec.ConfigValue<?> cv,
                  ForgeConfigSpec.ValueSpec vs) {
            super(id, path, file, type);
            this.cv = cv;
            this.vs = vs;
        }

        @Override
        public Object currentObject() {
            return cv.get();
        }

        @Override
        public Object defaultObject() {
            return cv.getDefault();
        }

        @Override
        protected boolean accepts(Object parsed) {
            return vs.test(parsed);
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        protected Object enumValue(String choice) {
            Object def = cv.getDefault();
            if (def instanceof Enum<?> en) {
                return Enum.valueOf((Class) en.getDeclaringClass(), choice);
            }
            return choice;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void store(Object parsed) {
            ((ForgeConfigSpec.ConfigValue<Object>) cv).set(parsed);
        }
    }
}
