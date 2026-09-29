package net.shurui.shuruisutilities.devtools.parity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.ModFileScanData;

/**
 * The module axes the suite reasons about, so a change on any of them is caught:
 * <ul>
 *   <li>SU modules: how many {@code @SUModule} annotations Forge found, and each module's loadable state after
 *       teardown ({@code ModuleLauncher.getModuleMap()});</li>
 *   <li>the key tier this process resolved: the raw gate booleans plus a derived label.</li>
 * </ul>
 * The operator per-feature switchboard was removed in batch M, so there is no switchboard axis any more. All
 * cross-tree access is reflective, so this file adds no hard dependency edge and simply omits a line if a class
 * is absent.
 */
final class ModuleDump {

    private ModuleDump() {}

    static String dump(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        suModules(lines);
        keyTier(lines);
        return ParityDump.sortedBlock("SU modules, key tier", lines);
    }

    private static void suModules(List<String> out) {
        // "Found N SUModule annotations": count and list the annotated classes from scan data.
        try {
            String suModule = "net.shurui.shuruisutilities.core.moduleLauncher.SUModule";
            int count = 0;
            List<String> members = new ArrayList<>();
            for (ModFileScanData data : ModList.get().getAllScanData()) {
                for (ModFileScanData.AnnotationData ad : data.getAnnotations()) {
                    if (suModule.equals(ad.annotationType().getClassName())) {
                        count++;
                        members.add(ad.clazz().getClassName());
                    }
                }
            }
            out.add("SUMODULE-ANNOTATIONS count=" + count);
            java.util.Collections.sort(members);
            for (String m : members) {
                out.add("SUMODULE-ANNOTATED " + m);
            }
        } catch (Throwable t) {
            out.add("SUMODULE-ANNOTATIONS <error: " + t + ">");
        }

        // Loadable state per module after teardown, from the launcher's live container map.
        try {
            Class<?> launcher = Class.forName("net.shurui.shuruisutilities.core.moduleLauncher.ModuleLauncher");
            Method getMap = launcher.getMethod("getModuleMap");
            Object map = getMap.invoke(null);
            if (map instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : new TreeMap<>((Map<String, Object>) m).entrySet()) {
                    Object container = e.getValue();
                    Object loadable = ParityDump.field(container, "isLoadable");
                    out.add("SUMODULE-ACTIVE " + e.getKey() + " loadable=" + loadable);
                }
            }
        } catch (Throwable t) {
            out.add("SUMODULE-ACTIVE <error: " + t + ">");
        }
    }

    private static void keyTier(List<String> out) {
        boolean suFull = staticBool("net.shurui.shuruisutilities.KeyGate", "unlocked");
        boolean suKey = staticBool("net.shurui.shuruisutilities.KeyGate", "present");
        boolean sduKey = staticBool("net.shurui.dev.sdu.KeyGate", "present");
        boolean publicTier = staticBool("net.shurui.shuruisutilities.core.config.PublicContent", "publicTier");
        boolean restricted = staticBool("net.shurui.shuruisutilities.core.config.PublicContent", "restricted");

        out.add("KEY su.unlocked=" + suFull);
        out.add("KEY su.present=" + suKey);
        out.add("KEY sdu.present=" + sduKey);
        out.add("KEY public.publicTier=" + publicTier);
        out.add("KEY public.restricted=" + restricted);
        // What a joining client would be told (DmzNet.clientKeyPresent / clientFeatureIds): the key flag and the
        // private feature ids the server can prove the real key installed. A fake key jar must leave both empty.
        out.add("KEY client.key=" + staticBool("net.shurui.dev.sdu.network.DmzNet", "clientKeyPresent"));
        out.add("KEY client.features=" + clientFeatures());

        String label;
        if (suFull) {
            label = "FULL";
        } else if (sduKey || suKey) {
            label = "KEYED";
        } else {
            label = "PUBLIC";
        }
        out.add("KEY resolved.tier=" + label);
    }

    private static String clientFeatures() {
        try {
            return String.valueOf(Class.forName("net.shurui.dev.sdu.network.DmzNet").getMethod("clientFeatureIds")
                    .invoke(null));
        } catch (Throwable t) {
            return "<error: " + t + ">";
        }
    }

    private static boolean staticBool(String className, String method) {
        try {
            Class<?> c = Class.forName(className);
            Method m = c.getMethod(method);
            Object v = m.invoke(null);
            return v instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }
}
