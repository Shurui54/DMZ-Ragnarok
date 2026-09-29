package net.shurui.shuruisutilities.devtools.parity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.ModFileScanData;

/**
 * Every {@code @Mod.EventBusSubscriber} in the suite: the class, which bus it is on (FORGE/MOD), its explicit
 * {@code modid} (or {@code <none>}, the case the guardrail flags), the {@code Dist} it is restricted to, and the
 * number of {@code @SubscribeEvent} methods declared in it.
 *
 * <p>Read from Forge's mod-file scan data, the same source {@code ModuleLauncher} uses to find SU modules, so it
 * reflects what Forge will actually register. A bare subscriber whose {@code modid} defaults per mod container is one
 * of the merge's silent-failure traps; recording the modid here makes a regression obvious.
 */
final class EventBusDump {

    private EventBusDump() {}

    private static final String EBS = "net.minecraftforge.fml.common.Mod$EventBusSubscriber";
    private static final String SUB = "net.minecraftforge.eventbus.api.SubscribeEvent";

    static String dump() {
        // First pass: count @SubscribeEvent methods per declaring class.
        Map<String, Integer> subCounts = new TreeMap<>();
        try {
            for (ModFileScanData data : ModList.get().getAllScanData()) {
                for (ModFileScanData.AnnotationData ad : data.getAnnotations()) {
                    if (SUB.equals(ad.annotationType().getClassName())
                            && ad.targetType() == java.lang.annotation.ElementType.METHOD) {
                        subCounts.merge(ad.clazz().getClassName(), 1, Integer::sum);
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall through: counts stay best-effort
        }

        List<String> lines = new ArrayList<>();
        try {
            for (ModFileScanData data : ModList.get().getAllScanData()) {
                for (ModFileScanData.AnnotationData ad : data.getAnnotations()) {
                    if (!EBS.equals(ad.annotationType().getClassName())) {
                        continue;
                    }
                    String clazz = ad.clazz().getClassName();
                    if (!clazz.startsWith("net.shurui")) {
                        continue;
                    }
                    Map<String, Object> values = ad.annotationData();
                    String bus = enumName(values.get("bus"), "FORGE");
                    String modid = values.containsKey("modid") ? String.valueOf(values.get("modid")) : "<none>";
                    String dist = distList(values.get("value"));
                    int subs = subCounts.getOrDefault(clazz, 0);
                    lines.add("EBS " + clazz + " | bus=" + bus + " | modid=" + modid
                            + " | dist=" + dist + " | subscribers=" + subs);
                }
            }
        } catch (Throwable t) {
            lines.add("EBS <error enumerating scan data: " + t + ">");
        }
        return ParityDump.sortedBlock("@Mod.EventBusSubscriber classes", lines);
    }

    /** ASM stores an enum annotation value as an EnumHolder whose value() is the constant name. */
    private static String enumName(Object holder, String fallback) {
        if (holder == null) {
            return fallback;
        }
        Object v = ParityDump.call(holder, "getValue");
        if (v == null) {
            v = ParityDump.field(holder, "value");
        }
        return v == null ? String.valueOf(holder) : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static String distList(Object value) {
        if (value == null) {
            return "[CLIENT,DEDICATED_SERVER]"; // Forge default: both sides
        }
        if (value instanceof List<?> list) {
            List<String> names = new ArrayList<>();
            for (Object o : list) {
                names.add(enumName(o, String.valueOf(o)));
            }
            java.util.Collections.sort(names);
            return names.toString();
        }
        return String.valueOf(value);
    }
}
