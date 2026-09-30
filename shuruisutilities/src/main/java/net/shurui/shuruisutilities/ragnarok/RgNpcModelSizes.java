package net.shurui.shuruisutilities.ragnarok;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * GENERATED, do not edit by hand. Per-geo collision box sizes for {@link RgNpcEntity}, baked offline from the
 * bundled geo files under assets/dmz_ragnarok/geo/entity/ragnarok/*.geo.json.
 *
 * <p>Rebuilt in September 2026 when the streamed rgnpc set was replaced by the bundled NinjinEntities saga models
 * (see {@link RgNpcModels}). Keyed by GEO id (the value of {@link RgNpcModels#geoId(String)}), NOT by entry id.
 *
 * <p>Numbers are sanitised: height is max(0,yMax)/16, floored at 0.5 and capped at 6.0; width is
 * min(rawWidth, height*0.6), floored at 0.4 and capped at 2.5. Values are in blocks. The kept shadow dragon geos
 * keep their previously tuned boxes.
 */
public final class RgNpcModelSizes {

    private RgNpcModelSizes() {
    }

    private static final Map<String, float[]> SIZES = build();

    /**
     * The baked {width, height} for a geo id, or null when the geo is not in the table. Callers must treat null as
     * "fall back to the registered base size"; this never throws.
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
        m.put("2stars", new float[]{1.1625f, 1.9375f});
        m.put("3or4stars", new float[]{1.2375f, 2.0625f});
        m.put("5stars", new float[]{0.975f, 1.625f});
        m.put("6stars", new float[]{1.1562f, 2.125f});
        m.put("6starstrueform", new float[]{1.1625f, 1.9375f});
        m.put("7stars", new float[]{0.9375f, 1.5625f});
        m.put("7starspan", new float[]{0.975f, 1.625f});
        m.put("7starstrue", new float[]{0.9375f, 1.5625f});
        m.put("master_beerus", new float[]{0.875f, 2.4688f});
        m.put("master_buu", new float[]{1.1f, 2.2188f});
        m.put("master_tien", new float[]{1f, 2.1f});
        m.put("master_whis", new float[]{1.1062f, 2.5312f});
        m.put("omega", new float[]{1.3744f, 2.4062f});
        m.put("omega2", new float[]{1.3744f, 2.4062f});
        m.put("saga_angel_awamo", new float[]{1.2375f, 2.3f});
        m.put("saga_angel_camparri", new float[]{1.1062f, 2.2188f});
        m.put("saga_angel_cognac", new float[]{1.3125f, 2.4438f});
        m.put("saga_angel_cukatail", new float[]{1.2f, 2.2125f});
        m.put("saga_angel_korn", new float[]{1.1062f, 2.2188f});
        m.put("saga_angel_kusu", new float[]{1.0437f, 2.2375f});
        m.put("saga_angel_marcarita", new float[]{1.1938f, 2.525f});
        m.put("saga_angel_martinu", new float[]{1.1687f, 1.9688f});
        m.put("saga_angel_mohito", new float[]{1.1687f, 2.2188f});
        m.put("saga_angel_sour", new float[]{1.1687f, 2.25f});
        m.put("saga_beerus", new float[]{0.875f, 2.4688f});
        m.put("saga_cl_android8", new float[]{1.3725f, 2.2875f});
        m.put("saga_cl_bacterian", new float[]{1.4775f, 2.4625f});
        m.put("saga_cl_buyon", new float[]{1.41f, 2.35f});
        m.put("saga_cl_chiaotzu", new float[]{0.875f, 1.65f});
        m.put("saga_cl_chiaotzu2", new float[]{0.875f, 1.65f});
        m.put("saga_cl_chichi", new float[]{0.9062f, 2.1406f});
        m.put("saga_cl_colonel_silver", new float[]{1f, 2.1875f});
        m.put("saga_cl_colonel_violet", new float[]{0.775f, 1.875f});
        m.put("saga_cl_commander_red", new float[]{0.9937f, 1.6562f});
        m.put("saga_cl_cymbal", new float[]{1.5263f, 2.5438f});
        m.put("saga_cl_devil", new float[]{1.5037f, 2.5063f});
        m.put("saga_cl_drum", new float[]{1.3462f, 2.2437f});
        m.put("saga_cl_fortuneteller_baba", new float[]{1.125f, 2.375f});
        m.put("saga_cl_general_blue", new float[]{1f, 2f});
        m.put("saga_cl_general_white", new float[]{1f, 2.0312f});
        m.put("saga_cl_giran", new float[]{1.3538f, 2.2563f});
        m.put("saga_cl_grandpa_gohan", new float[]{0.95f, 1.9812f});
        m.put("saga_cl_hercule", new float[]{1.0312f, 2.0312f});
        m.put("saga_cl_invisible_man", new float[]{1f, 2f});
        m.put("saga_cl_jackie_chun", new float[]{0.95f, 1.75f});
        m.put("saga_cl_jackie_chun2", new float[]{1.3275f, 2.2125f});
        m.put("saga_cl_jackie_chun3", new float[]{0.8625f, 1.75f});
        m.put("saga_cl_kami", new float[]{1f, 2f});
        m.put("saga_cl_king_chappa", new float[]{1f, 2.075f});
        m.put("saga_cl_king_piccolo", new float[]{1.125f, 2.525f});
        m.put("saga_cl_king_piccolo2", new float[]{1.225f, 2.475f});
        m.put("saga_cl_launch", new float[]{0.9812f, 2.0312f});
        m.put("saga_cl_mai", new float[]{1f, 2f});
        m.put("saga_cl_mai_mecha", new float[]{1.635f, 2.725f});
        m.put("saga_cl_major_metallitron", new float[]{1.4925f, 2.4875f});
        m.put("saga_cl_man_wolf", new float[]{1.1625f, 1.9375f});
        m.put("saga_cl_master_shen", new float[]{0.95f, 1.875f});
        m.put("saga_cl_mercenary_tao", new float[]{1f, 2f});
        m.put("saga_cl_mercenary_tao2", new float[]{1f, 2f});
        m.put("saga_cl_mummy", new float[]{1.3725f, 2.2875f});
        m.put("saga_cl_nam", new float[]{1f, 2f});
        m.put("saga_cl_ninja_murasaki", new float[]{1.3125f, 2.3438f});
        m.put("saga_cl_officer_black", new float[]{1f, 2f});
        m.put("saga_cl_officer_black2", new float[]{1.23f, 2.05f});
        m.put("saga_cl_oolong", new float[]{0.875f, 1.6812f});
        m.put("saga_cl_pamput", new float[]{1f, 2.1187f});
        m.put("saga_cl_piano", new float[]{0.8175f, 1.3625f});
        m.put("saga_cl_pilaf", new float[]{0.8512f, 1.4187f});
        m.put("saga_cl_pilaf_mecha", new float[]{1.1438f, 1.9062f});
        m.put("saga_cl_pilaf_mecha_combined", new float[]{2.425f, 5.1063f});
        m.put("saga_cl_puar", new float[]{0.7312f, 1.2188f});
        m.put("saga_cl_red_ribbon_soldier_bazooka", new float[]{1.2f, 2f});
        m.put("saga_cl_red_ribbon_soldier_gunner", new float[]{1.2f, 2f});
        m.put("saga_cl_saiyan1", new float[]{1f, 2f});
        m.put("saga_cl_saiyan2", new float[]{1f, 2f});
        m.put("saga_cl_shu", new float[]{0.9788f, 1.6313f});
        m.put("saga_cl_shu_mecha", new float[]{1.7125f, 5.4375f});
        m.put("saga_cl_tambourine", new float[]{1.3125f, 2.1875f});
        m.put("saga_cl_tien", new float[]{1f, 2.1f});
        m.put("saga_cl_tournament_announcer", new float[]{1f, 2.0312f});
        m.put("saga_cl_vampire", new float[]{1f, 2.25f});
        m.put("saga_cl_yajirobe", new float[]{1.125f, 2.0562f});
        m.put("saga_future_mai", new float[]{1f, 2f});
        m.put("saga_god_arak", new float[]{1.075f, 2.3125f});
        m.put("saga_god_belmod", new float[]{0.875f, 1.9875f});
        m.put("saga_god_giin", new float[]{1f, 2.0187f});
        m.put("saga_god_heles", new float[]{0.875f, 2.325f});
        m.put("saga_god_iwan", new float[]{1.1875f, 1.9937f});
        m.put("saga_god_liquiir", new float[]{0.875f, 2.35f});
        m.put("saga_god_mosco", new float[]{1.275f, 2.125f});
        m.put("saga_god_quitela", new float[]{1.0625f, 2.2188f});
        m.put("saga_god_rumsshi", new float[]{1.0625f, 2.0688f});
        m.put("saga_god_sidra", new float[]{1.1287f, 1.8813f});
        m.put("saga_goku_black", new float[]{1.125f, 2.25f});
        m.put("saga_goku_black_rose", new float[]{1.25f, 2.4625f});
        m.put("saga_gp_daishinkan", new float[]{1f, 2.2256f});
        m.put("saga_gp_zenoguard", new float[]{1f, 2.0344f});
        m.put("saga_gt_baby", new float[]{1f, 2f});
        m.put("saga_gt_baby2", new float[]{1f, 2f});
        m.put("saga_gt_baby_ssj", new float[]{1f, 2.4046f});
        m.put("saga_gt_baby_ssj2", new float[]{1.4387f, 2.3979f});
        m.put("saga_gt_dragon2", new float[]{1.1625f, 1.9375f});
        m.put("saga_gt_dragon3", new float[]{1.2375f, 2.0625f});
        m.put("saga_gt_dragon4", new float[]{1.2375f, 2.0625f});
        m.put("saga_gt_dragon4fp", new float[]{1.2375f, 2.0625f});
        m.put("saga_gt_dragon5", new float[]{0.975f, 1.625f});
        m.put("saga_gt_dragon5fp", new float[]{0.975f, 1.625f});
        m.put("saga_gt_dragon6", new float[]{1.1562f, 2.125f});
        m.put("saga_gt_dragon6t", new float[]{1.1625f, 1.9375f});
        m.put("saga_gt_dragon7", new float[]{0.9375f, 1.5625f});
        m.put("saga_gt_dragon7pan", new float[]{0.975f, 1.625f});
        m.put("saga_gt_dragon7t", new float[]{0.9375f, 1.5625f});
        m.put("saga_gt_ledgic", new float[]{0.875f, 2.125f});
        m.put("saga_gt_luud", new float[]{0.875f, 2.0312f});
        m.put("saga_gt_luud_fp", new float[]{0.875f, 2.0312f});
        m.put("saga_gt_omega", new float[]{1.3745f, 2.4062f});
        m.put("saga_gt_rilldo", new float[]{1.2375f, 2.0625f});
        m.put("saga_gt_rilldo_meta", new float[]{1.2375f, 2.0625f});
        m.put("saga_gt_super17", new float[]{1f, 2f});
        m.put("saga_gt_syn", new float[]{1.3745f, 2.4062f});
        m.put("saga_hit", new float[]{1f, 2.0312f});
        m.put("saga_jiren", new float[]{1.5f, 2.5f});
        m.put("saga_jiren_fp", new float[]{1.5188f, 2.5312f});
        m.put("saga_mv_broly_base", new float[]{1.35f, 2.4125f});
        m.put("saga_mv_broly_lssj", new float[]{1.675f, 2.9125f});
        m.put("saga_mv_broly_ssj", new float[]{1.725f, 2.875f});
        m.put("saga_mv_broly_wrath", new float[]{1.35f, 2.4125f});
        m.put("saga_mv_cell_max", new float[]{1.425f, 2.375f});
        m.put("saga_mv_gamma1", new float[]{1f, 2.3906f});
        m.put("saga_mv_gamma2", new float[]{1f, 2.3906f});
        m.put("saga_mv_paragus", new float[]{1.0562f, 2f});
        m.put("saga_rof_shisami", new float[]{1.475f, 2.5688f});
        m.put("saga_rof_sorbet", new float[]{0.795f, 1.325f});
        m.put("saga_rof_tagoma", new float[]{1.25f, 2.1125f});
        m.put("saga_tk_caulifla", new float[]{0.75f, 2.075f});
        m.put("saga_tk_caulifla_ssj", new float[]{0.75f, 2.125f});
        m.put("saga_tk_dyspo", new float[]{0.875f, 2.4688f});
        m.put("saga_tk_kale", new float[]{0.75f, 2.3062f});
        m.put("saga_tk_kale_ssj", new float[]{0.9375f, 2.1375f});
        m.put("saga_tk_katopesla", new float[]{1.45f, 2.9625f});
        m.put("saga_tk_kefla", new float[]{1.05f, 2.2437f});
        m.put("saga_tk_kefla_ssj", new float[]{0.9062f, 2.2625f});
        m.put("saga_tk_rylibeu", new float[]{1.15f, 2.0625f});
        m.put("saga_tk_toppo", new float[]{1.3087f, 2.1812f});
        m.put("saga_tk_toppo_god", new float[]{1.3837f, 2.3062f});
        m.put("saga_u10_murichim", new float[]{1.5f, 2.5f});
        m.put("saga_u10_napapa", new float[]{1.5438f, 2.6f});
        m.put("saga_u10_obni", new float[]{1f, 2.3125f});
        m.put("saga_u11_cocotte", new float[]{1f, 1.875f});
        m.put("saga_u11_kahseral", new float[]{1f, 2.0562f});
        m.put("saga_u11_kunshi", new float[]{0.945f, 1.575f});
        m.put("saga_u2_kakunsa", new float[]{1f, 1.875f});
        m.put("saga_u2_ribrianne", new float[]{1.1375f, 2.5187f});
        m.put("saga_u2_rozie", new float[]{0.75f, 2.8f});
        m.put("saga_u3_anilaza", new float[]{2.5f, 6f});
        m.put("saga_u3_biarra", new float[]{1.6462f, 2.7437f});
        m.put("saga_u3_borareta", new float[]{2.3062f, 3.8438f});
        m.put("saga_u3_koichiarator", new float[]{2.5f, 5.675f});
        m.put("saga_u3_koitsukai", new float[]{1.5075f, 2.5125f});
        m.put("saga_u3_narirama", new float[]{1.6313f, 2.9f});
        m.put("saga_u3_panchia", new float[]{2.25f, 3.7812f});
        m.put("saga_u3_paparoni", new float[]{1.0063f, 2.3f});
        m.put("saga_u4_dercori", new float[]{0.9625f, 2.7125f});
        m.put("saga_u4_ganos", new float[]{0.8125f, 1.875f});
        m.put("saga_u4_ganos_true", new float[]{1.375f, 2.625f});
        m.put("saga_u4_majora", new float[]{0.9625f, 2.35f});
        m.put("saga_u4_shosa", new float[]{1.4062f, 2.3438f});
        m.put("saga_u6_botamo", new float[]{1.2712f, 2.1187f});
        m.put("saga_u6_cabba", new float[]{0.8125f, 2.1667f});
        m.put("saga_u6_cabba_ssj", new float[]{0.8125f, 2.1667f});
        m.put("saga_u6_champa", new float[]{0.875f, 2.4688f});
        m.put("saga_u6_frost", new float[]{1.2f, 2f});
        m.put("saga_u6_frost_final", new float[]{1.2f, 2f});
        m.put("saga_u6_magetta", new float[]{1.5562f, 2.5938f});
        m.put("saga_u6_monaka", new float[]{0.8375f, 1.675f});
        m.put("saga_u6_vados", new float[]{1.1062f, 2.475f});
        m.put("saga_u6_zeno", new float[]{0.7688f, 1.3375f});
        m.put("saga_u9_basil", new float[]{1.3913f, 2.3188f});
        m.put("saga_u9_bergamo", new float[]{1.3913f, 2.3188f});
        m.put("saga_u9_hop", new float[]{0.75f, 2.0938f});
        m.put("saga_u9_lavender", new float[]{1.3087f, 2.1812f});
        m.put("saga_u9_sorrel", new float[]{0.6875f, 2.2313f});
        m.put("saga_whis", new float[]{1.1062f, 2.5312f});
        m.put("saga_zamasu", new float[]{1.25f, 2.3188f});
        m.put("saga_zamasu_fused", new float[]{1.785f, 2.975f});
        m.put("saga_zamasu_fused2", new float[]{1.25f, 2.4562f});
        // Halloween 2026 zombified Z fighters (baked from the new geos, same formula as the rest).
        m.put("halloween_goku_ssj", new float[]{1.0f, 2.5f});
        m.put("halloween_goku_ssj2", new float[]{1.0f, 2.5f});
        m.put("halloween_krillin", new float[]{1.2156f, 2.5844f});
        m.put("halloween_vegeta", new float[]{1.2156f, 2.5844f});
        m.put("halloween_yamcha", new float[]{1.0f, 2.3345f});
        return m;
    }
}
