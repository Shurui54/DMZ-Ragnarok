package net.shurui.dev.shuruis_raid_bosses.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses legacy ampersand color codes (&amp;a, &amp;6, &amp;l, ...) into a Minecraft {@link Component},
 * so server owners can style config messages the familiar Bukkit way.
 */
public final class TextUtil {
    private TextUtil() {}

    private static final String CODES = "0123456789abcdef";

    public static Component color(String input) {
        MutableComponent result = Component.empty();
        List<Character> pending = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        Style style = Style.EMPTY;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < input.length()) {
                char code = Character.toLowerCase(input.charAt(++i));
                if (buf.length() > 0) {
                    result.append(Component.literal(buf.toString()).setStyle(style));
                    buf.setLength(0);
                }
                style = applyCode(style, code);
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) result.append(Component.literal(buf.toString()).setStyle(style));
        pending.clear();
        return result;
    }

    /**
     * Format a whole-second duration as {@code H:MM:SS}, omitting the hours block when it is zero
     * ({@code M:SS}). Examples: {@code 3661 -> "1:01:01"}, {@code 125 -> "2:05"}, {@code 0 -> "0:00"}.
     * Negative inputs are clamped to zero.
     */
    public static String formatDuration(long seconds)
    {
        if (seconds < 0)
            seconds = 0;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    private static Style applyCode(Style style, char code) {
        if (CODES.indexOf(code) >= 0) {
            return Style.EMPTY.withColor(TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.getByCode(code)));
        }
        return switch (code) {
            case 'l' -> style.withBold(true);
            case 'o' -> style.withItalic(true);
            case 'n' -> style.withUnderlined(true);
            case 'm' -> style.withStrikethrough(true);
            case 'k' -> style.withObfuscated(true);
            case 'r' -> Style.EMPTY;
            default -> style;
        };
    }
}
