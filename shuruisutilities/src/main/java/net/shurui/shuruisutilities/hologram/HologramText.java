package net.shurui.shuruisutilities.hologram;

import java.lang.management.ManagementFactory;
import java.util.Locale;

import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Turns a hologram's raw line into the coloured {@link Component} a TextDisplay shows.
 *
 * <p>Four things happen here, in order, because each one feeds the next:
 *
 * <ol>
 *   <li>ANIMATION. A line may hold several frames separated by {@code ||}; the one showing right now is picked
 *       from the tick, so {@code Tip one||Tip two} alternates on its own.</li>
 *   <li>PLACEHOLDERS. {@code %players%}, {@code %tps%} and friends are substituted for live server values.</li>
 *   <li>EFFECTS. {@code <rainbow>} and {@code <gradient:#aabbcc:#ddeeff>} colour a run of text per character.</li>
 *   <li>CODES. The usual {@code &a} colours and {@code &l} styles, plus {@code &#rrggbb} for any colour at all.</li>
 * </ol>
 *
 * <p>Per-character colouring is why this cannot just hand the string to {@link ChatOutputHandler#formatColors}: a
 * gradient needs a different colour on every letter, which is a component per letter, not a section sign.
 *
 * <p>Everything here is SERVER-WIDE. A TextDisplay is one entity showing one string to everybody who can see it,
 * so a per-viewer placeholder (a player's own name, their rank, their coordinates) has nowhere to live. Those are
 * deliberately absent rather than faked: supporting them means not using shared display entities at all.
 */
public final class HologramText
{
    private HologramText() {}

    /** Splits one line into the frames it cycles through. */
    public static final String FRAME_SEPARATOR = "||";

    /** Ticks the rainbow takes to travel one full turn of the colour wheel. */
    private static final float RAINBOW_PERIOD = 60.0f;

    /** How far the hue shifts from one character to the next, so a rainbow reads as a band and not a flat colour. */
    private static final float RAINBOW_SPREAD = 0.055f;

    private static final long SERVER_START = System.currentTimeMillis();

    /**
     * Build the component for a line as it should look on this tick.
     *
     * @param tick the server tick, which drives frame cycling and the rainbow. Pass 0 for a still image.
     */
    public static Component build(String raw, long tick)
    {
        if (raw == null)
            return Component.empty();
        return parse(placeholders(frame(raw, tick)), tick);
    }

    /** True if this line's appearance changes over time, so the manager knows whether it has to keep redrawing. */
    public static boolean isAnimated(String raw)
    {
        if (raw == null || raw.isEmpty())
            return false;
        return raw.contains(FRAME_SEPARATOR) || raw.contains("<rainbow>") || raw.contains("%top:")
                || hasPlaceholder(raw);
    }

    /** The frame showing now. A line with no separator is a single frame and never changes. */
    private static String frame(String raw, long tick)
    {
        int at = raw.indexOf(FRAME_SEPARATOR);
        if (at < 0)
            return raw;
        String[] frames = raw.split(java.util.regex.Pattern.quote(FRAME_SEPARATOR), -1);
        // negative ticks would index backwards off the array, so the modulus is taken positive.
        int index = (int) Math.floorMod(tick / HologramManager.FRAME_TICKS, frames.length);
        return frames[index];
    }

    private static final String[] PLACEHOLDERS = {
            "%players%", "%maxplayers%", "%tps%", "%uptime%", "%memory%", "%server_time%", "%motd%",
    };

    /** The placeholders an admin can use, for command tab completion and the editor's help line. */
    public static String[] placeholderNames()
    {
        return PLACEHOLDERS.clone();
    }

    private static boolean hasPlaceholder(String raw)
    {
        for (String key : PLACEHOLDERS)
            if (raw.contains(key))
                return true;
        return false;
    }

    private static String placeholders(String text)
    {
        if (text.indexOf('%') < 0)
            return text;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return text;

        if (text.contains("%players%"))
            text = text.replace("%players%", Integer.toString(server.getPlayerCount()));
        if (text.contains("%maxplayers%"))
            text = text.replace("%maxplayers%", Integer.toString(server.getMaxPlayers()));
        if (text.contains("%tps%"))
            text = text.replace("%tps%", String.format(Locale.ROOT, "%.1f", tps(server)));
        if (text.contains("%uptime%"))
            text = text.replace("%uptime%", uptime());
        if (text.contains("%memory%"))
            text = text.replace("%memory%", memory());
        if (text.contains("%server_time%"))
            text = text.replace("%server_time%",
                    java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
        if (text.contains("%motd%"))
            text = text.replace("%motd%", server.getMotd() == null ? "" : server.getMotd());
        return leaderboard(text, server);
    }

    /** {@code %top:<objective>:<rank>%}, which is how a hologram becomes a scoreboard leaderboard. */
    private static final java.util.regex.Pattern TOP =
            java.util.regex.Pattern.compile("%top:([A-Za-z0-9_.+-]+):(\\d+)%");

    private static String leaderboard(String text, MinecraftServer server)
    {
        if (!text.contains("%top:"))
            return text;
        java.util.regex.Matcher matcher = TOP.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find())
        {
            int rank = Integer.parseInt(matcher.group(2));
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(topEntry(server, matcher.group(1), rank)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The nth highest scorer on an objective, as "name - score", or a dash when there is nobody there. */
    private static String topEntry(MinecraftServer server, String objectiveName, int rank)
    {
        var scoreboard = server.getScoreboard();
        var objective = scoreboard.getObjective(objectiveName);
        if (objective == null || rank < 1)
            return "-";
        java.util.List<net.minecraft.world.scores.Score> scores =
                new java.util.ArrayList<>(scoreboard.getPlayerScores(objective));
        // highest first, which is what a leaderboard means; the scoreboard itself keeps no order worth trusting.
        scores.sort(java.util.Comparator.comparingInt(net.minecraft.world.scores.Score::getScore).reversed());
        if (rank > scores.size())
            return "-";
        net.minecraft.world.scores.Score score = scores.get(rank - 1);
        return score.getOwner() + " - " + score.getScore();
    }

    private static double tps(MinecraftServer server)
    {
        long total = 0L;
        long[] times = server.tickTimes;
        if (times == null || times.length == 0)
            return 20.0D;
        for (long t : times)
            total += t;
        double meanMillis = (total / (double) times.length) / 1_000_000.0D;
        // a server keeping up ticks in under 50ms; it cannot exceed 20 TPS however fast it is.
        return meanMillis <= 0.0D ? 20.0D : Math.min(20.0D, 1000.0D / meanMillis);
    }

    private static String uptime()
    {
        long seconds = (System.currentTimeMillis() - SERVER_START) / 1000L;
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (days > 0)
            return days + "d " + hours + "h";
        if (hours > 0)
            return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private static String memory()
    {
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long maxMb = runtime.maxMemory() / (1024L * 1024L);
        // touched so the management factory import is not dead weight on servers that strip it; also the cheapest
        // way to keep this honest if the JVM reports an unbounded heap.
        if (maxMb <= 0)
            maxMb = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax() / (1024L * 1024L);
        return usedMb + "/" + maxMb + "MB";
    }

    /** Which per-character colouring is in force over the current run of text. */
    private enum Effect { NONE, RAINBOW, GRADIENT }

    private static Component parse(String text, long tick)
    {
        MutableComponent out = Component.empty();
        StringBuilder run = new StringBuilder();
        Style style = Style.EMPTY;

        Effect effect = Effect.NONE;
        int effectFrom = 0;   // colour A of a gradient
        int effectTo = 0;     // colour B of a gradient
        int effectIndex = 0;  // how many characters into the effect we are
        int effectLength = 1; // how many characters the effect spans, for interpolation

        int i = 0;
        int len = text.length();
        while (i < len)
        {
            char c = text.charAt(i);

            if (c == '<')
            {
                int close = text.indexOf('>', i);
                if (close > i)
                {
                    String tag = text.substring(i + 1, close).toLowerCase(Locale.ROOT);
                    if (tag.equals("rainbow"))
                    {
                        flush(out, run, style);
                        effect = Effect.RAINBOW;
                        effectIndex = 0;
                        i = close + 1;
                        continue;
                    }
                    if (tag.equals("/rainbow") || tag.equals("/gradient"))
                    {
                        flush(out, run, style);
                        effect = Effect.NONE;
                        i = close + 1;
                        continue;
                    }
                    if (tag.startsWith("gradient:"))
                    {
                        String[] parts = tag.substring("gradient:".length()).split(":");
                        Integer a = parts.length > 0 ? hex(parts[0]) : null;
                        Integer b = parts.length > 1 ? hex(parts[1]) : null;
                        if (a != null && b != null)
                        {
                            flush(out, run, style);
                            effect = Effect.GRADIENT;
                            effectFrom = a;
                            effectTo = b;
                            effectIndex = 0;
                            effectLength = Math.max(1, visibleLength(text, close + 1) - 1);
                            i = close + 1;
                            continue;
                        }
                    }
                }
            }

            if (c == '&' && i + 1 < len)
            {
                char next = text.charAt(i + 1);
                if (next == '&')
                {
                    run.append('&');
                    i += 2;
                    continue;
                }
                if (next == '#' && i + 8 <= len)
                {
                    Integer rgb = hex(text.substring(i + 1, i + 8));
                    if (rgb != null)
                    {
                        flush(out, run, style);
                        style = style.withColor(TextColor.fromRgb(rgb));
                        i += 8;
                        continue;
                    }
                }
                ChatFormatting fmt = ChatFormatting.getByCode(Character.toLowerCase(next));
                if (fmt != null)
                {
                    flush(out, run, style);
                    style = apply(style, fmt);
                    i += 2;
                    continue;
                }
            }

            if (effect == Effect.NONE)
            {
                run.append(c);
            }
            else
            {
                // a per-character colour cannot share a run, so each letter becomes its own component
                flush(out, run, style);
                int rgb = effect == Effect.RAINBOW
                        ? rainbow(effectIndex, tick)
                        : lerpColor(effectFrom, effectTo, effectIndex / (float) effectLength);
                out.append(Component.literal(String.valueOf(c)).withStyle(style.withColor(TextColor.fromRgb(rgb))));
                effectIndex++;
            }
            i++;
        }
        flush(out, run, style);
        return out;
    }

    /**
     * Emit and clear the pending run of same-styled text.
     *
     * <p>Nothing is appended when the run is empty. That matters more than it looks: in an effect every character
     * is its own component and this is called before each one, so appending an empty sibling each time would
     * double the size of a rainbow line for no visible difference.
     */
    private static void flush(MutableComponent out, StringBuilder run, Style style)
    {
        if (run.isEmpty())
            return;
        out.append(Component.literal(run.toString()).setStyle(style));
        run.setLength(0);
    }

    /**
     * Apply a formatting code the way a chat client would: a colour REPLACES the colour and clears decorations,
     * a decoration adds to what is there, and reset clears everything.
     */
    private static Style apply(Style style, ChatFormatting fmt)
    {
        if (fmt == ChatFormatting.RESET)
            return Style.EMPTY;
        if (fmt.isColor())
            return Style.EMPTY.withColor(fmt);
        return style.applyFormat(fmt);
    }

    /** How many printable characters remain from an index, ignoring tags and codes. */
    private static int visibleLength(String text, int from)
    {
        int count = 0;
        int i = from;
        while (i < text.length())
        {
            char c = text.charAt(i);
            if (c == '<')
            {
                int close = text.indexOf('>', i);
                if (close > i)
                {
                    if (text.startsWith("</gradient>", i))
                        break;
                    i = close + 1;
                    continue;
                }
            }
            if (c == '&' && i + 1 < text.length())
            {
                char next = text.charAt(i + 1);
                if (next == '#' && i + 8 <= text.length())
                {
                    i += 8;
                    continue;
                }
                if (next != '&' && ChatFormatting.getByCode(Character.toLowerCase(next)) != null)
                {
                    i += 2;
                    continue;
                }
            }
            count++;
            i++;
        }
        return count;
    }

    /** Parse {@code #rrggbb} or {@code rrggbb}, or null if it is not a colour. */
    private static Integer hex(String raw)
    {
        String s = raw.startsWith("#") ? raw.substring(1) : raw;
        if (s.length() != 6)
            return null;
        try
        {
            return Integer.parseInt(s, 16);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static int rainbow(int index, long tick)
    {
        float hue = (index * RAINBOW_SPREAD + tick / RAINBOW_PERIOD) % 1.0f;
        if (hue < 0.0f)
            hue += 1.0f;
        return java.awt.Color.HSBtoRGB(hue, 1.0f, 1.0f) & 0xFFFFFF;
    }

    private static int lerpColor(int from, int to, float t)
    {
        t = Math.min(1.0f, Math.max(0.0f, t));
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }
}
