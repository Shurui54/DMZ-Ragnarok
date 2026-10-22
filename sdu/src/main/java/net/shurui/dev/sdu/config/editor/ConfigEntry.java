package net.shurui.dev.sdu.config.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * One editable option on the side that owns it (the server for server options, the game client for the Client tab).
 * Subclasses say where the value lives; this class does the parsing and bound checks every save goes through, so a
 * value is only ever stored after it parsed as its type and passed its range and its owner's own validator.
 */
public abstract class ConfigEntry {

    /** Longest string a text option accepts. */
    public static final int MAX_STRING = 4096;
    /** Most elements a list option accepts. */
    public static final int MAX_LIST = 512;

    public final String id;
    public final String path;
    public final String file;
    public String tab;
    public String group;
    public String labelKey;
    public String label;
    public String comment = "";
    public final OptionType type;
    public OptionType elementType;
    /** Lower and upper bound as numbers, or null when unbounded. */
    public Double min;
    public Double max;
    public List<String> choices = List.of();
    public boolean restart;
    public boolean keyOnly;
    /** Optional live line added under the comment (for example which value is in force right now). */
    public java.util.function.Supplier<String> note;

    protected ConfigEntry(String id, String path, String file, OptionType type) {
        this.id = id;
        this.path = path;
        this.file = file;
        this.type = type;
        this.elementType = type;
        this.label = ConfigLabels.humanize(path);
        this.labelKey = "config.dmz_ragnarok." + id.replace(':', '.');
    }

    /** The live value, in its stored Java form. */
    public abstract Object currentObject();

    /** The default value, in its stored Java form. */
    public abstract Object defaultObject();

    /** Store an already parsed and checked value. */
    protected abstract void store(Object parsed);

    /** The owner's own validator on top of the type and range checks (a Forge spec's validator, say). */
    protected boolean accepts(Object parsed) {
        return true;
    }

    /** Turn an ENUM choice back into what the owner stores (an enum constant, or the string). */
    protected Object enumValue(String choice) {
        return choice;
    }

    public final String current() {
        return format(currentObject());
    }

    public final String defaultValue() {
        return format(defaultObject());
    }

    /** {@code null} when {@code raw} may be stored, else why not, in plain words. */
    public final String validate(String raw) {
        try {
            Object v = parse(raw);
            String bad = checkRange(v);
            if (bad != null) {
                return bad;
            }
            return accepts(v) ? null : "not an allowed value here";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Store {@code raw}. Call {@link #validate} first; this throws on a value that does not parse. */
    public final void apply(String raw) {
        store(parse(raw));
    }

    public OptionView view() {
        List<String> ch = choices == null ? List.of() : choices;
        String c = comment == null ? "" : comment;
        if (note != null) {
            try {
                String n = note.get();
                if (n != null && !n.isEmpty()) {
                    c = c.isEmpty() ? n : c + "\n" + n;
                }
            } catch (RuntimeException ignored) {
                // a note is decoration; never let it break the row
            }
        }
        return new OptionView(id, tab, group == null ? file : group, labelKey, label, c,
                type, elementType, shownBound(min), shownBound(max), ch, current(),
                defaultValue(), restart, keyOnly);
    }

    /** A bound as the client shows it: blank when it is only the type's own limit (no real limit). */
    private String shownBound(Double b) {
        if (b == null) {
            return "";
        }
        OptionType t = type == OptionType.LIST ? elementType : type;
        double a = Math.abs(b);
        boolean typeLimit = (t == OptionType.INT && a >= Integer.MAX_VALUE)
                || (t == OptionType.LONG && a >= 9.0E18) || (t == OptionType.DOUBLE && a >= 1.0E300);
        return typeLimit ? "" : fmtBound(b);
    }

    private String fmtBound(double d) {
        OptionType t = type == OptionType.LIST ? elementType : type;
        if (t == OptionType.DOUBLE) {
            return Double.toString(d);
        }
        return Long.toString((long) d);
    }

    // ---- parsing ----

    protected Object parse(String raw) {
        String s = raw == null ? "" : raw;
        if (type == OptionType.LIST) {
            List<String> parts = OptionView.splitList(s);
            if (parts.size() > MAX_LIST) {
                throw new IllegalArgumentException("too many entries (at most " + MAX_LIST + ")");
            }
            List<Object> out = new ArrayList<>(parts.size());
            for (String p : parts) {
                out.add(parseScalar(elementType, p));
            }
            return out;
        }
        if (type == OptionType.ENUM) {
            String c = s.trim();
            if (!choices.contains(c)) {
                throw new IllegalArgumentException("must be one of " + String.join(", ", choices));
            }
            return enumValue(c);
        }
        return parseScalar(type, s);
    }

    private static Object parseScalar(OptionType t, String s) {
        String v = s.trim();
        switch (t) {
            case BOOL -> {
                if (v.equalsIgnoreCase("true")) {
                    return Boolean.TRUE;
                }
                if (v.equalsIgnoreCase("false")) {
                    return Boolean.FALSE;
                }
                throw new IllegalArgumentException("must be true or false");
            }
            case INT -> {
                try {
                    return Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("\"" + v + "\" is not a whole number");
                }
            }
            case LONG -> {
                try {
                    return Long.parseLong(v);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("\"" + v + "\" is not a whole number");
                }
            }
            case DOUBLE -> {
                try {
                    double d = Double.parseDouble(v);
                    if (Double.isNaN(d) || Double.isInfinite(d)) {
                        throw new NumberFormatException();
                    }
                    return d;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("\"" + v + "\" is not a number");
                }
            }
            default -> {
                if (s.length() > MAX_STRING) {
                    throw new IllegalArgumentException("too long (at most " + MAX_STRING + " characters)");
                }
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c < 0x20 && c != '\t') {
                        throw new IllegalArgumentException("contains a control character");
                    }
                }
                return s;
            }
        }
    }

    private String checkRange(Object v) {
        if (min == null && max == null) {
            return null;
        }
        if (v instanceof List<?> list) {
            for (Object o : list) {
                String bad = checkOne(o);
                if (bad != null) {
                    return bad;
                }
            }
            return null;
        }
        return checkOne(v);
    }

    private String checkOne(Object v) {
        if (!(v instanceof Number n)) {
            return null;
        }
        double d = n.doubleValue();
        if ((min != null && d < min) || (max != null && d > max)) {
            return "out of range: must be from " + (min == null ? "any" : fmtBound(min)) + " to "
                    + (max == null ? "any" : fmtBound(max));
        }
        return null;
    }

    // ---- formatting ----

    public static String format(Object o) {
        if (o == null) {
            return "";
        }
        if (o instanceof Enum<?> e) {
            return e.name();
        }
        if (o instanceof List<?> l) {
            List<String> parts = new ArrayList<>(l.size());
            for (Object x : l) {
                parts.add(format(x));
            }
            return OptionView.joinList(parts);
        }
        if (o instanceof Float f) {
            return Double.toString(f.doubleValue());
        }
        return String.valueOf(o);
    }
}
