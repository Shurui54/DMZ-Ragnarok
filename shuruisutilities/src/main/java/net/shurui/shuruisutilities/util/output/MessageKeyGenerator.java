package net.shurui.shuruisutilities.util.output;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Offline generator for the per-player chat lang entries. It scans the source tree for every
 * {@code ChatOutputHandler.chat*} call whose message argument is a DIRECT string literal (the exact
 * set of templates that {@link ChatOutputHandler#chatTranslatable} routes through
 * {@code Component.translatableWithFallback}), runs each through the production
 * {@link MessageKeys#keyFor(String)} and {@link MessageKeys#escapeFormat(String)}, and writes the
 * resulting keys into {@code en_us.json} (English values) while ADDING any missing keys to
 * {@code es_es.json} (seeded with the English text for a human to translate).
 *
 * <p>Because both this generator and the runtime call the SAME {@link MessageKeys#keyFor}, the two
 * cannot drift: regenerate and the key set is byte-for-byte reproducible.
 *
 * <p>Run with: {@code ./gradlew :shuruisutilities:genChatLang} (see build.gradle), or invoke this
 * {@code main} directly with the module source root as arg[0] and the lang dir as arg[1].
 */
public final class MessageKeyGenerator
{
    private static final String[] CHAT_METHODS = { "chatError", "chatConfirmation", "chatNotification",
            "chatWarning" };

    private static final Pattern CALL = Pattern.compile(
            "ChatOutputHandler\\.(chatError|chatConfirmation|chatNotification|chatWarning)\\s*\\(");

    private MessageKeyGenerator()
    {
    }

    public static void main(String[] args) throws IOException
    {
        Path sourceRoot = Path.of(args.length > 0 ? args[0] : "src/main/java");
        Path langDir = Path.of(args.length > 1 ? args[1]
                : "src/main/resources/assets/dmz_ragnarok/lang");
        boolean check = args.length > 2 && "--check".equals(args[2]);

        Map<String, String> english = collect(sourceRoot);
        System.out.println("[MessageKeyGenerator] collected " + english.size() + " distinct translatable templates");

        Path en = langDir.resolve("en_us.json");
        Path es = langDir.resolve("es_es.json");

        Map<String, String> enMap = readJson(en);
        Map<String, String> esMap = readJson(es);

        // Snapshot for --check mode.
        Map<String, String> enBefore = new LinkedHashMap<>(enMap);
        Map<String, String> esBefore = new LinkedHashMap<>(esMap);

        for (Map.Entry<String, String> e : english.entrySet())
        {
            String key = e.getKey();
            String value = MessageKeys.escapeFormat(e.getValue());
            enMap.put(key, value);
            // Seed Spanish with the English value only if the key is brand new; never clobber a
            // hand-written translation.
            esMap.putIfAbsent(key, value);
        }

        if (check)
        {
            boolean drift = !enMap.equals(enBefore) || !esBefore.keySet().equals(esMap.keySet());
            if (drift)
            {
                System.err.println("[MessageKeyGenerator] DRIFT: generated keys differ from committed lang files.");
                System.exit(1);
            }
            System.out.println("[MessageKeyGenerator] --check OK: no drift.");
            return;
        }

        writeJson(en, enMap);
        writeJson(es, esMap);
        System.out.println("[MessageKeyGenerator] wrote " + enMap.size() + " en_us keys, " + esMap.size()
                + " es_es keys");
    }

    /** English template -> its lang key, for every direct-literal, non-colour-coded chat template. */
    static Map<String, String> collect(Path sourceRoot) throws IOException
    {
        Map<String, String> keyToEnglish = new TreeMap<>();
        List<Path> javaFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(sourceRoot))
        {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(javaFiles::add);
        }
        for (Path p : javaFiles)
        {
            String src = Files.readString(p, StandardCharsets.UTF_8);
            Matcher m = CALL.matcher(src);
            while (m.find())
            {
                int open = m.end();
                int close = matchParen(src, open);
                if (close < 0)
                    continue;
                String inner = src.substring(open, close);
                String secondArg = secondArg(inner);
                if (secondArg == null)
                    continue;
                String literal = leadingLiteral(secondArg);
                if (literal == null)
                    continue;
                // Skip colour-coded templates: they stay on the legacy server-side path.
                if (MessageKeys.hasColorCodes(literal))
                    continue;
                keyToEnglish.put(MessageKeys.keyFor(literal), literal);
            }
        }
        // return English keyed by lang key
        return keyToEnglish;
    }

    // index just past the matching close paren for an open paren at position 'open' (which points
    // right after the '('). Returns index of the close paren, or -1.
    private static int matchParen(String src, int open)
    {
        int depth = 1;
        int j = open;
        while (j < src.length() && depth > 0)
        {
            char c = src.charAt(j);
            if (c == '(')
                depth++;
            else if (c == ')')
                depth--;
            else if (c == '"')
            {
                j++;
                while (j < src.length() && src.charAt(j) != '"')
                {
                    if (src.charAt(j) == '\\')
                        j++;
                    j++;
                }
            }
            if (depth == 0)
                return j;
            j++;
        }
        return -1;
    }

    // the text of the second top-level argument (after the sender), or null.
    private static String secondArg(String inner)
    {
        int depth = 0;
        int k = 0;
        int comma = -1;
        while (k < inner.length())
        {
            char c = inner.charAt(k);
            if (c == '(')
                depth++;
            else if (c == ')')
                depth--;
            else if (c == '"')
            {
                k++;
                while (k < inner.length() && inner.charAt(k) != '"')
                {
                    if (inner.charAt(k) == '\\')
                        k++;
                    k++;
                }
            }
            else if (c == ',' && depth == 0)
            {
                comma = k;
                break;
            }
            k++;
        }
        if (comma < 0)
            return null;
        return inner.substring(comma + 1).trim();
    }

    // if 'arg' begins with a Java string literal, decode and return it; else null. Handles a leading
    // literal even when it is the head of a concatenation, matching how chatTranslatable slugifies
    // the runtime string (the runtime slugs the whole concatenated value; for a PURE literal the two
    // agree, and only pure literals are pre-registered here).
    private static String leadingLiteral(String arg)
    {
        if (arg.isEmpty() || arg.charAt(0) != '"')
            return null;
        StringBuilder sb = new StringBuilder();
        int i = 1;
        while (i < arg.length())
        {
            char c = arg.charAt(i);
            if (c == '\\' && i + 1 < arg.length())
            {
                char n = arg.charAt(i + 1);
                switch (n)
                {
                case 'n':
                    sb.append('\n');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '\'':
                    sb.append('\'');
                    break;
                default:
                    sb.append(n);
                    break;
                }
                i += 2;
                continue;
            }
            if (c == '"')
                break;
            sb.append(c);
            i++;
        }
        // Only accept a PURE literal (next non-space char is ',' or ')' / end): a concatenation would
        // produce a runtime value we cannot know at generation time, so it stays an English fallback.
        String tail = arg.substring(Math.min(i + 1, arg.length())).trim();
        if (!tail.isEmpty() && tail.charAt(0) != ',' && tail.charAt(0) != ')')
            return null;
        return sb.toString();
    }

    static Map<String, String> readJson(Path p) throws IOException
    {
        Map<String, String> map = new LinkedHashMap<>();
        if (!Files.exists(p))
            return map;
        String s = Files.readString(p, StandardCharsets.UTF_8);
        int i = 0;
        int n = s.length();
        while (i < n)
        {
            while (i < n && s.charAt(i) != '"')
                i++;
            if (i >= n)
                break;
            int[] kEnd = new int[1];
            String key = readJsonString(s, i, kEnd);
            i = kEnd[0];
            while (i < n && s.charAt(i) != ':')
                i++;
            i++;
            while (i < n && s.charAt(i) != '"')
                i++;
            if (i >= n)
                break;
            int[] vEnd = new int[1];
            String value = readJsonString(s, i, vEnd);
            i = vEnd[0];
            map.put(key, value);
        }
        return map;
    }

    private static String readJsonString(String s, int quotePos, int[] endOut)
    {
        StringBuilder sb = new StringBuilder();
        int i = quotePos + 1;
        while (i < s.length())
        {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length())
            {
                char nx = s.charAt(i + 1);
                switch (nx)
                {
                case 'n':
                    sb.append('\n');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'u':
                    sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                    i += 4;
                    break;
                default:
                    sb.append(nx);
                    break;
                }
                i += 2;
                continue;
            }
            if (c == '"')
            {
                endOut[0] = i + 1;
                return sb.toString();
            }
            sb.append(c);
            i++;
        }
        endOut[0] = i;
        return sb.toString();
    }

    static void writeJson(Path p, Map<String, String> map) throws IOException
    {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        int i = 0;
        int size = map.size();
        for (Map.Entry<String, String> e : map.entrySet())
        {
            sb.append("    \"").append(escapeJson(e.getKey())).append("\": \"").append(escapeJson(e.getValue()))
                    .append("\"");
            if (++i < size)
                sb.append(',');
            sb.append('\n');
        }
        sb.append("}\n");
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String escapeJson(String s)
    {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            switch (c)
            {
            case '"':
                sb.append("\\\"");
                break;
            case '\\':
                sb.append("\\\\");
                break;
            case '\n':
                sb.append("\\n");
                break;
            case '\r':
                sb.append("\\r");
                break;
            case '\t':
                sb.append("\\t");
                break;
            default:
                sb.append(c);
                break;
            }
        }
        return sb.toString();
    }
}
