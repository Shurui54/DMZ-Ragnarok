package net.shurui.shuruisutilities.ragnarok;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * GENERATED, do not edit by hand. Per-geo collision box sizes for {@link RgNpcEntity}, baked offline
 * from the shipped geo files under assets/shuruisutilities/geo/entity/ragnarok/*.geo.json.
 *
 * <p>Why baked and not parsed at runtime: the geo files live under assets/, a CLIENT resource tree,
 * but the SERVER must know the box to build the collision AABB and has no client resource manager.
 * So sizes are computed offline and stored here, matching how {@link RgNpcModels} is itself a
 * generated static table. Keyed by GEO id (the value of {@link RgNpcModels#geoId(String)}), NOT by
 * entry id. In the August 2026 pack every entry id equals its geo id, so the map has one row per
 * installed model (259 rows).
 *
 * <p>Numbers are sanitised: height is max(0,yMax)/16 (some rigs are not ground-anchored, so a raw span
 * would start below the feet), floored at 0.5 and capped at 6.0 so a tall rig does not clip ceilings;
 * width is min(rawWidth, height*0.6) so a T-posed arm span does not balloon the box, floored at 0.4
 * and capped at 2.5. Values are in blocks.
 */
public final class RgNpcModelSizes {

    private RgNpcModelSizes() {
    }

    // geo id -> {width, height} in blocks.
    private static final Map<String, float[]> SIZES = build();

    /**
     * The baked {width, height} for a geo id, or null when the geo is not in the table. Callers must
     * treat null as "fall back to the registered base size"; this never throws.
     */
    public static float[] sizeFor(String geoId) {
        if (geoId == null) {
            return null;
        }
        return SIZES.get(geoId);
    }

    /** Unmodifiable view of the whole table, for validation / tooling. */
    public static Map<String, float[]> all() {
        return Collections.unmodifiableMap(SIZES);
    }

    private static Map<String, float[]> build() {
        Map<String, float[]> m = new HashMap<>(512);
        m.put("bdkssj4", new float[]{1.5646f, 2.6076f});
        m.put("bdkssjb3ninjin", new float[]{1.7191f, 2.8652f});
        m.put("tournamentannouncer", new float[]{1.0f, 2.0063f});
        m.put("chiaotzu1", new float[]{1.0f, 2.0f});
        m.put("gokukid1", new float[]{1.7191f, 2.8652f});
        m.put("gokukid2", new float[]{1.7191f, 2.8652f});
        m.put("krillinkid1", new float[]{1.0f, 2.0f});
        m.put("krillinkid2", new float[]{1.0f, 2.0f});
        m.put("roshi", new float[]{1.0f, 1.75f});
        m.put("roshibuff", new float[]{1.0f, 2.0f});
        m.put("yamcha1", new float[]{1.1004f, 2.2911f});
        m.put("android8", new float[]{1.3725f, 2.2875f});
        m.put("bacterian", new float[]{1.4775f, 2.4625f});
        m.put("buyon", new float[]{1.41f, 2.35f});
        m.put("chiaotzu", new float[]{0.875f, 1.65f});
        m.put("colonelsilver", new float[]{1.0f, 2.1875f});
        m.put("colonelviolet", new float[]{0.775f, 1.875f});
        m.put("commanderred", new float[]{0.9937f, 1.6562f});
        m.put("cymbal", new float[]{1.2955f, 2.1592f});
        m.put("devil", new float[]{1.5037f, 2.5063f});
        m.put("drum", new float[]{1.25f, 2.125f});
        m.put("generalblue", new float[]{1.0f, 2.0f});
        m.put("generalwhite", new float[]{1.0f, 2.0312f});
        m.put("giran", new float[]{1.3538f, 2.2563f});
        m.put("grandpagohan", new float[]{0.95f, 1.9812f});
        m.put("jackiechun", new float[]{0.95f, 1.75f});
        m.put("jackiechun2", new float[]{1.3275f, 2.2125f});
        m.put("jackiechun3", new float[]{0.8625f, 1.75f});
        m.put("kingchappa", new float[]{1.0f, 2.075f});
        m.put("kingpiccolo", new float[]{1.125f, 2.525f});
        m.put("kingpiccolo2", new float[]{1.225f, 2.475f});
        m.put("launch", new float[]{0.9812f, 2.0312f});
        m.put("mai", new float[]{1.0f, 2.0f});
        m.put("maimecha", new float[]{1.635f, 2.725f});
        m.put("majormetallitron", new float[]{1.4925f, 2.4875f});
        m.put("manwolf", new float[]{1.1625f, 1.9375f});
        m.put("mastershen", new float[]{0.95f, 1.875f});
        m.put("nam", new float[]{1.0f, 2.0f});
        m.put("ninjamurasaki", new float[]{1.3125f, 2.3438f});
        m.put("piano", new float[]{0.8175f, 1.3625f});
        m.put("pilaf", new float[]{0.8512f, 1.4187f});
        m.put("pilafmecha", new float[]{1.1438f, 1.9062f});
        m.put("pilafmechacombined", new float[]{2.425f, 5.1063f});
        m.put("puar", new float[]{0.7312f, 1.2188f});
        m.put("redribbonsoldierbazooka", new float[]{1.2f, 2.0f});
        m.put("redribbonsoldiergunner", new float[]{1.2f, 2.0f});
        m.put("shu", new float[]{0.9788f, 1.6313f});
        m.put("shumecha", new float[]{1.7125f, 5.4375f});
        m.put("upa", new float[]{0.875f, 1.8125f});
        m.put("vampire", new float[]{1.0f, 2.25f});
        m.put("daimagoku", new float[]{1.7191f, 2.8652f});
        m.put("daimagokussj", new float[]{1.7191f, 2.8652f});
        m.put("daimagokussj2", new float[]{1.7191f, 2.8652f});
        m.put("daimagokussj3", new float[]{1.7191f, 2.8652f});
        m.put("daimagokussj4", new float[]{1.5646f, 2.6076f});
        m.put("daimamajinduu", new float[]{1.25f, 2.25f});
        m.put("daimamajinduussj3", new float[]{1.25f, 2.4224f});
        m.put("daimamajinkuu", new float[]{0.9688f, 1.875f});
        m.put("daimaporunga", new float[]{2.5f, 6.0f});
        m.put("daimavegetassj3", new float[]{1.7191f, 2.8652f});
        m.put("gomahthirdeye", new float[]{1.625f, 2.9062f});
        m.put("gomahthirdeyegigantic", new float[]{1.625f, 2.9062f});
        m.put("kaioshinchild", new float[]{0.9875f, 2.3807f});
        m.put("tamagami_1", new float[]{2.5f, 4.9375f});
        m.put("tamagami_2", new float[]{2.5f, 6.0f});
        m.put("tamagami_3", new float[]{2.5f, 5.3438f});
        m.put("bb", new float[]{1.4775f, 2.4625f});
        m.put("bb2", new float[]{1.4775f, 2.4625f});
        m.put("bbgoldoozaru", new float[]{1.4387f, 2.3979f});
        m.put("bbssj", new float[]{1.0f, 2.4046f});
        m.put("bbssj2", new float[]{1.4387f, 2.3979f});
        m.put("goku", new float[]{1.0f, 2.3859f});
        m.put("ledgic", new float[]{0.875f, 2.125f});
        m.put("omega", new float[]{1.3744f, 2.4062f});
        m.put("omega2", new float[]{1.3744f, 2.4062f});
        m.put("2stars", new float[]{1.1625f, 1.9375f});
        m.put("3or4stars", new float[]{1.2375f, 2.0625f});
        m.put("5stars", new float[]{0.975f, 1.625f});
        m.put("6stars", new float[]{1.1562f, 2.125f});
        m.put("6starstrueform", new float[]{1.1625f, 1.9375f});
        m.put("7stars", new float[]{0.9375f, 1.5625f});
        m.put("7starspan", new float[]{0.975f, 1.625f});
        m.put("7starstrue", new float[]{0.9375f, 1.5625f});
        m.put("supera17", new float[]{1.0f, 2.0f});
        m.put("vegetacopy", new float[]{1.375f, 2.3438f});
        m.put("vegetacopyblue", new float[]{1.375f, 2.3438f});
        m.put("blockgohanultimate", new float[]{1.5916f, 2.6527f});
        m.put("blockgoku", new float[]{1.0f, 2.3859f});
        m.put("blockgokussjr", new float[]{1.0f, 2.4199f});
        m.put("blockgokussjr3", new float[]{1.7191f, 2.8652f});
        m.put("trunksfutursuper", new float[]{1.0f, 2.3421f});
        m.put("trunksfutursuperssj", new float[]{1.0f, 2.3421f});
        m.put("trunksfutursuperssj2", new float[]{1.0f, 2.3421f});
        m.put("trunksfutursuperssjg3", new float[]{1.125f, 2.4671f});
        m.put("trunksfutursuperssjrage", new float[]{1.0f, 2.3421f});
        m.put("zamasu", new float[]{0.875f, 2.4161f});
        m.put("zamasu_fused", new float[]{1.785f, 2.975f});
        m.put("zamasu_fused2", new float[]{1.25f, 2.4562f});
        m.put("zamasumerged", new float[]{0.875f, 2.6036f});
        m.put("galacticpatrolsoldier", new float[]{0.9994f, 2.0f});
        m.put("merus", new float[]{0.875f, 2.125f});
        m.put("moroold", new float[]{0.9997f, 2.1875f});
        m.put("whis", new float[]{1.1062f, 2.5312f});
        m.put("beerus", new float[]{0.875f, 2.4688f});
        m.put("sorbet", new float[]{0.795f, 1.325f});
        m.put("tagoma", new float[]{1.25f, 2.1125f});
        m.put("dbsbrolybuff", new float[]{1.725f, 2.875f});
        m.put("dbsbrolylegendary", new float[]{1.675f, 2.9125f});
        m.put("dbsbrolynormal", new float[]{1.35f, 2.4125f});
        m.put("dbsparagus", new float[]{1.0562f, 2.0f});
        m.put("cellmax", new float[]{1.425f, 2.375f});
        m.put("cellperfectmax", new float[]{1.425f, 2.375f});
        m.put("gamma1", new float[]{1.0f, 2.3906f});
        m.put("gamma2", new float[]{1.0f, 2.3906f});
        m.put("monaka", new float[]{1.0f, 2.0f});
        m.put("beerusmonaka", new float[]{0.85f, 2.0187f});
        m.put("beerusmonaka2", new float[]{0.85f, 2.0187f});
        m.put("beerusmonaka3", new float[]{0.85f, 2.0187f});
        m.put("angelkusu", new float[]{1.0437f, 2.2375f});
        m.put("godrumsshi", new float[]{1.0625f, 2.0688f});
        m.put("murichim", new float[]{1.5f, 2.5f});
        m.put("napapa", new float[]{1.5438f, 2.6f});
        m.put("obni", new float[]{1.0f, 2.3125f});
        m.put("rylibeu", new float[]{1.15f, 2.0625f});
        m.put("cocotte", new float[]{1.0f, 2.0f});
        m.put("kahseral", new float[]{1.0f, 2.0f});
        m.put("kettle", new float[]{1.0f, 2.0f});
        m.put("tupper", new float[]{1.0f, 2.0f});
        m.put("zoire", new float[]{1.0f, 2.0f});
        m.put("angelmarcarita", new float[]{1.1938f, 2.525f});
        m.put("biarra", new float[]{1.6462f, 2.7437f});
        m.put("dyspo", new float[]{0.875f, 2.4688f});
        m.put("godbelmod", new float[]{0.875f, 1.9875f});
        m.put("jiren", new float[]{1.5f, 2.5f});
        m.put("jiren_full_power", new float[]{1.5188f, 2.5312f});
        m.put("toppo", new float[]{1.3087f, 2.1812f});
        m.put("toppo_god", new float[]{1.3837f, 2.3062f});
        m.put("angelsour", new float[]{1.1687f, 2.25f});
        m.put("godheles", new float[]{0.875f, 2.325f});
        m.put("kakunsa", new float[]{1.0f, 1.875f});
        m.put("roasie", new float[]{0.75f, 2.8f});
        m.put("angelcamparri", new float[]{1.1062f, 2.2188f});
        m.put("aniraza", new float[]{2.5f, 6.0f});
        m.put("aniraza2", new float[]{1.1125f, 2.0f});
        m.put("godmosco", new float[]{1.275f, 2.125f});
        m.put("koitsukai", new float[]{1.5075f, 2.5125f});
        m.put("narirama", new float[]{1.6313f, 2.9f});
        m.put("panchia", new float[]{2.25f, 3.7812f});
        m.put("paparoni", new float[]{1.0063f, 2.3f});
        m.put("angelcognac", new float[]{1.3125f, 2.4438f});
        m.put("dercori", new float[]{0.9625f, 2.7125f});
        m.put("ganos", new float[]{0.8125f, 1.875f});
        m.put("godquitela", new float[]{1.0625f, 2.2188f});
        m.put("majora", new float[]{0.9625f, 2.35f});
        m.put("shosa", new float[]{1.4062f, 2.3438f});
        m.put("hit", new float[]{1.0f, 2.0f});
        m.put("saonel", new float[]{1.0f, 2.0f});
        m.put("angelkorn", new float[]{1.1062f, 2.2188f});
        m.put("botamo", new float[]{1.2712f, 2.1187f});
        m.put("cabba", new float[]{0.875f, 2.3734f});
        m.put("caulifla", new float[]{0.75f, 2.075f});
        m.put("cauliflassj", new float[]{0.75f, 2.125f});
        m.put("champa", new float[]{0.875f, 2.4688f});
        m.put("kale", new float[]{0.75f, 2.3062f});
        m.put("kalessj", new float[]{0.9375f, 2.1375f});
        m.put("magetta", new float[]{1.5562f, 2.5938f});
        m.put("angelmohito", new float[]{1.1687f, 2.2188f});
        m.put("basil", new float[]{1.3913f, 2.3188f});
        m.put("bergamo", new float[]{1.3913f, 2.3188f});
        m.put("godsidra", new float[]{1.1287f, 1.8813f});
        m.put("lavender", new float[]{1.3087f, 2.1812f});
        m.put("kaioshin", new float[]{1.0f, 2.3807f});
        m.put("kibito", new float[]{1.0f, 2.3331f});
        m.put("buuevil", new float[]{0.875f, 2.5f});
        m.put("buufat", new float[]{1.35f, 2.25f});
        m.put("buukid", new float[]{0.825f, 2.125f});
        m.put("buusuper", new float[]{1.05f, 2.375f});
        m.put("buusuper_buffed", new float[]{1.2437f, 2.3687f});
        m.put("buusuper_fusion", new float[]{1.0125f, 3.6562f});
        m.put("buusuper_piccolo", new float[]{1.3f, 3.7062f});
        m.put("buusuper_ultimate", new float[]{1.0312f, 3.6375f});
        m.put("yakon", new float[]{1.275f, 2.125f});
        m.put("garlicjr", new float[]{0.9f, 1.5f});
        m.put("garlicjrsuper", new float[]{1.2f, 2.0f});
        m.put("ginger", new float[]{1.0f, 1.75f});
        m.put("nicky", new float[]{1.35f, 2.25f});
        m.put("sansho", new float[]{1.2f, 2.0f});
        m.put("brolyzlssj", new float[]{1.675f, 3.0391f});
        m.put("brolyzbio", new float[]{1.438f, 2.3966f});
        m.put("janemba", new float[]{1.8f, 3.0f});
        m.put("janembasuper", new float[]{1.4625f, 2.4375f});
        m.put("pikkon", new float[]{1.0f, 2.25f});
        m.put("officeogre", new float[]{1.0f, 2.2313f});
        m.put("officeogre2", new float[]{0.875f, 2.2313f});
        m.put("hirudegarnbottom", new float[]{1.65f, 2.75f});
        m.put("hirudegarnpost", new float[]{2.5f, 5.0159f});
        m.put("hirudegarnpre", new float[]{2.5f, 4.3125f});
        m.put("hirudegarnupper", new float[]{1.6125f, 2.6875f});
        m.put("hoi", new float[]{0.8688f, 1.875f});
        m.put("biowarrior1", new float[]{1.0f, 2.0f});
        m.put("biowarrior2", new float[]{1.0f, 2.0f});
        m.put("biowarrior3", new float[]{1.0f, 2.0f});
        m.put("biowarrior4", new float[]{1.0f, 2.0f});
        m.put("biowarrior5", new float[]{1.0f, 2.0f});
        m.put("biowarrior6", new float[]{1.0f, 2.0f});
        m.put("biomen", new float[]{0.9f, 1.5f});
        m.put("drwheelo", new float[]{2.5f, 4.75f});
        m.put("amond", new float[]{1.375f, 2.4796f});
        m.put("cacao", new float[]{1.2f, 2.0f});
        m.put("daiz", new float[]{1.2f, 2.0f});
        m.put("rasin", new float[]{0.9f, 1.5f});
        m.put("turles1", new float[]{1.4315f, 2.3859f});
        m.put("turles2", new float[]{1.4315f, 2.3859f});
        m.put("turles3", new float[]{1.4315f, 2.3859f});
        m.put("rasinlakasei", new float[]{0.9f, 1.5f});
        m.put("angila", new float[]{1.2f, 2.0f});
        m.put("medamatcha", new float[]{0.9f, 1.5f});
        m.put("medamatchaclone", new float[]{0.9f, 1.5f});
        m.put("sluggiant", new float[]{1.0f, 2.0f});
        m.put("slugold", new float[]{1.2375f, 2.0625f});
        m.put("slugsoldier", new float[]{1.0f, 2.0f});
        m.put("slugyoung", new float[]{1.0f, 2.0625f});
        m.put("wings", new float[]{1.2f, 2.0f});
        m.put("zeeun", new float[]{1.2f, 2.0f});
        m.put("cooler", new float[]{1.2f, 2.0f});
        m.put("coolerfifthform", new float[]{1.4363f, 2.3939f});
        m.put("dore", new float[]{1.1875f, 2.0f});
        m.put("neiz", new float[]{1.1625f, 1.9375f});
        m.put("salza", new float[]{1.1875f, 2.2373f});
        m.put("coolermetal", new float[]{1.2f, 2.0f});
        m.put("coolermetaltrueform", new float[]{1.0f, 2.0f});
        m.put("coolermetalrealform", new float[]{1.0f, 2.0f});
        m.put("android13", new float[]{1.125f, 2.0f});
        m.put("android13super", new float[]{1.1266f, 2.4046f});
        m.put("android14", new float[]{1.0625f, 2.0f});
        m.put("android15", new float[]{1.0f, 1.875f});
        m.put("brolyzssj", new float[]{1.35f, 2.4125f});
        m.put("paragus", new float[]{1.125f, 2.4359f});
        m.put("paragussoldier", new float[]{1.0f, 2.0f});
        m.put("broly", new float[]{1.4371f, 2.4161f});
        m.put("bido", new float[]{1.0f, 2.25f});
        m.put("bojack", new float[]{1.125f, 2.0f});
        m.put("bojacksuper", new float[]{1.125f, 2.0f});
        m.put("bujin", new float[]{1.0875f, 1.8125f});
        m.put("zangya", new float[]{0.9963f, 2.1875f});
        m.put("bdkninjin", new float[]{1.7191f, 2.8652f});
        m.put("abo", new float[]{1.2375f, 2.0625f});
        m.put("kado", new float[]{1.2375f, 2.0625f});
        m.put("abokado", new float[]{1.2375f, 2.0625f});
        m.put("futuregohan", new float[]{1.4684f, 2.4474f});
        m.put("futuregohan2", new float[]{1.4684f, 2.4474f});
        m.put("futuregohan2ssj", new float[]{1.5916f, 2.6527f});
        m.put("futuregohanssj", new float[]{1.5916f, 2.6527f});
        m.put("hatchiyack", new float[]{1.2551f, 2.3739f});
        m.put("hatchiyackgiant", new float[]{1.2551f, 2.3739f});
        m.put("hatchiyacksuper", new float[]{1.4243f, 2.3739f});
        m.put("bdkssjninjin", new float[]{1.7191f, 2.8652f});
        m.put("chilled", new float[]{1.425f, 2.375f});
        m.put("berryblue", new float[]{0.8287f, 1.3813f});
        // Generic saiyan grunts (DMZ friezasoldier rig plus a static tail): a standard humanoid box, matching the
        // other soldier faces (paragussoldier, biowarrior*). The server needs this to build the collision AABB.
        m.put("saiyan_grunt_1", new float[]{1.0f, 2.0f});
        m.put("saiyan_grunt_2", new float[]{1.0f, 2.0f});
        m.put("saiyan_grunt_3", new float[]{1.0f, 2.0f});
        return m;
    }
}
