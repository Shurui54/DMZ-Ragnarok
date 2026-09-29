package net.shurui.dev.sdu;

import java.util.LinkedHashMap;
import java.util.Map;

import org.objectweb.asm.Type;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.forgespi.language.ModFileScanData;

/**
 * Dev-only split self-check. At {@link FMLLoadCompleteEvent} it logs, per DMZ Ragnarok container id, how
 * many {@code @Mod.EventBusSubscriber} classes are declared for it and whether the container is loaded.
 *
 * <p>The point is to compare a fat-jar boot against a modular boot: the per-container subscriber counts
 * must be IDENTICAL (same sources), and the "loaded" flags show which containers a given install actually
 * has. It reads the static scan data, so it reports what Forge WILL bind, and the build-time checker
 * ({@code tools/check-eventbus-modids.py}) already guarantees each count lands on the right container.
 *
 * <p>Guarded on {@code !FMLEnvironment.production}, so it costs nothing in a shipped reobf jar. Registered
 * once from core's mod bus (see {@link DmzNpc}). Any failure here is swallowed: diagnostics must never
 * affect loading.
 */
public final class SplitDiagnostics {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Type EBS = Type.getType(Mod.EventBusSubscriber.class);

    /** Core plus the four modules, in load order. */
    private static final String[] CONTAINERS = {
            "dmz_ragnarok", "dmz_ragnarok_dungeons", "dmz_ragnarok_raids", "dmz_ragnarok_tournaments",
            "dmz_ragnarok_space"
    };

    private SplitDiagnostics() {
    }

    public static void onLoadComplete(final FMLLoadCompleteEvent event) {
        if (FMLEnvironment.production) {
            return;
        }
        try {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String id : CONTAINERS) {
                counts.put(id, 0);
            }
            for (ModFileScanData scan : ModList.get().getAllScanData()) {
                for (ModFileScanData.AnnotationData ad : scan.getAnnotations()) {
                    if (!EBS.equals(ad.annotationType())) {
                        continue;
                    }
                    Object modid = ad.annotationData().get("modid");
                    if (modid instanceof String s && counts.containsKey(s)) {
                        counts.merge(s, 1, Integer::sum);
                    }
                }
            }
            LOGGER.info("[DMZ Ragnarok split] EventBusSubscriber classes per container (must match across fat/modular boots):");
            for (String id : CONTAINERS) {
                LOGGER.info("[DMZ Ragnarok split]   {} loaded={} subscribers={}",
                        id, ModList.get().isLoaded(id), counts.get(id));
            }
        } catch (Throwable t) {
            LOGGER.warn("[DMZ Ragnarok split] diagnostics failed (ignored): {}", t.toString());
        }
    }
}
