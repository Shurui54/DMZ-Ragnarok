package net.shurui.dev.sdu.compat.cnpc;

import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.DmzNpc;
import software.bernie.geckolib.cache.GeckoLibCache;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side index of DMZ's GeckoLib assets for the {@code DMZ NPC} tab's model/animation pickers (feature
 * 4.2). DMZ ships models under {@code assets/dragonminez/geo/**} and clips under
 * {@code assets/dragonminez/animations/**}; GeckoLib bakes every {@code .geo.json}/{@code .animation.json} into
 * {@link GeckoLibCache} at resource load, so we read the cache and filter to DMZ's namespace: no copying, and
 * resource-pack overrides/additions are picked up automatically.
 *
 * <p>Client-only (GeckoLibCache is a client cache); callers gate on the Gecko addon being present.</p>
 */
public final class DmzAssetRegistry {

    /** DMZ's asset namespace. Kept here so a rename is a one-line change. */
    public static final String DMZ_NAMESPACE = "dragonminez";
    /** SDU's own namespace, holds {@code sdu_slim}/{@code sdu_wide} and future SDU-authored models. */
    public static final String SDU_NAMESPACE = DmzNpc.MODID;
    /**
     * SU's namespace, carrying the ragnarok character models (258 under {@code geo/entity/ragnarok/}) plus the
     * citizen model. A STRING, not compiled against SU: the dependency runs the other way (SU depends on sdu,
     * never the reverse), so sdu cannot import it. If SU is absent its namespace just contributes no entries,
     * because the filter reads whatever GeckoLib actually baked.
     */
    // Post-merge these SU-origin assets live under dmz_ragnarok (same as SDU_NAMESPACE); kept separate for
    // readability of the "ours" filter below.
    public static final String SU_NAMESPACE = "dmz_ragnarok";

    private DmzAssetRegistry() {
    }

    /** Resource-location strings of every DMZ, SDU and SU geo model, sorted (e.g. {@code sdu:geo/entity/sdu_slim.geo.json}). */
    public static List<String> geoModels() {
        return collect(GeckoLibCache.getBakedModels().keySet(), ".geo.json");
    }

    /** Resource-location strings of every DMZ, SDU and SU animation file, sorted. */
    public static List<String> animationFiles() {
        return collect(GeckoLibCache.getBakedAnimations().keySet(), ".animation.json");
    }

    private static List<String> collect(Iterable<ResourceLocation> keys, String suffix) {
        List<String> out = new ArrayList<>();
        try {
            for (ResourceLocation rl : keys) {
                String ns = rl.getNamespace();
                boolean ours = DMZ_NAMESPACE.equals(ns) || SDU_NAMESPACE.equals(ns) || SU_NAMESPACE.equals(ns);
                if (ours && rl.getPath().endsWith(suffix)) {
                    out.add(rl.toString());
                }
            }
            out.sort(String::compareTo);
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Failed to read GeckoLib cache for DMZ assets: {}", DmzNpc.MODID, t.toString());
        }
        return out;
    }
}
