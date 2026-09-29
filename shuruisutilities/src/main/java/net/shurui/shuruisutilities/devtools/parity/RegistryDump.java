package net.shurui.shuruisutilities.devtools.parity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/**
 * Registry entry ids per registry.
 *
 * <p>Two kinds of line. {@code REG <registry> <id>} lists every entry whose namespace belongs to the suite (see
 * {@link ParityDump#SUITE_NAMESPACES}), which is the set that must not move without an explicit, allow-listed
 * migration. {@code NSALL <registry> <namespace>=<count>} records the per-namespace count for EVERY namespace in
 * EVERY registry, so a namespace appearing or disappearing (or an entry count changing) is visible even outside the
 * suite set. Static registries come from {@link BuiltInRegistries}; datapack (dynamic) registries come from the
 * server's {@code registryAccess()} and so are server-only.
 */
final class RegistryDump {

    private RegistryDump() {}

    static String dump(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        collectStatic(lines);
        collectDynamic(server, lines);
        return ParityDump.sortedBlock("registry entries (static + dynamic)", lines);
    }

    static String dumpStaticOnly() {
        List<String> lines = new ArrayList<>();
        collectStatic(lines);
        return ParityDump.sortedBlock("registry entries (static)", lines);
    }

    private static void collectStatic(List<String> out) {
        try {
            for (Map.Entry<? extends net.minecraft.resources.ResourceKey<?>, ?> e : BuiltInRegistries.REGISTRY.entrySet()) {
                String registryId = e.getKey().location().toString();
                Object value = e.getValue();
                if (value instanceof Registry<?> reg) {
                    dumpOne(registryId, reg, out);
                }
            }
        } catch (Throwable t) {
            out.add("REG <error enumerating BuiltInRegistries: " + t + ">");
        }
    }

    private static void collectDynamic(MinecraftServer server, List<String> out) {
        try {
            server.registryAccess().registries().forEach(entry -> {
                try {
                    String registryId = entry.key().location().toString();
                    dumpOne(registryId, entry.value(), out);
                } catch (Throwable inner) {
                    out.add("REG <error on dynamic registry: " + inner + ">");
                }
            });
        } catch (Throwable t) {
            out.add("REG <error enumerating dynamic registries: " + t + ">");
        }
    }

    private static void dumpOne(String registryId, Registry<?> reg, List<String> out) {
        Map<String, Integer> perNs = new TreeMap<>();
        for (ResourceLocation id : reg.keySet()) {
            perNs.merge(id.getNamespace(), 1, Integer::sum);
            if (ParityDump.isSuiteNamespace(id.getNamespace())) {
                out.add("REG " + registryId + " " + id);
            }
        }
        for (Map.Entry<String, Integer> e : perNs.entrySet()) {
            out.add("NSALL " + registryId + " " + e.getKey() + "=" + e.getValue());
        }
    }
}
