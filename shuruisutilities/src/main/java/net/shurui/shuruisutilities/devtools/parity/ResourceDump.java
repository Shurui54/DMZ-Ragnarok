package net.shurui.shuruisutilities.devtools.parity;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraftforge.fml.ModList;

/**
 * Resource counts per namespace and lang key counts per locale, read straight from the suite mod file so the numbers
 * are captured even on a headless dedicated server (where no client asset {@code ResourceManager} exists).
 *
 * <p>The mod file root ({@code IModFile.getSecureJar().getRootPath()}) is walked once. In a dev run it resolves to the
 * exploded resource output; in a shipped jar it is the jar's virtual filesystem. Either way the same
 * {@code Files.walk} logic applies. All access is reflective to avoid a compile dependency on Forge's file-locating
 * types.
 */
final class ResourceDump {

    private ResourceDump() {}

    static String resourceCounts() {
        List<String> lines = new ArrayList<>();
        Path root = suiteRoot();
        if (root == null) {
            lines.add("RES <suite mod file root unavailable>");
            return ParityDump.sortedBlock("resource counts per namespace", lines);
        }
        // (top, namespace) -> file count, where top is "assets" or "data".
        Map<String, Integer> counts = new TreeMap<>();
        for (String top : new String[]{"assets", "data"}) {
            Path base = root.resolve(top);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    Path rel = base.relativize(p);
                    if (rel.getNameCount() >= 1) {
                        String ns = rel.getName(0).toString();
                        counts.merge(top + " " + ns, 1, Integer::sum);
                    }
                });
            } catch (Throwable t) {
                lines.add("RES <error walking " + top + ": " + t + ">");
            }
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            lines.add("RES " + e.getKey() + "=" + e.getValue());
        }
        return ParityDump.sortedBlock("resource counts per namespace", lines);
    }

    static String langCounts() {
        List<String> lines = new ArrayList<>();
        Path root = suiteRoot();
        if (root == null) {
            lines.add("LANG <suite mod file root unavailable>");
            return ParityDump.sortedBlock("lang key counts per locale", lines);
        }
        Path assets = root.resolve("assets");
        if (!Files.isDirectory(assets)) {
            lines.add("LANG <no assets>");
            return ParityDump.sortedBlock("lang key counts per locale", lines);
        }
        try (Stream<Path> walk = Files.walk(assets)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        return s.contains("/lang/") && s.endsWith(".json");
                    })
                    .forEach(p -> {
                        Path rel = assets.relativize(p);
                        String ns = rel.getNameCount() >= 1 ? rel.getName(0).toString() : "?";
                        String locale = p.getFileName().toString().replace(".json", "");
                        int keys = keyCount(p);
                        lines.add("LANG " + ns + " " + locale + " keys=" + keys);
                    });
        } catch (Throwable t) {
            lines.add("LANG <error: " + t + ">");
        }
        return ParityDump.sortedBlock("lang key counts per locale", lines);
    }

    private static int keyCount(Path json) {
        try (Reader r = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(r);
            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                return obj.size();
            }
        } catch (Throwable ignored) {
            // unreadable lang file: report -1 so it stands out without breaking the dump
        }
        return -1;
    }

    /** Root path of the suite mod file, via reflection over Forge's file-locating chain. */
    private static Path suiteRoot() {
        try {
            Object info = ModList.get().getModFileById(ParityDump.SUITE_MODID);
            Object file = ParityDump.call(info, "getFile");
            Object secureJar = ParityDump.call(file, "getSecureJar");
            Object rootPath = ParityDump.call(secureJar, "getRootPath");
            if (rootPath instanceof Path p) {
                return p;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }
}
