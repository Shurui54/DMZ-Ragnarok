package net.shurui.shuruisutilities.devtools.parity;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Dev-only parity dump. Writes stable, sorted, diffable text files describing the loaded state of the suite
 * (channels, commands, registries, event-bus subscribers, mixins, SU modules, persisted data layout, and
 * resource/lang counts). It is the BASELINE recorder for the 2.0 re-architecture: capture it on the pre-split
 * build, capture it again at every stage, and any unintended difference is a regression.
 *
 * <h2>Completely inert in a shipped jar</h2>
 * {@link #enabled()} is true only when BOTH {@link FMLEnvironment#production} is false (so never in a reobfuscated
 * production jar) AND the system property {@code dmzr.paritydump} names an output directory. A shipped server never
 * sets that property and is never in a dev environment, so not a line of this runs there. It registers no registry
 * objects, opens no ports, and starts no threads.
 *
 * <h2>Determinism</h2>
 * Every collection is sorted before it is written, no timestamps or identity hashes are emitted, and each section is
 * produced independently so a failure in one still writes a deterministic error marker rather than aborting the run.
 * Two dumps of the same build must be byte-identical; that is what makes a diff meaningful.
 *
 * <p>Nothing here is on any hot path. It is called once from {@link ParityDumpEvents} on
 * {@code ServerStartedEvent} and once from {@link ParityDumpClientEvents} at the title screen.
 */
public final class ParityDump {

    private ParityDump() {}

    /** System property naming the output directory. Absent = disabled. */
    public static final String OUT_PROPERTY = "dmzr.paritydump";

    /**
     * Environment-variable fallback for the output directory, so the harness can enable the dump on the unified
     * dev run without editing the (separately owned) root run configuration: a forked dev-run JVM inherits the
     * environment of the Gradle invocation.
     */
    public static final String OUT_ENV = "DMZR_PARITYDUMP";

    /** The suite mod id (single merged container). */
    public static final String SUITE_MODID = "dmz_ragnarok";

    /**
     * Namespaces the suite registers into. Post-merge everything registers under {@code dmz_ragnarok}, but the
     * original owning namespaces are listed too so the dump keeps working if a future stage moves ids back.
     */
    public static final Set<String> SUITE_NAMESPACES = Collections.unmodifiableSet(new TreeSet<>(List.of(
            "dmz_ragnarok", "shuruisutilities", "shuruis_dmz_dungeons", "shuruis_raid_bosses",
            "shuruis_dmz_tournaments", "sdu")));

    /** The suite's SimpleChannel holder classes; each is scanned for a static SimpleChannel field. */
    private static final List<String> CHANNEL_HOLDERS = List.of(
            "net.shurui.dev.sdu.network.DmzNet",
            "net.shurui.shuruisutilities.commons.network.NetworkUtils",
            "net.shurui.dev.shuruis_dmz_dungeons.network.SddNet",
            "net.shurui.dev.shuruis_raid_bosses.network.RaidNet",
            "net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet");

    private static final AtomicBoolean CLIENT_DONE = new AtomicBoolean(false);
    private static final AtomicBoolean SERVER_DONE = new AtomicBoolean(false);

    /** True only in a dev environment with the output directory configured (system property or env var). */
    public static boolean enabled() {
        try {
            return !FMLEnvironment.production && rawOut() != null && !rawOut().isBlank();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String rawOut() {
        String p = System.getProperty(OUT_PROPERTY);
        if (p != null && !p.isBlank()) {
            return p;
        }
        return System.getenv(OUT_ENV);
    }

    private static Path outDir() {
        return Paths.get(rawOut());
    }

    /**
     * Harness opt-in: when {@code DMZR_PARITY_STOP} is truthy the server halts itself right after the dump, so a
     * headless parity run exits on its own without any stdin plumbing. A manual dev dump (property set, this env
     * var unset) leaves the server running.
     */
    public static boolean stopAfterDump() {
        String v = System.getenv("DMZR_PARITY_STOP");
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes"));
    }

    /** Dedicated/integrated server dump. Runs once per server start. */
    public static void dumpServer(MinecraftServer server) {
        if (!enabled() || server == null) {
            return;
        }
        if (!SERVER_DONE.compareAndSet(false, true)) {
            return;
        }
        String prefix = "server";
        write(prefix, "channels", () -> ChannelDump.dump());
        write(prefix, "commands", () -> CommandDump.dump(server));
        write(prefix, "registries", () -> RegistryDump.dump(server));
        write(prefix, "eventbus", () -> EventBusDump.dump());
        write(prefix, "mixins", () -> MixinDump.dump());
        write(prefix, "modules", () -> ModuleDump.dump(server));
        write(prefix, "data-layout", () -> DataLayoutDump.dump(server));
        write(prefix, "resources", () -> ResourceDump.resourceCounts());
        write(prefix, "lang", () -> ResourceDump.langCounts());
        log("wrote server parity dump to " + outDir().toAbsolutePath());
    }

    /** Client dump at the title screen. Runs once per launch. */
    public static void dumpClient() {
        if (!enabled()) {
            return;
        }
        if (!CLIENT_DONE.compareAndSet(false, true)) {
            return;
        }
        String prefix = "client";
        write(prefix, "channels", () -> ChannelDump.dump());
        write(prefix, "registries", () -> RegistryDump.dumpStaticOnly());
        write(prefix, "eventbus", () -> EventBusDump.dump());
        write(prefix, "mixins", () -> MixinDump.dump());
        write(prefix, "resources", () -> ResourceDump.resourceCounts());
        write(prefix, "lang", () -> ResourceDump.langCounts());
        log("wrote client parity dump to " + outDir().toAbsolutePath());
    }

    // ---- shared IO / formatting helpers (package-visible for the section classes) ----

    /** Run one section defensively and write {@code <out>/<prefix>-<name>.txt}; a failure writes a stable marker. */
    private static void write(String prefix, String name, Supplier<String> body) {
        String content;
        try {
            content = body.get();
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            content = "# SECTION FAILED\n" + sw;
        }
        try {
            Path dir = outDir();
            Files.createDirectories(dir);
            Path file = dir.resolve(prefix + "-" + name + ".txt");
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log("could not write " + prefix + "-" + name + ": " + e);
        }
    }

    static void log(String msg) {
        System.out.println("[parity] " + msg);
    }

    /** Header line for a section file, so a stray file is self-describing. */
    static String header(String title) {
        return "# parity: " + title + "\n";
    }

    /** Sort lines and join with newlines, trailing newline included. Empty list yields just a marker. */
    static String sortedBlock(String title, Collection<String> lines) {
        List<String> sorted = new ArrayList<>(lines);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder(header(title));
        sb.append("# count=").append(sorted.size()).append('\n');
        for (String l : sorted) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /** Reflectively read a declared (possibly private) static field's value by type, else null. */
    static Object firstStaticFieldOfType(Class<?> owner, Class<?> fieldType) {
        for (Field f : owner.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && fieldType.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    return f.get(null);
                } catch (Throwable ignored) {
                    // try next
                }
            }
        }
        return null;
    }

    static List<String> channelHolders() {
        return CHANNEL_HOLDERS;
    }

    /** Best-effort invoke of a public no-arg method by name, returning null on any failure. */
    static Object call(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Best-effort read of a declared field (any access) by name, returning null on any failure. */
    static Object field(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    static boolean isSuiteNamespace(String ns) {
        return SUITE_NAMESPACES.contains(ns);
    }

    static <K, V> Map<K, V> tree() {
        return new TreeMap<>();
    }

    static Map<String, Integer> counts() {
        return new LinkedHashMap<>();
    }
}
