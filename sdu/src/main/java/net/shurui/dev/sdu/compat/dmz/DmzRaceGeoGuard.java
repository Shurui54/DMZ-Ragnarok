package net.shurui.dev.sdu.compat.dmz;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.DmzNpc;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;

/**
 * Last line of defence between DragonMineZ's race-model resolution and GeckoLib's baked-model map.
 *
 * <h2>Why this has to exist</h2>
 * {@code GeoModel.getBakedModel} does not degrade. It looks the location up in
 * {@link GeckoLibCache#getBakedModels()} and, on a miss, THROWS {@code GeckoLibException} out of the render thread,
 * which takes the client down. So a geo location is only safe to hand over if GeckoLib actually baked it.
 *
 * <p>"Baked" is a narrower question than "the file exists", and the two can disagree, which is what makes this a
 * crash rather than a wrong-looking model. GeckoLib fills its map ONCE per resource reload, by LISTING
 * {@code geo/} across the resource stack ({@code GeckoLibCache.loadModels}). DragonMineZ, for a race whose
 * {@code customModel} it does not recognise, instead builds {@code dragonminez:geo/entity/races/<model><variant>.geo.json}
 * and asks the resource manager whether that one file READS ({@code DMZPlayerModel.fileExists}, itself memoised in a
 * static cache DMZ never clears). Anything a pack can read but never listed therefore passes DMZ's test and dies in
 * GeckoLib's.
 *
 * <p>That is exactly the shape of the rgnpc race aliases: the streamed model pack answers
 * {@code .../races/<entry>.geo.json} and, because DragonMineZ appends the gender to a gendered race's model name, it
 * also answers {@code <entry>_male} / {@code <entry>_female} / {@code <entry>_slim} out of the same entry. Only the
 * bare name is enumerated, so on a gendered race the variant name reads fine and was never baked.
 *
 * <h2>What it does</h2>
 * Nothing at all in the normal case (one map lookup, the location is baked, it is returned untouched). On a miss it
 * degrades, in order: the same name without a variant suffix (identical geometry, and for the rgnpc aliases it is
 * literally the same bytes), then DragonMineZ's own {@code human.geo.json}, which its own fallbacks already use.
 * If even that is not baked, the wanted location is handed back unchanged and DMZ behaves exactly as it did before
 * this class existed: there is nothing better to offer, and swallowing the frame is not ours to decide.
 *
 * <p>Deliberately generic: it knows about locations and suffixes, not about rgnpc. A third-party race pack removed
 * mid-session gets the same protection, and this class stays inside sdu with no reach into the other trees.
 */
public final class DmzRaceGeoGuard {

    private DmzRaceGeoGuard() {
    }

    /** DragonMineZ's own base race geo, the body its own resolution falls back to. */
    private static final ResourceLocation HUMAN =
            new ResourceLocation("dragonminez", "geo/entity/races/human.geo.json");

    private static final String GEO_SUFFIX = ".geo.json";

    /** Suffixes DragonMineZ appends to a custom model name. Kept in step with RgNpcRaceModels' own list. */
    private static final String[] VARIANT_SUFFIXES = {"_male", "_female", "_slim"};

    /** One line per distinct substitution; this runs from the render loop. */
    private static final Set<String> WARNED = Collections.synchronizedSet(new HashSet<>());

    /**
     * {@code wanted} when GeckoLib can bake it, otherwise the closest thing it can. Never throws, never returns null
     * for a non-null argument.
     */
    public static ResourceLocation bakedOrDefault(ResourceLocation wanted) {
        if (wanted == null) {
            return null;
        }
        try {
            Map<ResourceLocation, BakedGeoModel> baked = GeckoLibCache.getBakedModels();
            // Empty means models have not been loaded at all (no reload has run yet). Substituting cannot help:
            // the replacement would be just as absent, so leave the caller's choice alone.
            if (baked.isEmpty() || baked.containsKey(wanted)) {
                return wanted;
            }
            ResourceLocation bare = withoutVariant(wanted);
            if (bare != null && baked.containsKey(bare)) {
                warnOnce(wanted, bare);
                return bare;
            }
            if (baked.containsKey(HUMAN)) {
                warnOnce(wanted, HUMAN);
                return HUMAN;
            }
            return wanted;
        } catch (Throwable t) {
            // GeckoLib not initialised, or a reshaped cache: never let the guard itself be the failure.
            return wanted;
        }
    }

    /** {@code .../zeno_male.geo.json} -&gt; {@code .../zeno.geo.json}, or null when there is no variant to strip. */
    private static ResourceLocation withoutVariant(ResourceLocation loc) {
        String path = loc.getPath();
        if (!path.endsWith(GEO_SUFFIX)) {
            return null;
        }
        String base = path.substring(0, path.length() - GEO_SUFFIX.length());
        for (String suffix : VARIANT_SUFFIXES) {
            if (base.length() > suffix.length() && base.endsWith(suffix)) {
                return ResourceLocation.tryParse(loc.getNamespace() + ":"
                        + base.substring(0, base.length() - suffix.length()) + GEO_SUFFIX);
            }
        }
        return null;
    }

    private static void warnOnce(ResourceLocation wanted, ResourceLocation used) {
        if (WARNED.add(wanted.toString())) {
            DmzNpc.LOGGER.warn("[{}] {} was never baked by GeckoLib (it reads, but nothing listed it); drawing {} "
                    + "instead of crashing the render thread.", DmzNpc.MODID, wanted, used);
        }
    }
}
