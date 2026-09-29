package net.shurui.shuruisutilities.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The DragonMineZ NPC geo paths a {@code /model set dmz:<path>} may name (relative to {@code dragonminez:geo/entity/},
 * no {@code .geo.json}), used to validate the id server-side so an unknown one is rejected instead of silently
 * degrading.
 *
 * <p>Generated at build time from {@code libs/dragonminez-2.1.3.jar}: of its 184 entity geos, the {@code enemies/},
 * {@code master/} and {@code sagas/} ones (humanoid NPC bodies) that ship a PARALLEL texture
 * ({@code dragonminez:textures/entity/<path>.png}), which is what the model redirect paints them with: 120 entries.
 * Left out on purpose: race bodies (textureless, painted by layers), props and effects (kinton, scouter, auras, ki
 * weapons, skills, the space pod), animals and dragons (not player-shaped), and the 14 saga geos whose textures are
 * split per form. A client whose GeckoLib did not bake a listed geo still degrades to a normal player
 * ({@code ModelClientCache.resolve}), never a crash. Paths keep their subfolder and their {@code /}, so the id must
 * NOT be slash-sanitised. Regenerate this list if the DMZ jar version changes.
 */
public final class DmzNpcModels
{
    private DmzNpcModels() {}

    private static final Set<String> GEOS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        "enemies/bandit",
        "enemies/red_ribbon_soldier",
        "enemies/robot1",
        "enemies/robotxv",
        "master/master_babidi",
        "master/master_cell",
        "master/master_dende",
        "master/master_enma",
        "master/master_frieza",
        "master/master_gero",
        "master/master_gohan",
        "master/master_goku",
        "master/master_guru",
        "master/master_kaiosama",
        "master/master_karin",
        "master/master_krillin",
        "master/master_oldkai",
        "master/master_piccolo",
        "master/master_popo",
        "master/master_roshi",
        "master/master_toribot",
        "master/master_trunks",
        "master/master_uranai",
        "master/master_vegeta",
        "master/master_yamcha",
        "sagas/saga_a13",
        "sagas/saga_a14",
        "sagas/saga_a15",
        "sagas/saga_a16",
        "sagas/saga_a17",
        "sagas/saga_a18",
        "sagas/saga_a19",
        "sagas/saga_babidi",
        "sagas/saga_bido",
        "sagas/saga_bio_broly",
        "sagas/saga_bojack",
        "sagas/saga_bojack_fp",
        "sagas/saga_broly_base",
        "sagas/saga_broly_lssj",
        "sagas/saga_broly_ssj",
        "sagas/saga_bujin",
        "sagas/saga_bulma",
        "sagas/saga_burter",
        "sagas/saga_buufat",
        "sagas/saga_cell_imperfect",
        "sagas/saga_cell_jr",
        "sagas/saga_cell_perfect",
        "sagas/saga_cell_semiperfect",
        "sagas/saga_chaoz",
        "sagas/saga_cooler",
        "sagas/saga_cooler_5ta",
        "sagas/saga_cui",
        "sagas/saga_dabura",
        "sagas/saga_dodoria",
        "sagas/saga_dore",
        "sagas/saga_dr_wheelo",
        "sagas/saga_drgero",
        "sagas/saga_evilbuu",
        "sagas/saga_frieza_base",
        "sagas/saga_frieza_first",
        "sagas/saga_frieza_fp",
        "sagas/saga_frieza_second",
        "sagas/saga_frieza_third",
        "sagas/saga_friezasoldier01",
        "sagas/saga_friezasoldier02",
        "sagas/saga_friezasoldier03",
        "sagas/saga_garlick_jr",
        "sagas/saga_garlick_jr_transformed",
        "sagas/saga_gete_robot",
        "sagas/saga_ginyu",
        "sagas/saga_ginyu_goku",
        "sagas/saga_gohan_end_ssj",
        "sagas/saga_gohan_end_ssj2",
        "sagas/saga_gohan_mid_ssj2",
        "sagas/saga_gokua",
        "sagas/saga_goten",
        "sagas/saga_goten_ssj",
        "sagas/saga_gotenks",
        "sagas/saga_gotenks_ssj3",
        "sagas/saga_guldo",
        "sagas/saga_hirudegarn",
        "sagas/saga_janemba_fat",
        "sagas/saga_jeice",
        "sagas/saga_kibito",
        "sagas/saga_kid_gohan",
        "sagas/saga_kid_trunks",
        "sagas/saga_kid_trunks_ssj",
        "sagas/saga_kidbuu",
        "sagas/saga_king_cold",
        "sagas/saga_metal_cooler_core",
        "sagas/saga_morosoldier",
        "sagas/saga_nappa",
        "sagas/saga_neiz",
        "sagas/saga_ozaru",
        "sagas/saga_paikuhan",
        "sagas/saga_paragus",
        "sagas/saga_piccolo",
        "sagas/saga_puipui",
        "sagas/saga_raditz",
        "sagas/saga_recoome",
        "sagas/saga_salza",
        "sagas/saga_shin",
        "sagas/saga_slug",
        "sagas/saga_slug_soldier",
        "sagas/saga_spopovitch",
        "sagas/saga_super_a13",
        "sagas/saga_super_janemba",
        "sagas/saga_superbuu",
        "sagas/saga_trunks_ssj",
        "sagas/saga_turles",
        "sagas/saga_vegeta",
        "sagas/saga_vegetto_base",
        "sagas/saga_vegetto_ssj",
        "sagas/saga_videl",
        "sagas/saga_yakon",
        "sagas/saga_yamcha",
        "sagas/saga_zangya",
        "sagas/saga_zarbon",
        "sagas/saga_zarbont1",
        "sagas/shadow_dummy")));

    /** True when {@code path} (the part after {@code dmz:}) names a real DragonMineZ geo in the jar. */
    public static boolean isValid(String path)
    {
        return path != null && GEOS.contains(path);
    }

    /** The full set, for command tab completion (prefixed with {@code dmz:} by the command). */
    public static Set<String> ids()
    {
        return GEOS;
    }
}
