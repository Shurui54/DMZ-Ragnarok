package net.shurui.dev.sdu.config.editor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The public fields of a JSON config object (the classes the suite reads with Gson): booleans, numbers, strings and
 * string or number lists. The live object always comes from the owner, so a reload that swaps the instance is seen;
 * {@code commit} hands persisting back to the owner, so its own save, load and sanitising run exactly as they do for
 * a hand-edited file.
 *
 * <p>JSON has no declared ranges, so numbers are bounded by {@link #range} or, when none is given, must not be
 * negative. Fields without a {@link #tab} placement use the source's default tab; {@link #exclude} drops one.
 */
public final class JsonObjectSource extends ConfigSource {

    private final Supplier<Object> live;
    private final Supplier<Object> defaults;
    private final Runnable commit;
    private final String defaultTab;
    private final Map<String, double[]> ranges = new HashMap<>();
    private final Map<String, String> tabs = new HashMap<>();
    private final Map<String, String> comments = new HashMap<>();
    private final Set<String> excluded = new HashSet<>();
    private final Set<String> restart = new HashSet<>();
    private boolean keyOnly;
    private List<ConfigEntry> cached;

    /**
     * @param live     the object the feature reads (fetched again on every use)
     * @param defaults a fresh default instance (its field values are the defaults)
     * @param commit   persist the live object and let the feature reload it
     */
    public JsonObjectSource(String id, String file, String defaultTab, Supplier<Object> live,
                            Supplier<Object> defaults, Runnable commit) {
        super(id, file);
        this.defaultTab = defaultTab;
        this.live = live;
        this.defaults = defaults;
        this.commit = commit;
    }

    public JsonObjectSource range(String field, double min, double max) {
        ranges.put(field, new double[]{min, max});
        return this;
    }

    public JsonObjectSource tab(String field, String tab) {
        tabs.put(field, tab);
        return this;
    }

    public JsonObjectSource comment(String field, String text) {
        comments.put(field, text);
        return this;
    }

    public JsonObjectSource exclude(String... fields) {
        excluded.addAll(List.of(fields));
        return this;
    }

    public JsonObjectSource restart(String... fields) {
        restart.addAll(List.of(fields));
        return this;
    }

    public JsonObjectSource keyOnly() {
        this.keyOnly = true;
        return this;
    }

    @Override
    public synchronized List<ConfigEntry> entries() {
        if (cached != null) {
            return cached;
        }
        Object sample = live.get();
        if (sample == null) {
            return List.of();
        }
        List<ConfigEntry> out = new ArrayList<>();
        for (Field f : sample.getClass().getFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) || Modifier.isTransient(m) || Modifier.isFinal(m) || excluded.contains(f.getName())
                    || ConfigLabels.looksSecret(f.getName())) {
                continue;
            }
            OptionType type = typeOf(f.getType());
            OptionType element = null;
            if (type == null && List.class.isAssignableFrom(f.getType())) {
                element = elementOf(f.getGenericType());
                if (element == null) {
                    continue;
                }
                type = OptionType.LIST;
            }
            if (type == null) {
                continue;
            }
            FieldEntry e = new FieldEntry(id + ":" + f.getName(), f.getName(), file, type, f);
            if (element != null) {
                e.elementType = element;
            }
            e.tab = tabs.getOrDefault(f.getName(), defaultTab);
            e.group = file;
            e.comment = comments.getOrDefault(f.getName(), "");
            OptionType numeric = element != null ? element : type;
            if (numeric == OptionType.INT || numeric == OptionType.LONG || numeric == OptionType.DOUBLE) {
                double[] r = ranges.get(f.getName());
                e.min = r == null ? 0.0 : r[0];
                double hardMax = numeric == OptionType.INT ? Integer.MAX_VALUE
                        : numeric == OptionType.LONG ? (double) Long.MAX_VALUE : Double.MAX_VALUE;
                e.max = r == null ? hardMax : r[1];
            }
            e.restart = restart.contains(f.getName());
            e.keyOnly = keyOnly;
            out.add(e);
        }
        cached = List.copyOf(out);
        return cached;
    }

    private static OptionType typeOf(Class<?> c) {
        if (c == boolean.class || c == Boolean.class) {
            return OptionType.BOOL;
        }
        if (c == int.class || c == Integer.class) {
            return OptionType.INT;
        }
        if (c == long.class || c == Long.class) {
            return OptionType.LONG;
        }
        if (c == double.class || c == Double.class || c == float.class || c == Float.class) {
            return OptionType.DOUBLE;
        }
        if (c == String.class) {
            return OptionType.STRING;
        }
        return null;
    }

    private static OptionType elementOf(Type generic) {
        if (generic instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
            OptionType t = typeOf(c);
            return t == OptionType.BOOL ? null : t;
        }
        return null;
    }

    @Override
    public void commit() {
        commit.run();
    }

    private final class FieldEntry extends ConfigEntry {
        private final Field field;

        FieldEntry(String id, String path, String file, OptionType type, Field field) {
            super(id, path, file, type);
            this.field = field;
        }

        @Override
        public Object currentObject() {
            return read(live.get());
        }

        @Override
        public Object defaultObject() {
            return read(defaults.get());
        }

        private Object read(Object o) {
            try {
                return o == null ? null : field.get(o);
            } catch (IllegalAccessException e) {
                return null;
            }
        }

        @Override
        protected void store(Object parsed) {
            Object target = live.get();
            if (target == null) {
                throw new IllegalStateException("config not loaded");
            }
            Class<?> t = field.getType();
            Object v = parsed;
            if ((t == float.class || t == Float.class) && parsed instanceof Double d) {
                v = d.floatValue();
            }
            if (parsed instanceof List<?> l) {
                v = new ArrayList<>(l);
            }
            try {
                field.set(target, v);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
