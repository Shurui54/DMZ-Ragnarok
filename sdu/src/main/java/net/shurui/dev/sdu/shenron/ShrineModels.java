package net.shurui.dev.sdu.shenron;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single table of Shenron shapes and skins a shrine may point at, plus the GeckoLib animation each
 * shape is driven by.
 *
 * <p>The animation lives here, not in the config, because a clip only drives bones whose names match the
 * geo it plays over, and the suite carries four unrelated rigs:
 *
 * <ul>
 *   <li>DMZ's own shenron ({@code bone1..bone41}, {@code brazoizquierdo}),</li>
 *   <li>DMZ's porunga ({@code body}, {@code neck}, {@code cuerno1..4}),</li>
 *   <li>our shadow shenron ({@code bb_main}, {@code bone..bone21}),</li>
 *   <li>our HD serpent, shared by Super and Cerulean ({@code supershenron}, {@code segment1..14},
 *       {@code tail1..4}).</li>
 * </ul>
 *
 * <p>Feeding one a clip authored against another is not a load error: GeckoLib drives no bone and the
 * dragon hangs in its bind pose. Deriving the animation from the geo keeps picker and runtime in step.
 *
 * <p>Both maps are keyed on the POST-merge {@code dmz_ragnarok} spelling. A shrine saved before the merge
 * names {@code sdu:...}; callers normalise through {@code LegacyIds} first.
 */
public final class ShrineModels {

    /** DMZ's own shenron idle. The fallback for the bundled SDU geo and for any path not in {@link #ANIMATIONS}. */
    public static final String DMZ_SHENRON_ANIM = "dragonminez:animations/entity/dragon/shenron.animation.json";

    private static final String DMZ_SHENRON_GEO = "dragonminez:geo/entity/dragon/shenron.geo.json";
    private static final String DMZ_PORUNGA_GEO = "dragonminez:geo/entity/dragon/porunga.geo.json";
    private static final String SHADOW_SHENRON_GEO = "dmz_ragnarok:geo/entity/shadow_shenron.geo.json";
    private static final String SUPER_SHENRON_GEO = "dmz_ragnarok:geo/entity/dragon/shenron_super.geo.json";
    private static final String CERULEAN_SHENRON_GEO = "dmz_ragnarok:geo/entity/dragon/shenron_cerulean.geo.json";

    /** Every geo a shrine may display, in picker order. */
    private static final List<String> GEOS = List.of(
            ShrineColorConfig.DEFAULT_GEO,
            DMZ_SHENRON_GEO,
            DMZ_PORUNGA_GEO,
            SHADOW_SHENRON_GEO,
            SUPER_SHENRON_GEO,
            CERULEAN_SHENRON_GEO);

    /**
     * Every skin a shrine may paint, in picker order. A skin is NOT tied to a geo: the four flat DMZ-rig
     * colours (apophis red, green, blue, delta) share one UV map, while Super and Cerulean are 512px maps
     * for the HD serpent. Pairing a skin with a rig it was not drawn for renders (looks wrong), so the list
     * is left flat rather than filtered per geo.
     */
    private static final List<String> TEXTURES = List.of(
            ShrineColorConfig.DEFAULT_TEXTURE,
            "dragonminez:textures/entity/dragon/shenron.png",
            "dragonminez:textures/entity/dragon/porunga.png",
            "dmz_ragnarok:textures/entity/shadow_shenron.png",
            "dmz_ragnarok:textures/entity/dragon/shenron_apophis.png",
            "dmz_ragnarok:textures/entity/dragon/shenron_green.png",
            "dmz_ragnarok:textures/entity/dragon/shenron_blue.png",
            "dmz_ragnarok:textures/entity/dragon/shenron_delta.png",
            "dmz_ragnarok:textures/entity/dragon/shenron_super.png",
            "dmz_ragnarok:textures/entity/dragon/shenron_cerulean.png");

    /** geo -> the animation file whose bone names that geo answers to. Absent means DMZ's shenron idle. */
    private static final Map<String, String> ANIMATIONS = buildAnimations();

    private ShrineModels() {
    }

    private static Map<String, String> buildAnimations() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(DMZ_PORUNGA_GEO, "dragonminez:animations/entity/dragon/porunga.animation.json");
        m.put(SHADOW_SHENRON_GEO, "dmz_ragnarok:animations/entity/shadow_shenron.animation.json");
        m.put(SUPER_SHENRON_GEO, "dmz_ragnarok:animations/entity/dragon/shenron_hd.animation.json");
        m.put(CERULEAN_SHENRON_GEO, "dmz_ragnarok:animations/entity/dragon/shenron_hd.animation.json");
        return Collections.unmodifiableMap(m);
    }

    /** Geo choices for the shrine editor dropdown, in picker order. */
    public static List<String> geoOptions() {
        return GEOS;
    }

    /** Texture choices for the shrine editor dropdown, in picker order. */
    public static List<String> textureOptions() {
        return TEXTURES;
    }

    /**
     * The animation file to play over {@code geo}. Falls back to DMZ's shenron idle for the bundled SDU geo
     * and for anything an operator typed by hand.
     */
    public static String animationFor(String geo) {
        if (geo == null) {
            return DMZ_SHENRON_ANIM;
        }
        return ANIMATIONS.getOrDefault(geo.trim(), DMZ_SHENRON_ANIM);
    }
}
