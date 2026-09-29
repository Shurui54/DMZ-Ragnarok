package net.shurui.shuruisutilities.devtools.parity;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraftforge.fml.ModList;

/**
 * Loaded mixin configs and the mixins in each.
 *
 * <p>Preferred source: Mixin 0.8.5's own live registry, {@code Mixins.getConfigs()}, which reports which mixin
 * classes bound to which target classes and which declared targets went unhandled. Because our mixins into DMZ are
 * {@code require = 0}, a mixin that quietly stops binding after a refactor produces no build error and no crash; it
 * shows up here as a target moving from applied to unhandled, which is the point.
 *
 * <p>Under ModLauncher the {@code org.spongepowered.asm.mixin.Mixins} class can resolve to a copy with empty static
 * state depending on the classloader, so the live registry is tried across several classloaders. If none yields
 * configs, the dump FALLS BACK to reading the suite's mixin config JSONs from the mod file and listing their declared
 * mixin classes (labelled {@code MODE declared-from-json}). The fallback still catches a config or mixin-class list
 * changing; it just cannot report the applied/unhandled split.
 */
final class MixinDump {

    private MixinDump() {}

    static String dump() {
        List<String> lines = new ArrayList<>();
        Object configSet = liveConfigs(lines);
        if (configSet instanceof Set<?> set && !set.isEmpty()) {
            lines.add("MODE live-registry");
            for (Object config : set) {
                dumpConfig(config, lines);
            }
        } else {
            lines.add("MODE declared-from-json (live registry unavailable or empty)");
            declaredFromJson(lines);
        }
        return ParityDump.sortedBlock("mixin configs + applied mixins", lines);
    }

    /** Try Mixins.getConfigs() across candidate classloaders; return the first non-empty set, else null. */
    private static Object liveConfigs(List<String> diag) {
        List<ClassLoader> loaders = new ArrayList<>();
        loaders.add(Thread.currentThread().getContextClassLoader());
        loaders.add(MixinDump.class.getClassLoader());
        try {
            loaders.add(Class.forName("net.minecraft.server.MinecraftServer").getClassLoader());
        } catch (Throwable ignored) {
        }
        loaders.add(ClassLoader.getSystemClassLoader());
        for (ClassLoader cl : loaders) {
            if (cl == null) {
                continue;
            }
            try {
                Class<?> mixins = Class.forName("org.spongepowered.asm.mixin.Mixins", false, cl);
                Method getConfigs = mixins.getMethod("getConfigs");
                Object set = getConfigs.invoke(null);
                if (set instanceof Set<?> s && !s.isEmpty()) {
                    return set;
                }
            } catch (Throwable ignored) {
                // try next loader
            }
        }
        return null;
    }

    private static void dumpConfig(Object config, List<String> out) {
        String name = String.valueOf(ParityDump.call(config, "getName"));
        out.add("CONFIG " + name);
        Object cfg = ParityDump.call(config, "getConfig");
        if (cfg == null) {
            out.add("MIXIN " + name + " <config detail unavailable>");
            return;
        }
        try {
            TreeMap<String, TreeSet<String>> byMixin = new TreeMap<>();
            Object targetsObj = invoke(cfg, "getTargets");
            if (targetsObj instanceof Set<?> targets) {
                Method getMixinsFor = cfg.getClass().getMethod("getMixinsFor", String.class);
                getMixinsFor.setAccessible(true);
                for (Object target : targets) {
                    String targetName = readable(String.valueOf(target));
                    Object infos = getMixinsFor.invoke(cfg, target);
                    if (infos instanceof List<?> list) {
                        for (Object info : list) {
                            String mixinClass = String.valueOf(ParityDump.call(info, "getClassName"));
                            byMixin.computeIfAbsent(mixinClass, k -> new TreeSet<>()).add(targetName);
                        }
                    }
                }
            }
            for (java.util.Map.Entry<String, TreeSet<String>> e : byMixin.entrySet()) {
                out.add("MIXIN " + name + " " + e.getKey() + " -> " + e.getValue());
            }
            Object classesObj = invoke(cfg, "getClasses");
            if (classesObj instanceof List<?> classes) {
                for (Object mc : classes) {
                    String mixinClass = String.valueOf(mc);
                    if (!byMixin.containsKey(mixinClass)) {
                        out.add("MIXIN " + name + " " + mixinClass + " -> [] (no bound target)");
                    }
                }
            }
            Object unhandledObj = invoke(cfg, "getUnhandledTargets");
            if (unhandledObj instanceof Set<?> unhandled) {
                for (Object u : unhandled) {
                    out.add("UNHANDLED " + name + " " + readable(String.valueOf(u)));
                }
            }
        } catch (Throwable t) {
            out.add("MIXIN " + name + " <error: " + t + ">");
        }
    }

    /** Fallback: walk the suite mod file for its mixin config JSONs and list declared mixin classes. */
    private static void declaredFromJson(List<String> out) {
        Path root = suiteRoot();
        if (root == null) {
            out.add("CONFIG <suite mod file root unavailable>");
            return;
        }
        try (Stream<Path> walk = Files.walk(root, 3)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".mixins.json") || (n.startsWith("mixins.") && n.endsWith(".json"));
                    })
                    .forEach(p -> declaredOne(p, out));
        } catch (Throwable t) {
            out.add("CONFIG <error walking mod file: " + t + ">");
        }
    }

    private static void declaredOne(Path json, List<String> out) {
        String name = json.getFileName().toString();
        out.add("CONFIG " + name);
        try {
            JsonElement el = JsonParser.parseReader(Files.newBufferedReader(json));
            if (!el.isJsonObject()) {
                return;
            }
            JsonObject obj = el.getAsJsonObject();
            String pkg = obj.has("package") ? obj.get("package").getAsString() : "";
            Set<String> classes = new LinkedHashSet<>();
            for (String key : new String[]{"mixins", "client", "server"}) {
                if (obj.has(key) && obj.get(key).isJsonArray()) {
                    JsonArray arr = obj.getAsJsonArray(key);
                    for (JsonElement e : arr) {
                        classes.add(pkg.isEmpty() ? e.getAsString() : pkg + "." + e.getAsString());
                    }
                }
            }
            TreeSet<String> sorted = new TreeSet<>(classes);
            for (String c : sorted) {
                out.add("MIXIN " + name + " " + c + " (declared)");
            }
        } catch (Throwable t) {
            out.add("MIXIN " + name + " <error: " + t + ">");
        }
    }

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
        }
        return null;
    }

    private static Object invoke(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static String readable(String internalName) {
        return internalName.replace('/', '.');
    }
}
