package net.shurui.shuruisutilities.util.output;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Word wrapping for on-screen titles.
 *
 * <p>Minecraft's title and subtitle are drawn as ONE line each. {@code Gui} measures the string, centres it and
 * scales it up (the title 4x, the subtitle 2x); there is no width constraint and no line breaking, and a newline
 * in the string renders as a missing-glyph box rather than a break. So anything longer than the screen simply runs
 * off both edges. The only way to get a second line is to use the subtitle slot, and the only place the wrapping
 * can happen is here, before the packet leaves the server.
 *
 * <p>The awkward part is that the server cannot measure text: {@code Minecraft.getInstance().font.width} is client
 * side, and the client's GUI scale (which decides how many font pixels fit) is never reported to us. So this class
 * carries its own copy of the default font's ASCII advance table and works to a fixed, deliberately conservative
 * pixel budget. The budgets below fit a 1920x1080 client at GUI scale 3 (a 640 wide GUI) with room to spare, and
 * therefore fit every wider client too. A client on "auto" at 1080p (a 480 wide GUI) can still overrun a title
 * that uses the whole budget; that is the price of not knowing, and erring the other way would split short
 * broadcasts that render perfectly well today.
 *
 * <p>Nothing here ever drops text on its own. {@link #fit} reports whether it had to cut, and the caller is
 * expected to put the whole message somewhere the player can still read it (chat).
 */
public final class TitleWrap
{
    private TitleWrap()
    {
    }

    /** Font pixels a title may use. Drawn at 4x, so this is a 600 px line on a 640 wide GUI. */
    public static final int TITLE_BUDGET = 150;

    /** Font pixels a subtitle may use. Drawn at 2x, so this is a 600 px line on the same GUI. */
    public static final int SUBTITLE_BUDGET = 300;

    private static final char COLOR = '§';
    private static final String ELLIPSIS = "...";

    /**
     * Worst case width of {@link #ELLIPSIS}: three dots at 2 px, plus the 1 px per glyph that bold adds. Used as a
     * flat reservation so the budget does not depend on the formatting state at the cut point.
     */
    private static final int ELLIPSIS_WIDTH = 9;

    /** Advance width (glyph plus the 1 px gap) of printable ASCII in the default font, indexed from ' '. */
    private static final int[] ASCII_ADVANCE = new int[95];

    static
    {
        Arrays.fill(ASCII_ADVANCE, 6);
        advance(' ', 4);
        advance('!', 2);
        advance('"', 5);
        advance('\'', 3);
        advance('(', 5);
        advance(')', 5);
        advance('*', 5);
        advance(',', 2);
        advance('.', 2);
        advance(':', 2);
        advance(';', 2);
        advance('<', 5);
        advance('>', 5);
        advance('@', 7);
        advance('I', 4);
        advance('[', 4);
        advance(']', 4);
        advance('`', 3);
        advance('f', 5);
        advance('i', 2);
        advance('k', 5);
        advance('l', 3);
        advance('t', 4);
        advance('{', 5);
        advance('|', 2);
        advance('}', 5);
        advance('~', 7);
    }

    private static void advance(char c, int width)
    {
        ASCII_ADVANCE[c - ' '] = width;
    }

    /**
     * Width of one character in font pixels. Anything outside printable ASCII is assumed to be a full 6 px cell,
     * which is right for most of the Latin supplement and an under-estimate for CJK (9 px there). Titles are not
     * the place to reimplement the whole glyph provider chain.
     */
    private static int charWidth(char c, boolean bold)
    {
        int w = (c >= ' ' && c <= '~') ? ASCII_ADVANCE[c - ' '] : 6;
        return bold ? w + 1 : w;
    }

    /** Width of a string that already carries section-sign formatting codes. The codes themselves are free. */
    public static int width(String formatted)
    {
        if (formatted == null || formatted.isEmpty())
        {
            return 0;
        }
        Fmt fmt = new Fmt();
        int w = 0;
        int i = 0;
        while (i < formatted.length())
        {
            char c = formatted.charAt(i);
            if (c == COLOR && i + 1 < formatted.length())
            {
                fmt.apply(formatted.charAt(i + 1));
                i += 2;
                continue;
            }
            w += charWidth(c, fmt.bold);
            i++;
        }
        return w;
    }

    /**
     * What a message turned into once it was made to fit the two title slots.
     *
     * @param title      the title line, never null
     * @param subtitle   the subtitle line, or null when there is none
     * @param overflowed true when something had to be cut, so the caller MUST show the full text elsewhere
     */
    public record Fitted(String title, String subtitle, boolean overflowed)
    {
    }

    /**
     * Fit a title (and an optional operator-supplied subtitle) into what the screen can actually draw.
     *
     * <p>A message that already fits comes back untouched, so a broadcast that looks right today is not changed in
     * any way. A long title with no subtitle of its own wraps onto the subtitle line at a word boundary. A long
     * title that ALSO has a subtitle cannot borrow that line, so each is cut to its own budget with a trailing
     * "..." to show it continues. In both cut cases {@code overflowed} is true.
     */
    public static Fitted fit(String title, String subtitle)
    {
        String t = title == null ? "" : title;
        String s = (subtitle == null || subtitle.isEmpty()) ? null : subtitle;

        boolean titleFits = width(t) <= TITLE_BUDGET;
        boolean subFits = s == null || width(s) <= SUBTITLE_BUDGET;
        if (titleFits && subFits)
        {
            return new Fitted(t, s, false);
        }

        if (s == null)
        {
            // The subtitle slot is free, so it becomes the second line. It is drawn smaller than the title, which
            // reads a little oddly for a long sentence, but it is a whole readable message instead of half of one.
            Wrapped w = wrap(t, TITLE_BUDGET, SUBTITLE_BUDGET, 2);
            String first = w.lines.get(0);
            String second = w.lines.size() > 1 ? w.lines.get(1) : null;
            if (w.remainder.isEmpty())
            {
                return new Fitted(first, second, false);
            }
            return new Fitted(first, ellipsise(second == null ? "" : second, SUBTITLE_BUDGET), true);
        }

        String fittedTitle = titleFits ? t : ellipsise(firstLine(t, TITLE_BUDGET), TITLE_BUDGET);
        String fittedSub = subFits ? s : ellipsise(firstLine(s, SUBTITLE_BUDGET), SUBTITLE_BUDGET);
        return new Fitted(fittedTitle, fittedSub, true);
    }

    /**
     * Fit a string into the subtitle slot on its own, cutting at a word boundary with a trailing "..." if it is too
     * long. For callers whose second line is already spoken for and whose full text is delivered in chat anyway.
     */
    public static String fitSubtitle(String formatted)
    {
        String s = formatted == null ? "" : formatted;
        return width(s) <= SUBTITLE_BUDGET ? s : ellipsise(firstLine(s, SUBTITLE_BUDGET), SUBTITLE_BUDGET);
    }

    private static String firstLine(String text, int budget)
    {
        return wrap(text, budget, budget, 1).lines.get(0);
    }

    /** Cut a line down so that it plus a trailing "..." fits the budget. */
    private static String ellipsise(String line, int budget)
    {
        int room = budget - ELLIPSIS_WIDTH;
        if (room <= 0)
        {
            return line;
        }
        Fmt fmt = new Fmt();
        int w = 0;
        int cut = line.length();
        int i = 0;
        while (i < line.length())
        {
            char c = line.charAt(i);
            if (c == COLOR && i + 1 < line.length())
            {
                fmt.apply(line.charAt(i + 1));
                i += 2;
                continue;
            }
            int a = charWidth(c, fmt.bold);
            if (w + a > room)
            {
                cut = i;
                break;
            }
            w += a;
            i++;
        }
        String kept = line.substring(0, cut);
        while (kept.endsWith(" "))
        {
            kept = kept.substring(0, kept.length() - 1);
        }
        return kept + ELLIPSIS;
    }

    private static final class Wrapped
    {
        final List<String> lines = new ArrayList<>();
        String remainder = "";
    }

    /**
     * Greedy word wrap over a formatted string. Breaks on the last space that still fits, or mid word when a single
     * run is longer than the whole line. Formatting is carried across a break: the state in force at the break point
     * is re-emitted at the head of the next line, so a coloured or bold message does not lose its look halfway.
     */
    private static Wrapped wrap(String text, int firstBudget, int laterBudget, int maxLines)
    {
        Wrapped out = new Wrapped();
        if (text == null || text.isEmpty())
        {
            out.lines.add("");
            return out;
        }

        Fmt lineStartFmt = new Fmt();
        Fmt cur = lineStartFmt.copy();
        Fmt spaceFmt = null;
        int lineStart = 0;
        int lastSpace = -1;
        int width = 0;
        int budget = firstBudget;
        int i = 0;

        while (i < text.length())
        {
            char c = text.charAt(i);
            if (c == COLOR && i + 1 < text.length())
            {
                cur.apply(text.charAt(i + 1));
                i += 2;
                continue;
            }
            int a = charWidth(c, cur.bold);
            if (width + a > budget && i > lineStart)
            {
                int breakAt;
                int resume;
                Fmt nextFmt;
                if (lastSpace > lineStart)
                {
                    breakAt = lastSpace;
                    resume = lastSpace + 1;
                    nextFmt = spaceFmt.copy();
                }
                else
                {
                    // One unbroken run wider than the line. Break it rather than let it run off the screen.
                    breakAt = i;
                    resume = i;
                    nextFmt = cur.copy();
                }
                out.lines.add(lineStartFmt.prefix() + text.substring(lineStart, breakAt));
                if (out.lines.size() >= maxLines)
                {
                    out.remainder = nextFmt.prefix() + text.substring(resume);
                    return out;
                }
                lineStartFmt = nextFmt;
                cur = nextFmt.copy();
                lineStart = resume;
                i = resume;
                width = 0;
                lastSpace = -1;
                spaceFmt = null;
                budget = laterBudget;
                continue;
            }
            if (c == ' ')
            {
                lastSpace = i;
                spaceFmt = cur.copy();
            }
            width += a;
            i++;
        }
        out.lines.add(lineStartFmt.prefix() + text.substring(lineStart));
        return out;
    }

    /** The legacy formatting state, tracked so width knows about bold and so a wrapped line keeps its look. */
    private static final class Fmt
    {
        char colour = 0;
        boolean obfuscated;
        boolean bold;
        boolean strikethrough;
        boolean underline;
        boolean italic;

        Fmt copy()
        {
            Fmt f = new Fmt();
            f.colour = colour;
            f.obfuscated = obfuscated;
            f.bold = bold;
            f.strikethrough = strikethrough;
            f.underline = underline;
            f.italic = italic;
            return f;
        }

        void apply(char raw)
        {
            char c = Character.toLowerCase(raw);
            if (c == 'r')
            {
                reset();
                return;
            }
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))
            {
                // A colour code clears the other formatting, which is what vanilla's legacy code handling does.
                reset();
                colour = c;
                return;
            }
            switch (c)
            {
                case 'k' -> obfuscated = true;
                case 'l' -> bold = true;
                case 'm' -> strikethrough = true;
                case 'n' -> underline = true;
                case 'o' -> italic = true;
                default ->
                {
                    // Not a formatting code. formatColors never emits one, so there is nothing to carry.
                }
            }
        }

        private void reset()
        {
            colour = 0;
            obfuscated = false;
            bold = false;
            strikethrough = false;
            underline = false;
            italic = false;
        }

        String prefix()
        {
            if (colour == 0 && !obfuscated && !bold && !strikethrough && !underline && !italic)
            {
                return "";
            }
            StringBuilder sb = new StringBuilder(12);
            if (colour != 0)
            {
                sb.append(COLOR).append(colour);
            }
            if (obfuscated)
            {
                sb.append(COLOR).append('k');
            }
            if (bold)
            {
                sb.append(COLOR).append('l');
            }
            if (strikethrough)
            {
                sb.append(COLOR).append('m');
            }
            if (underline)
            {
                sb.append(COLOR).append('n');
            }
            if (italic)
            {
                sb.append(COLOR).append('o');
            }
            return sb.toString();
        }
    }
}
