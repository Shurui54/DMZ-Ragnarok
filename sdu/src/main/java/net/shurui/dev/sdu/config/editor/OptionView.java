package net.shurui.dev.sdu.config.editor;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * One editable option as the client sees it: everything the screen needs to draw and check a row, and nothing more.
 * Values travel as strings; a {@link OptionType#LIST} value is its elements joined with {@link #LIST_SEP}.
 *
 * @param id          stable option id, {@code <source>:<path>}, sent back on save
 * @param tab         tab id (see {@link ConfigTabs})
 * @param group       sub-header the row is listed under (the file and section it lives in)
 * @param labelKey    translation key the client uses for the label when it has one
 * @param label       fallback label (the key, made readable)
 * @param comment     the option's own comment from its file, for the tooltip
 * @param type        value kind
 * @param elementType element kind of a list (INT, LONG, DOUBLE or STRING), else the same as {@code type}
 * @param min         lower bound for numbers, or "" when unbounded
 * @param max         upper bound for numbers, or "" when unbounded
 * @param choices     allowed values of an ENUM
 * @param value       current value
 * @param def         default value
 * @param restart     the change only takes effect after a server (or game) restart
 * @param keyOnly     belongs to a Ragnarok Key feature (only ever sent when the key is present)
 */
public record OptionView(String id, String tab, String group, String labelKey, String label, String comment,
                         OptionType type, OptionType elementType, String min, String max, List<String> choices,
                         String value, String def, boolean restart, boolean keyOnly) {

    /** Separator between list elements inside one value string (ASCII unit separator, never typed). */
    public static final char LIST_SEP = '\u001F';

    private static final int MAX_TEXT = 32767;

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(id);
        buf.writeUtf(tab);
        buf.writeUtf(group);
        buf.writeUtf(labelKey);
        buf.writeUtf(label);
        buf.writeUtf(cut(comment));
        buf.writeVarInt(type.ordinal());
        buf.writeVarInt(elementType.ordinal());
        buf.writeUtf(min);
        buf.writeUtf(max);
        buf.writeVarInt(choices.size());
        for (String c : choices) {
            buf.writeUtf(c);
        }
        buf.writeUtf(cut(value));
        buf.writeUtf(cut(def));
        buf.writeBoolean(restart);
        buf.writeBoolean(keyOnly);
    }

    public static OptionView decode(FriendlyByteBuf buf) {
        String id = buf.readUtf();
        String tab = buf.readUtf();
        String group = buf.readUtf();
        String labelKey = buf.readUtf();
        String label = buf.readUtf();
        String comment = buf.readUtf();
        OptionType type = OptionType.values()[buf.readVarInt()];
        OptionType element = OptionType.values()[buf.readVarInt()];
        String min = buf.readUtf();
        String max = buf.readUtf();
        int n = buf.readVarInt();
        List<String> choices = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            choices.add(buf.readUtf());
        }
        String value = buf.readUtf();
        String def = buf.readUtf();
        boolean restart = buf.readBoolean();
        boolean keyOnly = buf.readBoolean();
        return new OptionView(id, tab, group, labelKey, label, comment, type, element, min, max, choices, value, def,
                restart, keyOnly);
    }

    private static String cut(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    /** Split a list value into its elements ("" is the empty list). */
    public static List<String> splitList(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return out;
        }
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == LIST_SEP) {
                out.add(value.substring(start, i));
                start = i + 1;
            }
        }
        out.add(value.substring(start));
        return out;
    }

    /** Join list elements into one value string. */
    public static String joinList(List<?> elements) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) {
                sb.append(LIST_SEP);
            }
            sb.append(String.valueOf(elements.get(i)));
        }
        return sb.toString();
    }
}
