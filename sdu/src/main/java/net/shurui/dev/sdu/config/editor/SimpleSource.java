package net.shurui.dev.sdu.config.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Options backed by plain getters and setters, for owners that keep a value in their own small file or properties
 * (a grave timer, a client toggle). Each setter is the owner's own, so it persists and validates as it always did;
 * {@code commit} is for owners that save once per batch.
 */
public final class SimpleSource extends ConfigSource {

    private final List<ConfigEntry> entries = new ArrayList<>();
    private final Runnable commit;

    public SimpleSource(String id, String file, Runnable commit) {
        super(id, file);
        this.commit = commit;
    }

    public SimpleSource bool(String key, String tab, String comment, Supplier<Boolean> get, Consumer<Boolean> set,
                             boolean def) {
        return add(key, tab, comment, OptionType.BOOL, null, null, get::get, v -> set.accept((Boolean) v), def);
    }

    public SimpleSource integer(String key, String tab, String comment, Supplier<Integer> get, Consumer<Integer> set,
                                int def, int min, int max) {
        return add(key, tab, comment, OptionType.INT, (double) min, (double) max, get::get,
                v -> set.accept((Integer) v), def);
    }

    public SimpleSource decimal(String key, String tab, String comment, Supplier<Double> get, Consumer<Double> set,
                                double def, double min, double max) {
        return add(key, tab, comment, OptionType.DOUBLE, min, max, get::get, v -> set.accept((Double) v), def);
    }

    /** A fixed choice of values (shown as a dropdown). */
    public SimpleSource choice(String key, String tab, String comment, List<String> choices, Supplier<String> get,
                               Consumer<String> set, String def) {
        add(key, tab, comment, OptionType.ENUM, null, null, get::get, v -> set.accept((String) v), def);
        entries.get(entries.size() - 1).choices = List.copyOf(choices);
        return this;
    }

    /** Give the most recently added option a label other than its key. */
    public SimpleSource label(String text) {
        if (!entries.isEmpty()) {
            entries.get(entries.size() - 1).label = text;
        }
        return this;
    }

    /** A live line under the most recently added option's comment. */
    public SimpleSource note(Supplier<String> note) {
        if (!entries.isEmpty()) {
            entries.get(entries.size() - 1).note = note;
        }
        return this;
    }

    /** Mark the most recently added option as needing a restart. */
    public SimpleSource restart() {
        if (!entries.isEmpty()) {
            entries.get(entries.size() - 1).restart = true;
        }
        return this;
    }

    private SimpleSource add(String key, String tab, String comment, OptionType type, Double min, Double max,
                             Supplier<Object> get, Consumer<Object> set, Object def) {
        ConfigEntry e = new ConfigEntry(id + ":" + key, key, file, type) {
            @Override
            public Object currentObject() {
                return get.get();
            }

            @Override
            public Object defaultObject() {
                return def;
            }

            @Override
            protected void store(Object parsed) {
                set.accept(parsed);
            }
        };
        e.tab = tab;
        e.group = file;
        e.comment = comment == null ? "" : comment;
        e.min = min;
        e.max = max;
        entries.add(e);
        return this;
    }

    @Override
    public List<ConfigEntry> entries() {
        return entries;
    }

    @Override
    public void commit() {
        if (commit != null) {
            commit.run();
        }
    }
}
