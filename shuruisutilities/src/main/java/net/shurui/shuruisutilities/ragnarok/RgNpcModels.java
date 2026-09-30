package net.shurui.shuruisutilities.ragnarok;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static manifest of the spawnable "rgnpc" GeckoLib display entries. Each entry maps an entry id to
 * (geo id, texture base name), resolving to {@code dmz_ragnarok:geo/entity/ragnarok/<geo>.geo.json} and
 * {@code dmz_ragnarok:textures/entity/ragnarok/<texture>.png}. This is a build-time table, not a runtime resource
 * scan, so it is safe to consult on both sides (server for command tab completion / validation, client for
 * defensive fallback) without touching the resource manager.
 *
 * <p><b>Bundled, not streamed (September 2026).</b> The model set is now shipped IN the jar again. The previous
 * server-streamed rgnpc pack is gone: the whole cast was replaced by the NinjinEntities saga models (MIT, original
 * authors kaixi, Vidal1sHere, Phoenix_II), taken from the DMZ Sparking distribution and bundled under
 * {@code assets/dmz_ragnarok/geo/entity/ragnarok/} and {@code .../textures/entity/ragnarok/}. Provenance confirmed
 * by the owner on 2026-09-29; see {@code NINJIN-LICENSE.txt} in the jar. The only models kept from the previous set
 * are the corrupted-cycle shadow dragons (the star models {@code 2stars}..{@code 7starstrue} and
 * {@code omega}/{@code omega2}), because {@code ShadowDragonDef} references them by id and they are our own content.
 *
 * <p>Because every listed geo now really ships, absence at draw time is the exception rather than the rule; it is
 * still handled by {@code RgNpcFallback} / the generated saiyan for a stored id that no longer names a model.
 *
 * <p>Every id is stored already sanitised (lowercase, {@code [a-z0-9._-]}); apply {@link #sanitize(String)} to any
 * user supplied id before turning it into a resource location. An entry id may differ from its geo id (a texture
 * variant reuses another entry's geo), so {@link #geoId(String)} is the only value that may be turned into a geo
 * resource location.
 */
public final class RgNpcModels {

    /** Default entry id used when a synced / requested id is not installed. A confirmed biped on the DMZ rig. */
    public static final String DEFAULT_ID = "2stars";

    /** One spawnable entry: which geo to load and which texture to paint on it (both base names, no extension). */
    private static final class Entry {
        final String geoId;
        final String texture;
        Entry(String geoId, String texture) {
            this.geoId = geoId;
            this.texture = texture;
        }
    }

    // entry id -> (geo id, texture base name), preserving insertion order for stable tab completion.
    private static final Map<String, Entry> ENTRIES = build();

    /**
     * Legacy entry-id remap: PRE-REPLACEMENT saved id -> current id. Entry ids are persisted verbatim in the
     * {@code su_rgnpc_model} NBT string on every spawned NPC, in disguises, in the NPC editor and in Custom NPCs.
     * This map is the OLD-ID -> NEW-ID alias built when the streamed set was replaced by the bundled NinjinEntities
     * saga models: an already-spawned NPC saved with an old character id keeps its character by resolving here to the
     * matching saga model. It is consulted by {@link #resolveId(String)} (and therefore by {@link #canon(String)},
     * which the geo / texture / validity accessors run through), so the alias is applied at every read site without
     * rewriting stored data.
     *
     * <p>An old id with no saga counterpart is intentionally NOT listed: {@link #resolveId(String)} then returns
     * null and the render path draws the generated saiyan rather than a wrong character.
     */
    private static final Map<String, String> LEGACY_IDS = buildLegacy();

    /**
     * Entry ids that require the Ragnarok Key to select or spawn. Empty as of the September 2026 replacement: the
     * whole bundled NinjinEntities set is freely available (the owner hand picked it), so nothing is key gated here.
     * {@link #availableIds(boolean)} still filters on it, so a future gated batch only needs rows added back.
     */
    private static final Set<String> GATED = new HashSet<>();

    private RgNpcModels() {
    }

    /** All entry ids, in manifest order, for tab completion and browsing. Full table, gate-agnostic. */
    public static Set<String> ids() {
        return Collections.unmodifiableSet(ENTRIES.keySet());
    }

    /** True when {@code id} names a key-locked entry. Membership check only; the full table still contains it. */
    public static boolean isGated(String id) {
        return id != null && GATED.contains(canon(id));
    }

    /**
     * Entry ids the command surface may offer / accept for the current key state, in manifest order.
     * {@code unlocked == true} returns the FULL id set; {@code unlocked == false} filters the gated ids out.
     */
    public static Collection<String> availableIds(boolean unlocked) {
        if (unlocked || GATED.isEmpty()) {
            return ids();
        }
        List<String> out = new ArrayList<>(ENTRIES.size());
        for (String id : ENTRIES.keySet()) {
            if (!GATED.contains(id)) {
                out.add(id);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * True when {@code id} names an installed entry, applying the legacy alias first so an already-spawned NPC saved
     * with an old character id still reports valid and keeps rendering its (aliased) model.
     */
    public static boolean isValidId(String id) {
        return canon(id) != null;
    }

    /**
     * The live entry id an id maps to: itself if it is already a live entry, else its {@link #LEGACY_IDS} alias if
     * that is live, else null. Never throws. Null is the signal the render path uses to draw the generated saiyan.
     */
    private static String canon(String id) {
        if (id == null) {
            return null;
        }
        if (ENTRIES.containsKey(id)) {
            return id;
        }
        String mapped = LEGACY_IDS.get(id);
        return mapped != null && ENTRIES.containsKey(mapped) ? mapped : null;
    }

    /**
     * Resolve a possibly-legacy entry id to a live one, or null when it names nothing live (the caller then draws the
     * generated saiyan / falls back). Public so the render models can canonicalise a synced id.
     */
    public static String resolveId(String id) {
        return canon(id);
    }

    /**
     * The geo base name (no ".geo.json") an entry loads, or the default entry's geo if the id is unknown. Never
     * null. Applies the legacy alias so a stored old id loads the matching saga geo.
     */
    public static String geoId(String id) {
        String c = canon(id);
        Entry e = c == null ? null : ENTRIES.get(c);
        return e != null ? e.geoId : ENTRIES.get(DEFAULT_ID).geoId;
    }

    /**
     * The default texture base name (no ".png") an entry paints, or the default entry's texture if the id is
     * unknown. Never null. Applies the legacy alias.
     */
    public static String defaultTexture(String id) {
        String c = canon(id);
        Entry e = c == null ? null : ENTRIES.get(c);
        return e != null ? e.texture : ENTRIES.get(DEFAULT_ID).texture;
    }

    /**
     * Lowercase, then replace every character outside {@code [a-z0-9._-]} with {@code _}. A null / blank input
     * yields the empty string. This is what makes a synced or user supplied id safe to push into a
     * {@link net.minecraft.resources.ResourceLocation} path without throwing.
     */
    public static String sanitize(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            out.append(ok ? c : '_');
        }
        return out.toString();
    }

    private static Map<String, Entry> build() {
        Map<String, Entry> m = new LinkedHashMap<>(256);
        // Kept: the corrupted-cycle shadow dragon models (referenced by ShadowDragonDef), bundled from
        // our own art. Everything else below is the NinjinEntities saga set. DEFAULT_ID stays 2stars.
        put(m, "2stars", "2stars", "2stars");
        put(m, "3or4stars", "3or4stars", "3or4stars");
        put(m, "3starsfullpower", "3or4stars", "3starsfullpower");
        put(m, "4stars", "3or4stars", "4stars");
        put(m, "4starsfullpower", "3or4stars", "4starsfullpower");
        put(m, "4_star_grayscale", "3or4stars", "4_star_grayscale");
        put(m, "5stars", "5stars", "5stars");
        put(m, "5starsfullpower", "5stars", "5starsfullpower");
        put(m, "6stars", "6stars", "6stars");
        put(m, "6starstrueform", "6starstrueform", "6starstrueform");
        put(m, "7stars", "7stars", "7stars");
        put(m, "7starspan", "7starspan", "7starspan");
        put(m, "7starstrue", "7starstrue", "7starstrue");
        put(m, "omega", "omega", "omega");
        put(m, "omega2", "omega2", "omega2");
        // NinjinEntities (MIT) saga + master models, bundled from the DMZ Sparking distribution. Entry id,
        // geo id and texture base name are all the file's own name. See NINJIN-LICENSE.txt.
        put(m, "master_beerus", "master_beerus", "master_beerus");
        put(m, "master_buu", "master_buu", "master_buu");
        put(m, "master_tien", "master_tien", "master_tien");
        put(m, "master_whis", "master_whis", "master_whis");
        put(m, "saga_angel_awamo", "saga_angel_awamo", "saga_angel_awamo");
        put(m, "saga_angel_camparri", "saga_angel_camparri", "saga_angel_camparri");
        put(m, "saga_angel_cognac", "saga_angel_cognac", "saga_angel_cognac");
        put(m, "saga_angel_cukatail", "saga_angel_cukatail", "saga_angel_cukatail");
        put(m, "saga_angel_korn", "saga_angel_korn", "saga_angel_korn");
        put(m, "saga_angel_kusu", "saga_angel_kusu", "saga_angel_kusu");
        put(m, "saga_angel_marcarita", "saga_angel_marcarita", "saga_angel_marcarita");
        put(m, "saga_angel_martinu", "saga_angel_martinu", "saga_angel_martinu");
        put(m, "saga_angel_mohito", "saga_angel_mohito", "saga_angel_mohito");
        put(m, "saga_angel_sour", "saga_angel_sour", "saga_angel_sour");
        put(m, "saga_beerus", "saga_beerus", "saga_beerus");
        put(m, "saga_cl_android8", "saga_cl_android8", "saga_cl_android8");
        put(m, "saga_cl_bacterian", "saga_cl_bacterian", "saga_cl_bacterian");
        put(m, "saga_cl_buyon", "saga_cl_buyon", "saga_cl_buyon");
        put(m, "saga_cl_chiaotzu", "saga_cl_chiaotzu", "saga_cl_chiaotzu");
        put(m, "saga_cl_chiaotzu2", "saga_cl_chiaotzu2", "saga_cl_chiaotzu2");
        put(m, "saga_cl_chichi", "saga_cl_chichi", "saga_cl_chichi");
        put(m, "saga_cl_colonel_silver", "saga_cl_colonel_silver", "saga_cl_colonel_silver");
        put(m, "saga_cl_colonel_violet", "saga_cl_colonel_violet", "saga_cl_colonel_violet");
        put(m, "saga_cl_commander_red", "saga_cl_commander_red", "saga_cl_commander_red");
        put(m, "saga_cl_cymbal", "saga_cl_cymbal", "saga_cl_cymbal");
        put(m, "saga_cl_devil", "saga_cl_devil", "saga_cl_devil");
        put(m, "saga_cl_drum", "saga_cl_drum", "saga_cl_drum");
        put(m, "saga_cl_fortuneteller_baba", "saga_cl_fortuneteller_baba", "saga_cl_fortuneteller_baba");
        put(m, "saga_cl_general_blue", "saga_cl_general_blue", "saga_cl_general_blue");
        put(m, "saga_cl_general_white", "saga_cl_general_white", "saga_cl_general_white");
        put(m, "saga_cl_giran", "saga_cl_giran", "saga_cl_giran");
        put(m, "saga_cl_grandpa_gohan", "saga_cl_grandpa_gohan", "saga_cl_grandpa_gohan");
        put(m, "saga_cl_hercule", "saga_cl_hercule", "saga_cl_hercule");
        put(m, "saga_cl_invisible_man", "saga_cl_invisible_man", "saga_cl_invisible_man");
        put(m, "saga_cl_jackie_chun", "saga_cl_jackie_chun", "saga_cl_jackie_chun");
        put(m, "saga_cl_jackie_chun2", "saga_cl_jackie_chun2", "saga_cl_jackie_chun2");
        put(m, "saga_cl_jackie_chun3", "saga_cl_jackie_chun3", "saga_cl_jackie_chun3");
        put(m, "saga_cl_kami", "saga_cl_kami", "saga_cl_kami");
        put(m, "saga_cl_king_chappa", "saga_cl_king_chappa", "saga_cl_king_chappa");
        put(m, "saga_cl_king_piccolo", "saga_cl_king_piccolo", "saga_cl_king_piccolo");
        put(m, "saga_cl_king_piccolo2", "saga_cl_king_piccolo2", "saga_cl_king_piccolo2");
        put(m, "saga_cl_launch", "saga_cl_launch", "saga_cl_launch");
        put(m, "saga_cl_mai", "saga_cl_mai", "saga_cl_mai");
        put(m, "saga_cl_mai_mecha", "saga_cl_mai_mecha", "saga_cl_mai_mecha");
        put(m, "saga_cl_major_metallitron", "saga_cl_major_metallitron", "saga_cl_major_metallitron");
        put(m, "saga_cl_man_wolf", "saga_cl_man_wolf", "saga_cl_man_wolf");
        put(m, "saga_cl_master_shen", "saga_cl_master_shen", "saga_cl_master_shen");
        put(m, "saga_cl_mercenary_tao", "saga_cl_mercenary_tao", "saga_cl_mercenary_tao");
        put(m, "saga_cl_mercenary_tao2", "saga_cl_mercenary_tao2", "saga_cl_mercenary_tao2");
        put(m, "saga_cl_mummy", "saga_cl_mummy", "saga_cl_mummy");
        put(m, "saga_cl_nam", "saga_cl_nam", "saga_cl_nam");
        put(m, "saga_cl_ninja_murasaki", "saga_cl_ninja_murasaki", "saga_cl_ninja_murasaki");
        put(m, "saga_cl_officer_black", "saga_cl_officer_black", "saga_cl_officer_black");
        put(m, "saga_cl_officer_black2", "saga_cl_officer_black2", "saga_cl_officer_black2");
        put(m, "saga_cl_oolong", "saga_cl_oolong", "saga_cl_oolong");
        put(m, "saga_cl_pamput", "saga_cl_pamput", "saga_cl_pamput");
        put(m, "saga_cl_piano", "saga_cl_piano", "saga_cl_piano");
        put(m, "saga_cl_pilaf", "saga_cl_pilaf", "saga_cl_pilaf");
        put(m, "saga_cl_pilaf_mecha", "saga_cl_pilaf_mecha", "saga_cl_pilaf_mecha");
        put(m, "saga_cl_pilaf_mecha_combined", "saga_cl_pilaf_mecha_combined", "saga_cl_pilaf_mecha_combined");
        put(m, "saga_cl_puar", "saga_cl_puar", "saga_cl_puar");
        put(m, "saga_cl_red_ribbon_soldier_bazooka", "saga_cl_red_ribbon_soldier_bazooka", "saga_cl_red_ribbon_soldier_bazooka");
        put(m, "saga_cl_red_ribbon_soldier_gunner", "saga_cl_red_ribbon_soldier_gunner", "saga_cl_red_ribbon_soldier_gunner");
        put(m, "saga_cl_saiyan1", "saga_cl_saiyan1", "saga_cl_saiyan1");
        put(m, "saga_cl_saiyan2", "saga_cl_saiyan2", "saga_cl_saiyan2");
        put(m, "saga_cl_shu", "saga_cl_shu", "saga_cl_shu");
        put(m, "saga_cl_shu_mecha", "saga_cl_shu_mecha", "saga_cl_shu_mecha");
        put(m, "saga_cl_tambourine", "saga_cl_tambourine", "saga_cl_tambourine");
        put(m, "saga_cl_tien", "saga_cl_tien", "saga_cl_tien");
        put(m, "saga_cl_tournament_announcer", "saga_cl_tournament_announcer", "saga_cl_tournament_announcer");
        put(m, "saga_cl_vampire", "saga_cl_vampire", "saga_cl_vampire");
        put(m, "saga_cl_yajirobe", "saga_cl_yajirobe", "saga_cl_yajirobe");
        put(m, "saga_future_mai", "saga_future_mai", "saga_future_mai");
        put(m, "saga_god_arak", "saga_god_arak", "saga_god_arak");
        put(m, "saga_god_belmod", "saga_god_belmod", "saga_god_belmod");
        put(m, "saga_god_giin", "saga_god_giin", "saga_god_giin");
        put(m, "saga_god_heles", "saga_god_heles", "saga_god_heles");
        put(m, "saga_god_iwan", "saga_god_iwan", "saga_god_iwan");
        put(m, "saga_god_liquiir", "saga_god_liquiir", "saga_god_liquiir");
        put(m, "saga_god_mosco", "saga_god_mosco", "saga_god_mosco");
        put(m, "saga_god_quitela", "saga_god_quitela", "saga_god_quitela");
        put(m, "saga_god_rumsshi", "saga_god_rumsshi", "saga_god_rumsshi");
        put(m, "saga_god_sidra", "saga_god_sidra", "saga_god_sidra");
        put(m, "saga_goku_black", "saga_goku_black", "saga_goku_black");
        put(m, "saga_goku_black_rose", "saga_goku_black_rose", "saga_goku_black_rose");
        put(m, "saga_gp_daishinkan", "saga_gp_daishinkan", "saga_gp_daishinkan");
        put(m, "saga_gp_zenoguard", "saga_gp_zenoguard", "saga_gp_zenoguard");
        put(m, "saga_gt_baby", "saga_gt_baby", "saga_gt_baby");
        put(m, "saga_gt_baby2", "saga_gt_baby2", "saga_gt_baby2");
        put(m, "saga_gt_baby_ssj", "saga_gt_baby_ssj", "saga_gt_baby_ssj");
        put(m, "saga_gt_baby_ssj2", "saga_gt_baby_ssj2", "saga_gt_baby_ssj2");
        put(m, "saga_gt_dragon2", "saga_gt_dragon2", "saga_gt_dragon2");
        put(m, "saga_gt_dragon3", "saga_gt_dragon3", "saga_gt_dragon3");
        put(m, "saga_gt_dragon4", "saga_gt_dragon4", "saga_gt_dragon4");
        put(m, "saga_gt_dragon4fp", "saga_gt_dragon4fp", "saga_gt_dragon4fp");
        put(m, "saga_gt_dragon5", "saga_gt_dragon5", "saga_gt_dragon5");
        put(m, "saga_gt_dragon5fp", "saga_gt_dragon5fp", "saga_gt_dragon5fp");
        put(m, "saga_gt_dragon6", "saga_gt_dragon6", "saga_gt_dragon6");
        put(m, "saga_gt_dragon6t", "saga_gt_dragon6t", "saga_gt_dragon6t");
        put(m, "saga_gt_dragon7", "saga_gt_dragon7", "saga_gt_dragon7");
        put(m, "saga_gt_dragon7pan", "saga_gt_dragon7pan", "saga_gt_dragon7pan");
        put(m, "saga_gt_dragon7t", "saga_gt_dragon7t", "saga_gt_dragon7t");
        put(m, "saga_gt_ledgic", "saga_gt_ledgic", "saga_gt_ledgic");
        put(m, "saga_gt_luud", "saga_gt_luud", "saga_gt_luud");
        put(m, "saga_gt_luud_fp", "saga_gt_luud_fp", "saga_gt_luud_fp");
        put(m, "saga_gt_omega", "saga_gt_omega", "saga_gt_omega");
        put(m, "saga_gt_rilldo", "saga_gt_rilldo", "saga_gt_rilldo");
        put(m, "saga_gt_rilldo_meta", "saga_gt_rilldo_meta", "saga_gt_rilldo_meta");
        put(m, "saga_gt_super17", "saga_gt_super17", "saga_gt_super17");
        put(m, "saga_gt_syn", "saga_gt_syn", "saga_gt_syn");
        put(m, "saga_hit", "saga_hit", "saga_hit");
        put(m, "saga_jiren", "saga_jiren", "saga_jiren");
        put(m, "saga_jiren_fp", "saga_jiren_fp", "saga_jiren_fp");
        put(m, "saga_mv_broly_base", "saga_mv_broly_base", "saga_mv_broly_base");
        put(m, "saga_mv_broly_lssj", "saga_mv_broly_lssj", "saga_mv_broly_lssj");
        put(m, "saga_mv_broly_ssj", "saga_mv_broly_ssj", "saga_mv_broly_ssj");
        put(m, "saga_mv_broly_wrath", "saga_mv_broly_wrath", "saga_mv_broly_wrath");
        put(m, "saga_mv_cell_max", "saga_mv_cell_max", "saga_mv_cell_max");
        put(m, "saga_mv_gamma1", "saga_mv_gamma1", "saga_mv_gamma1");
        put(m, "saga_mv_gamma2", "saga_mv_gamma2", "saga_mv_gamma2");
        put(m, "saga_mv_paragus", "saga_mv_paragus", "saga_mv_paragus");
        put(m, "saga_rof_shisami", "saga_rof_shisami", "saga_rof_shisami");
        put(m, "saga_rof_sorbet", "saga_rof_sorbet", "saga_rof_sorbet");
        put(m, "saga_rof_tagoma", "saga_rof_tagoma", "saga_rof_tagoma");
        put(m, "saga_tk_caulifla", "saga_tk_caulifla", "saga_tk_caulifla");
        put(m, "saga_tk_caulifla_ssj", "saga_tk_caulifla_ssj", "saga_tk_caulifla_ssj");
        put(m, "saga_tk_dyspo", "saga_tk_dyspo", "saga_tk_dyspo");
        put(m, "saga_tk_kale", "saga_tk_kale", "saga_tk_kale");
        put(m, "saga_tk_kale_ssj", "saga_tk_kale_ssj", "saga_tk_kale_ssj");
        put(m, "saga_tk_katopesla", "saga_tk_katopesla", "saga_tk_katopesla");
        put(m, "saga_tk_kefla", "saga_tk_kefla", "saga_tk_kefla");
        put(m, "saga_tk_kefla_ssj", "saga_tk_kefla_ssj", "saga_tk_kefla_ssj");
        put(m, "saga_tk_rylibeu", "saga_tk_rylibeu", "saga_tk_rylibeu");
        put(m, "saga_tk_toppo", "saga_tk_toppo", "saga_tk_toppo");
        put(m, "saga_tk_toppo_god", "saga_tk_toppo_god", "saga_tk_toppo_god");
        put(m, "saga_u10_murichim", "saga_u10_murichim", "saga_u10_murichim");
        put(m, "saga_u10_napapa", "saga_u10_napapa", "saga_u10_napapa");
        put(m, "saga_u10_obni", "saga_u10_obni", "saga_u10_obni");
        put(m, "saga_u11_cocotte", "saga_u11_cocotte", "saga_u11_cocotte");
        put(m, "saga_u11_kahseral", "saga_u11_kahseral", "saga_u11_kahseral");
        put(m, "saga_u11_kunshi", "saga_u11_kunshi", "saga_u11_kunshi");
        put(m, "saga_u2_kakunsa", "saga_u2_kakunsa", "saga_u2_kakunsa");
        put(m, "saga_u2_ribrianne", "saga_u2_ribrianne", "saga_u2_ribrianne");
        put(m, "saga_u2_rozie", "saga_u2_rozie", "saga_u2_rozie");
        put(m, "saga_u3_anilaza", "saga_u3_anilaza", "saga_u3_anilaza");
        put(m, "saga_u3_biarra", "saga_u3_biarra", "saga_u3_biarra");
        put(m, "saga_u3_borareta", "saga_u3_borareta", "saga_u3_borareta");
        put(m, "saga_u3_koichiarator", "saga_u3_koichiarator", "saga_u3_koichiarator");
        put(m, "saga_u3_koitsukai", "saga_u3_koitsukai", "saga_u3_koitsukai");
        put(m, "saga_u3_narirama", "saga_u3_narirama", "saga_u3_narirama");
        put(m, "saga_u3_panchia", "saga_u3_panchia", "saga_u3_panchia");
        put(m, "saga_u3_paparoni", "saga_u3_paparoni", "saga_u3_paparoni");
        put(m, "saga_u4_dercori", "saga_u4_dercori", "saga_u4_dercori");
        put(m, "saga_u4_ganos", "saga_u4_ganos", "saga_u4_ganos");
        put(m, "saga_u4_ganos_true", "saga_u4_ganos_true", "saga_u4_ganos_true");
        put(m, "saga_u4_majora", "saga_u4_majora", "saga_u4_majora");
        put(m, "saga_u4_shosa", "saga_u4_shosa", "saga_u4_shosa");
        put(m, "saga_u6_botamo", "saga_u6_botamo", "saga_u6_botamo");
        put(m, "saga_u6_cabba", "saga_u6_cabba", "saga_u6_cabba");
        put(m, "saga_u6_cabba_ssj", "saga_u6_cabba_ssj", "saga_u6_cabba_ssj");
        put(m, "saga_u6_champa", "saga_u6_champa", "saga_u6_champa");
        put(m, "saga_u6_frost", "saga_u6_frost", "saga_u6_frost");
        put(m, "saga_u6_frost_final", "saga_u6_frost_final", "saga_u6_frost_final");
        put(m, "saga_u6_magetta", "saga_u6_magetta", "saga_u6_magetta");
        put(m, "saga_u6_monaka", "saga_u6_monaka", "saga_u6_monaka");
        put(m, "saga_u6_vados", "saga_u6_vados", "saga_u6_vados");
        put(m, "saga_u6_zeno", "saga_u6_zeno", "saga_u6_zeno");
        put(m, "saga_u9_basil", "saga_u9_basil", "saga_u9_basil");
        put(m, "saga_u9_bergamo", "saga_u9_bergamo", "saga_u9_bergamo");
        put(m, "saga_u9_hop", "saga_u9_hop", "saga_u9_hop");
        put(m, "saga_u9_lavender", "saga_u9_lavender", "saga_u9_lavender");
        put(m, "saga_u9_sorrel", "saga_u9_sorrel", "saga_u9_sorrel");
        put(m, "saga_whis", "saga_whis", "saga_whis");
        put(m, "saga_zamasu", "saga_zamasu", "saga_zamasu");
        put(m, "saga_zamasu_fused", "saga_zamasu_fused", "saga_zamasu_fused");
        put(m, "saga_zamasu_fused2", "saga_zamasu_fused2", "saga_zamasu_fused2");
        // Halloween 2026 zombified Z fighters (the owner's own Halloween art). Each is a saga model with a
        // zombified skin; halloween-prefixed ids so nothing existing is overwritten. Goku SSJ and SSJ2 share the
        // one Goku skin. Vegeto has a skin but no dedicated geo, so it reuses the Vegeta geo (same 64x64
        // player-skin UV layout and the closest silhouette); drop in halloween_vegeto.geo.json later and repoint.
        put(m, "halloween_goku_ssj", "halloween_goku_ssj", "halloween_goku");
        put(m, "halloween_goku_ssj2", "halloween_goku_ssj2", "halloween_goku");
        put(m, "halloween_krillin", "halloween_krillin", "halloween_krillin");
        put(m, "halloween_vegeta", "halloween_vegeta", "halloween_vegeta");
        put(m, "halloween_yamcha", "halloween_yamcha", "halloween_yamcha");
        put(m, "halloween_vegeto", "halloween_vegeta", "halloween_vegeto");
        return m;
    }

    private static Map<String, String> buildLegacy() {
        // OLD-ID -> NEW-ID alias built when the streamed set was replaced by the bundled NinjinEntities saga models
        // (September 2026). Old ids not listed here have no saga counterpart and resolve to null on purpose.
        Map<String, String> m = new LinkedHashMap<>(256);
        m.put("android8", "saga_cl_android8");
        m.put("angelawamo", "saga_angel_awamo");
        m.put("angelcamparri", "saga_angel_camparri");
        m.put("angelcognac", "saga_angel_cognac");
        m.put("angelcukatail", "saga_angel_cukatail");
        m.put("angelkorn", "saga_angel_korn");
        m.put("angelkusu", "saga_angel_kusu");
        m.put("angelmarcarita", "saga_angel_marcarita");
        m.put("angelmartinu", "saga_angel_martinu");
        m.put("angelmohito", "saga_angel_mohito");
        m.put("angelsour", "saga_angel_sour");
        m.put("aniraza", "saga_u3_anilaza");
        m.put("aniraza2", "saga_u3_anilaza");
        m.put("bacterian", "saga_cl_bacterian");
        m.put("basil", "saga_u9_basil");
        m.put("beerus", "saga_beerus");
        m.put("bergamo", "saga_u9_bergamo");
        m.put("biarra", "saga_u3_biarra");
        m.put("botamo", "saga_u6_botamo");
        m.put("brianne", "saga_u2_ribrianne");
        m.put("broly", "saga_mv_broly_lssj");
        m.put("brolyssj", "saga_mv_broly_ssj");
        m.put("brolyzbase", "saga_mv_broly_base");
        m.put("brolyzbio", "saga_mv_broly_base");
        m.put("brolyzlssj", "saga_mv_broly_lssj");
        m.put("brolyzssj", "saga_mv_broly_ssj");
        m.put("buyon", "saga_cl_buyon");
        m.put("cabba", "saga_u6_cabba");
        m.put("cabbassj", "saga_u6_cabba_ssj");
        m.put("caulifla", "saga_tk_caulifla");
        m.put("cauliflassj", "saga_tk_caulifla_ssj");
        m.put("cellmax", "saga_mv_cell_max");
        m.put("cellperfectmax", "saga_mv_cell_max");
        m.put("champa", "saga_u6_champa");
        m.put("chiaotzu", "saga_cl_chiaotzu");
        m.put("cocotte", "saga_u11_cocotte");
        m.put("colonelsilver", "saga_cl_colonel_silver");
        m.put("colonelviolet", "saga_cl_colonel_violet");
        m.put("commanderred", "saga_cl_commander_red");
        m.put("cymbal", "saga_cl_cymbal");
        m.put("dbc_cabba", "saga_u6_cabba");
        m.put("dbc_cymbal", "saga_cl_cymbal");
        m.put("dbc_drum", "saga_cl_drum");
        m.put("dbc_tambourine", "saga_cl_tambourine");
        m.put("dbc_whis", "saga_whis");
        m.put("dbc_zamasu", "saga_zamasu");
        m.put("dbsbroly2", "saga_mv_broly_base");
        m.put("dbsbrolybuff", "saga_mv_broly_wrath");
        m.put("dbsbrolylegendary", "saga_mv_broly_lssj");
        m.put("dbsbrolynormal", "saga_mv_broly_base");
        m.put("dbsparagus", "saga_mv_paragus");
        m.put("dercori", "saga_u4_dercori");
        m.put("devil", "saga_cl_devil");
        m.put("drum", "saga_cl_drum");
        m.put("dyspo", "saga_tk_dyspo");
        m.put("gamma1", "saga_mv_gamma1");
        m.put("gamma2", "saga_mv_gamma2");
        m.put("ganos", "saga_u4_ganos");
        m.put("generalblue", "saga_cl_general_blue");
        m.put("generalwhite", "saga_cl_general_white");
        m.put("giran", "saga_cl_giran");
        m.put("godarak", "saga_god_arak");
        m.put("godbelmod", "saga_god_belmod");
        m.put("godgiin", "saga_god_giin");
        m.put("godheles", "saga_god_heles");
        m.put("godiwan", "saga_god_iwan");
        m.put("godliquiir", "saga_god_liquiir");
        m.put("godmosco", "saga_god_mosco");
        m.put("godquitela", "saga_god_quitela");
        m.put("godrumsshi", "saga_god_rumsshi");
        m.put("godsidra", "saga_god_sidra");
        m.put("gokublackrose", "saga_goku_black_rose");
        m.put("grandpagohan", "saga_cl_grandpa_gohan");
        m.put("hit", "saga_hit");
        m.put("hop", "saga_u9_hop");
        m.put("jackiechun", "saga_cl_jackie_chun");
        m.put("jackiechun2", "saga_cl_jackie_chun2");
        m.put("jackiechun3", "saga_cl_jackie_chun3");
        m.put("jiren", "saga_jiren");
        m.put("jiren_full_power", "saga_jiren_fp");
        m.put("kahseral", "saga_u11_kahseral");
        m.put("kakunsa", "saga_u2_kakunsa");
        m.put("kale", "saga_tk_kale");
        m.put("kalessj", "saga_tk_kale_ssj");
        m.put("katopesla", "saga_tk_katopesla");
        m.put("kefla", "saga_tk_kefla");
        m.put("keflassj", "saga_tk_kefla_ssj");
        m.put("kingchappa", "saga_cl_king_chappa");
        m.put("kingpiccolo", "saga_cl_king_piccolo");
        m.put("kingpiccolo2", "saga_cl_king_piccolo2");
        m.put("koitsukai", "saga_u3_koitsukai");
        m.put("kunshi", "saga_u11_kunshi");
        m.put("launch", "saga_cl_launch");
        m.put("lavender", "saga_u9_lavender");
        m.put("ledgic", "saga_gt_ledgic");
        m.put("magetta", "saga_u6_magetta");
        m.put("mai", "saga_cl_mai");
        m.put("maimecha", "saga_cl_mai_mecha");
        m.put("majora", "saga_u4_majora");
        m.put("majormetallitron", "saga_cl_major_metallitron");
        m.put("manwolf", "saga_cl_man_wolf");
        m.put("mastershen", "saga_cl_master_shen");
        m.put("mercenarytao", "saga_cl_mercenary_tao");
        m.put("mercenarytao2", "saga_cl_mercenary_tao2");
        m.put("monaka", "saga_u6_monaka");
        m.put("murichim", "saga_u10_murichim");
        m.put("nam", "saga_cl_nam");
        m.put("napapa", "saga_u10_napapa");
        m.put("narirama", "saga_u3_narirama");
        m.put("ninjamurasaki", "saga_cl_ninja_murasaki");
        m.put("obni", "saga_u10_obni");
        m.put("officerblack", "saga_cl_officer_black");
        m.put("officerblack2", "saga_cl_officer_black2");
        m.put("oolong", "saga_cl_oolong");
        m.put("pamput", "saga_cl_pamput");
        m.put("panchia", "saga_u3_panchia");
        m.put("paparoni", "saga_u3_paparoni");
        m.put("paragus", "saga_mv_paragus");
        m.put("piano", "saga_cl_piano");
        m.put("pilaf", "saga_cl_pilaf");
        m.put("pilafmecha", "saga_cl_pilaf_mecha");
        m.put("pilafmechacombined", "saga_cl_pilaf_mecha_combined");
        m.put("puar", "saga_cl_puar");
        m.put("redribbonsoldierbazooka", "saga_cl_red_ribbon_soldier_bazooka");
        m.put("redribbonsoldiergunner", "saga_cl_red_ribbon_soldier_gunner");
        m.put("roasie", "saga_u2_rozie");
        m.put("rylibeu", "saga_tk_rylibeu");
        m.put("shisami", "saga_rof_shisami");
        m.put("shosa", "saga_u4_shosa");
        m.put("shu", "saga_cl_shu");
        m.put("shumecha", "saga_cl_shu_mecha");
        m.put("sorbet", "saga_rof_sorbet");
        m.put("sorrel", "saga_u9_sorrel");
        m.put("supera17", "saga_gt_super17");
        m.put("tagoma", "saga_rof_tagoma");
        m.put("tambourine", "saga_cl_tambourine");
        m.put("tien", "saga_cl_tien");
        m.put("toppo", "saga_tk_toppo");
        m.put("toppo_god", "saga_tk_toppo_god");
        m.put("tournamentannouncer", "saga_cl_tournament_announcer");
        // "upa" never had a bundled model, so it used to resolve to null and the caller then drew the default 2stars
        // face (Haze Shenron). It was a member of the OVERWORLD (Red Ribbon Army) garrison family, so any defender NPC
        // already saved to a live world with modelId "upa" would render as Haze Shenron until it died. Alias it to a
        // Red Ribbon soldier so those persisted defenders draw a fitting face instead of the shadow dragon.
        m.put("upa", "saga_cl_red_ribbon_soldier_gunner");
        m.put("vados", "saga_u6_vados");
        m.put("vampire", "saga_cl_vampire");
        m.put("whis", "saga_whis");
        m.put("yajirobe", "saga_cl_yajirobe");
        m.put("zamasu", "saga_zamasu");
        m.put("zamasu_fused", "saga_zamasu_fused");
        m.put("zamasu_fused2", "saga_zamasu_fused2");
        m.put("zeno", "saga_u6_zeno");
        return m;
    }

    private static void put(Map<String, Entry> m, String id, String geoId, String texture) {
        m.put(id, new Entry(geoId, texture));
    }
}
