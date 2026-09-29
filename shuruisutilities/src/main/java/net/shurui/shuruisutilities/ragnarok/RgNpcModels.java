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
 * {@code dmz_ragnarok:textures/entity/ragnarok/<texture>.png}. This is a build-time table generated
 * from the asset drop, not a runtime resource scan, so it is safe to consult on both sides (server for command
 * tab completion / validation, client for defensive fallback) without touching the resource manager.
 *
 * <p><b>Those files are no longer in the mod jar.</b> Apart from the single bundled fallback
 * ({@link #DEFAULT_ID}), they live in {@code <gamedir>/ShuruisUtilities/rgnpc/} on the server and are streamed
 * to each client on join ({@link RgNpcAssetServer}), the same arrangement the rank badges use, so the model pack
 * cannot be lifted by unzipping a downloaded jar. This table is deliberately unchanged by that: it still lists
 * every entry whether or not a given client has been sent the file, because what may be SPAWNED is a server
 * decision. Absence is handled at the point of drawing instead, by
 * {@code net.shurui.shuruisutilities.ragnarok.client.RgNpcFallback}, since GeckoLib throws rather than degrades
 * when a geo is missing.</p>
 *
 * <p>This table was fully rebuilt in August 2026 from a curated model pack the user assembled: the whole
 * previous rgnpc set was deleted and replaced. It holds 387 entries. Most come one per character
 * folder in the drop, where the entry id equals its geo id. The exception is a set of texture-only variants: a
 * handful of source folders shipped one geo with several skins (GT Goku, the 3/4 and 5 Star Shadow Dragons,
 * Cabba, DBS Broly), and each extra skin is its own character, so it gets its own entry that reuses the shared
 * geo id.
 * A later batch (August 2026) appended 115 more entries from a second model drop; these are key-gated because
 * they are premium content the user restricts to Shurui's Key holders. Their entry ids populate {@link #GATED},
 * and {@link #GATED} membership is exactly what {@link #availableIds(boolean)} filters on: gated ids stay in the
 * full table (they still resolve, render and persist) but are withheld from the command surface without the key.
 * The mechanism always allowed an entry id to differ from its geo id, so {@link #geoId(String)} is the only
 * value that may be turned into a geo resource location; the raw entry id must not. Every id is stored already
 * sanitised: lowercased with
 * every character outside {@code [a-z0-9._-]} kept as is, matching how the shipped asset files are installed (the
 * source folder names were mixed case and contained spaces, which would crash the render thread through
 * ResourceLocation). Apply {@link #sanitize(String)} to any id before turning it into a resource location.
 *
 * <p>Twenty-six models were once dropped for lacking a usable texture in the model drop. Twenty-five were
 * restored in August 2026 by pulling the correct skin from the mod each model came from (DragonBlockC for the
 * DB and Buu-saga characters, ninjinentities for Cymbal, Drum, Zamasu, Super 17 and the Movie 8 Broly), keeping
 * only a texture whose pixel size equals the geo's declared texture size or an exact HD multiple of it. The one
 * that stayed out is {@code moroold}: no Moro texture ships in any origin jar. Every entry in this table
 * therefore resolves to a geo file and a texture file that exist on disk and fit the model's UV map.
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

    /**
     * Batch 2 (August 2026) key-gated rows, kept in ONE place so the ENTRIES table and the GATED set can never
     * drift: each row is {entry id, geo id, texture base name}. {@link #build()} appends every row to ENTRIES in
     * this order, and {@link #buildGated()} takes column 0 of every row as the GATED membership. There is no second
     * hand-maintained id list. Declared before {@link #ENTRIES} on purpose: static fields initialise in
     * declaration order and build() reads this array. The {@code triodanger} row keeps its own texture base name on
     * purpose (bergamo.png is already occupied by a different shipped model), so it is taken verbatim.
     */
    private static final String[][] GATED_ENTRIES = {
        // Models that already ship a matching geo and texture as-is.
        { "aka", "aka", "aka" },
        { "angol", "angol", "angol" },
        { "cyclopianguard", "cyclopianguard", "cyclopianguard" },
        { "ebifurya", "ebifurya", "ebifurya" },
        { "freeza5damaged", "freeza5damaged", "freeza5damaged" },
        { "freeza6damaged", "freeza6damaged", "freeza6damaged" },
        { "fusion_mara", "fusion_mara", "fusion_mara" },
        { "general1", "general1", "vegeta1" },
        { "general2", "general2", "vegeta2" },
        { "general3", "general3", "vegeta3" },
        { "generalssj4", "generalssj4", "gogetassj4" },
        { "glnl", "glnl", "glnl" },
        { "gohanadult", "gohanadult", "gohanadult1" },
        { "gohanadultssj2", "gohanadultssj2", "gohanadult1ssj" },
        { "gohankid1", "gohankid1", "gohankid1" },
        { "gohankid2", "gohankid2", "gohankid2" },
        { "gohankidarmor", "gohankidarmor", "gohankidarmor" },
        { "gohanteen", "gohanteen", "gohanteen" },
        { "gohanteenssj2", "gohanteenssj2", "gohanteenssj2" },
        { "gokublackrose", "gokublackrose", "gokublackrose" },
        { "goldcoolermetalfifthform", "goldcoolermetalfifthform", "goldcoolermetalfifthform" },
        { "gotenks", "gotenks", "goten" },
        { "gotenksssj3", "gotenksssj3", "goten" },
        { "hetilierde", "hetilierde", "hetilierde" },
        { "js", "js", "js" },
        { "kanbassj3", "kanbassj3", "kanbassj3" },
        { "kishime", "kishime", "kishime" },
        { "kogu", "kogu", "kogu" },
        { "lierde", "lierde", "lierde" },
        { "lude", "lude", "lude" },
        { "majinduu", "majinduu", "majin_duu" },
        { "majinduussj3", "majinduussj3", "majin_duu" },
        { "majinkuu", "majinkuu", "majin_kuu" },
        { "majinsoldier", "majinsoldier", "majinsoldier1" },
        { "mask", "mask", "blockgoku" },
        { "maskssj", "maskssj", "daimagokussj" },
        { "misokatsun", "misokatsun", "misokatsun" },
        { "moah", "moah", "moah" },
        { "namekian", "namekian", "monaka" },
        { "nappaarmor", "nappaarmor", "nappa" },
        { "npcnormalarmor", "npcnormalarmor", "gokuarmor" },
        { "npcnormalcape", "npcnormalcape", "piccolodaimaoold" },
        { "oldkaioshin", "oldkaioshin", "eldkaioshin" },
        { "oozaru", "oozaru", "oozaru" },
        { "pessmonster1", "pessmonster1", "arbee" },
        { "pessmonster2", "pessmonster2", "kawazu" },
        { "pessmonster3", "pessmonster3", "budo" },
        { "triodanger", "triodanger", "triodanger" },
        { "trunks", "trunks", "gotrunks" },
        { "trunksarmor", "trunksarmor", "trunksfuturarmor" },
        { "trunksarmorssj", "trunksarmorssj", "trunksfuturarmorssj" },
        { "trunksarmorssjg3", "trunksarmorssjg3", "trunksfuturarmorssjg3" },
        { "trunksssj", "trunksssj", "gotrunksssj" },
        { "vegeta", "vegeta", "vegeta0" },
        { "vegetaarmor", "vegetaarmor", "vegeto" },
        { "vegetaarmordamaged", "vegetaarmordamaged", "vegeta0damaged" },
        { "vegetaoozaru", "vegetaoozaru", "vegetaoozaru" },
        { "vegetokisword", "vegetokisword", "vegeto" },
        { "xicor", "xicor", "xicor" },
        { "xicorssj", "xicorssj", "xicorssj" },
        // Models whose geo/texture files were renamed on install.
        { "angelawamo", "angelawamo", "angel_awamo" },
        { "angelcukatail", "angelcukatail", "angel_cukatail" },
        { "angelmartinu", "angelmartinu", "angel_martinu" },
        { "bandit1", "bandit1", "bandit1" },
        { "bearthief", "bearthief", "bear_thief" },
        { "bora", "bora", "bora" },
        { "brianne", "brianne", "brianne" },
        { "brolyssj", "brolyssj", "brolysuperwrath" },
        { "brolyzbase", "brolyzbase", "brolyzbase" },
        { "cheelai", "cheelai", "cheelai1" },
        { "dbc_cabba", "dbc_cabba", "dbc_cabba" },
        { "dbc_cymbal", "dbc_cymbal", "dbc_cymbal" },
        { "dbc_drum", "dbc_drum", "dbc_drum" },
        { "dbc_tambourine", "dbc_tambourine", "dbc_tambourine" },
        { "dbc_whis", "dbc_whis", "whis" },
        { "dbc_yamcha2", "dbc_yamcha2", "dbc_yamcha2" },
        { "dbc_zamasu", "dbc_zamasu", "dbc_zamasu" },
        { "dodoria", "dodoria", "dodoria" },
        { "godarak", "godarak", "god_arak" },
        { "godgiin", "godgiin", "god_giin" },
        { "godiwan", "godiwan", "god_iwan" },
        { "godliquiir", "godliquiir", "god_liquiir" },
        { "gogeta", "gogeta", "nj_gogeta" },
        { "gokussj", "gokussj", "nj_gokussj" },
        { "gokuui", "gokuui", "nj_gokuui" },
        { "hop", "hop", "hop" },
        { "katopesla", "katopesla", "katopesla" },
        { "kefla", "kefla", "kefla" },
        { "keflassj", "keflassj", "kefla_ssj" },
        { "krillin", "krillin", "krillin" },
        { "kunshi", "kunshi", "kunshi" },
        { "lemo", "lemo", "lemo" },
        { "masterroshi", "masterroshi", "master_roshi" },
        { "officerblack", "officerblack", "officer_black_a" },
        // Officer Black's battle robot, the same character in his mecha. Its own geo, not a reskin of the
        // unsuited model, so it carries a geo id of its own exactly like the Mercenary Tao pair below.
        { "officerblack2", "officerblack2", "officer_black_mecha" },
        { "oolong", "oolong", "oolong" },
        { "pamput", "pamput", "pamput" },
        { "piccolo2", "piccolo2", "piccolo2" },
        { "piccolo3", "piccolo3", "piccolo3" },
        { "piccolo4", "piccolo4", "piccolo4" },
        { "roshi_super", "roshi_super", "roshi_super" },
        { "shisami", "shisami", "shisami" },
        { "sorrel", "sorrel", "sorrel" },
        { "tambourine", "tambourine", "tambourine" },
        { "tien", "tien", "tien_shinhan" },
        { "tigerbandit", "tigerbandit", "tiger_bandit" },
        { "vados", "vados", "vados" },
        { "vegetamajin", "vegetamajin", "nj_vegetamajin" },
        { "vegeto", "vegeto", "nj_vegeto" },
        { "yajirobe", "yajirobe", "yajirobe" },
        { "yamcha", "yamcha", "dbc_yamcha1" },
        { "zarbon", "zarbon", "zarbon1" },
        { "zeno", "zeno", "zeno" },
        // Mercenary Tao pair.
        { "mercenarytao", "mercenarytao", "mercenary_tao" },
        { "mercenarytao2", "mercenarytao2", "mercenary_tao_cyborg_a" },
    };

    // entry id -> (geo id, texture base name), preserving insertion order for stable tab completion.
    private static final Map<String, Entry> ENTRIES = build();

    /**
     * Legacy entry-id remap: PRE-RENAME saved id -> current id. Entry ids are persisted verbatim in the
     * {@code su_rgnpc_model} NBT string on every spawned NPC. This map is consulted by {@link #resolveId(String)}
     * whenever a looked-up id is absent from the live table, so a renamed id can keep an already-spawned NPC on its
     * exact model.
     *
     * <p>It is intentionally EMPTY as of the August 2026 full pack replacement: the previous model set was retired
     * wholesale, and the user accepted that any NPC saved with a now-removed id falls back to the default model
     * rather than being repointed. Pre-existing NPCs whose saved id happens to match a surviving id need no row and
     * keep their model automatically. New rows may be added here in future if a specific rename must be preserved.
     */
    private static final Map<String, String> LEGACY_IDS = buildLegacy();

    /**
     * Entry ids that require the Ragnarok Key to select or spawn. This is a SEPARATE membership
     * query layered over the full {@link #ENTRIES} table, never a removal from it: rendering, NBT persistence and
     * the planet garrison rosters all read the full table, so a gated id still resolves, still renders, and still
     * survives a load on a keyed server. The gate only decides whether the command surface will hand out or accept
     * the id in the first place (see {@link #isGated(String)} and {@link #availableIds(boolean)}), enforced with
     * {@link net.shurui.shuruisutilities.KeyGate#present()} at the server-side command surface.
     *
     * <p>Populated from {@link #GATED_ENTRIES} (the 115 August 2026 batch-2 rows). It is derived from the same
     * rows {@link #build()} appends to {@link #ENTRIES}, so the two can never drift; every gated id is guaranteed
     * to exist in the table. Only these batch-2 ids are gated; the original curated set stays ungated.
     */
    private static final Set<String> GATED = buildGated();

    private RgNpcModels() {
    }

    /** All entry ids, in manifest order, for tab completion and browsing. Full table, gate-agnostic. */
    public static Set<String> ids() {
        return Collections.unmodifiableSet(ENTRIES.keySet());
    }

    /** True when {@code id} names a key-locked entry. Membership check only; the full table still contains it. */
    public static boolean isGated(String id) {
        return id != null && GATED.contains(id);
    }

    /**
     * Entry ids the command surface may offer / accept for the current key state, in manifest order.
     * {@code unlocked == true} (Shurui's Key present) returns the FULL id set; {@code unlocked == false} returns
     * the same order with the {@link #GATED gated} ids filtered out. This is the ONLY place ids are filtered by
     * key state; every other accessor stays over the full table so already-spawned NPCs are never blanked.
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

    /** True when {@code id} names an installed entry. */
    public static boolean isValidId(String id) {
        return id != null && ENTRIES.containsKey(id);
    }

    /**
     * Resolve a possibly-legacy entry id to a live one. If {@code id} is already a live entry it is returned
     * unchanged; otherwise a pre-rename id is looked up in {@link #LEGACY_IDS} and, if found, its current id is
     * returned. Returns null when the id resolves to nothing live (the caller then falls back to the default).
     */
    public static String resolveId(String id) {
        if (id == null) {
            return null;
        }
        if (ENTRIES.containsKey(id)) {
            return id;
        }
        String mapped = LEGACY_IDS.get(id);
        if (mapped != null && ENTRIES.containsKey(mapped)) {
            return mapped;
        }
        return null;
    }

    /**
     * The geo base name (no ".geo.json") an entry loads, or the default entry's geo if the id is unknown. Never
     * null. This is the value the client turns into a geo resource location, NOT the entry id, because a variant
     * loads a geo whose name differs from its id.
     */
    public static String geoId(String id) {
        Entry e = ENTRIES.get(id);
        if (e != null) {
            return e.geoId;
        }
        return ENTRIES.get(DEFAULT_ID).geoId;
    }

    /**
     * The default texture base name (no ".png") an entry paints, or the default entry's texture if the id is
     * unknown. Never null. An unknown id therefore falls back to a known good texture rather than a missing one.
     */
    public static String defaultTexture(String id) {
        Entry e = ENTRIES.get(id);
        if (e != null) {
            return e.texture;
        }
        return ENTRIES.get(DEFAULT_ID).texture;
    }

    /**
     * The one true sanitisation rule shared with the asset side: lowercase, then replace every character
     * outside {@code [a-z0-9._-]} with {@code _}. A null / blank input yields the empty string (the caller is
     * responsible for rejecting or defaulting that). This is what makes a synced or user supplied id safe to
     * push into a {@link net.minecraft.resources.ResourceLocation} path without throwing.
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
        Map<String, Entry> m = new LinkedHashMap<>(512);
        put(m, "bdkssj4", "bdkssj4", "bdkssj4");
        put(m, "bdkssjb3ninjin", "bdkssjb3ninjin", "bdkssjb3ninjin");
        put(m, "tournamentannouncer", "tournamentannouncer", "tournamentannouncer");
        put(m, "chiaotzu1", "chiaotzu1", "chiaotzu1");
        // Restored: DBC's second Chiaotzu skin (Entity Chiaotzu2 -> chiaotzu_b), its own 64x64 DBC texture.
        put(m, "chiaotzu", "chiaotzu", "chiaotzu");
        put(m, "gokukid1", "gokukid1", "gokukid1");
        put(m, "gokukid2", "gokukid2", "gokukid2");
        put(m, "krillinkid1", "krillinkid1", "krillinkid1");
        put(m, "krillinkid2", "krillinkid2", "krillinkid2");
        put(m, "roshi", "roshi", "roshi");
        put(m, "roshibuff", "roshibuff", "roshibuff");
        put(m, "yamcha1", "yamcha1", "yamcha1");
        put(m, "android8", "android8", "android8");
        put(m, "bacterian", "bacterian", "bacterian");
        put(m, "buyon", "buyon", "buyon");
        put(m, "colonelsilver", "colonelsilver", "colonelsilver");
        put(m, "colonelviolet", "colonelviolet", "colonelviolet");
        put(m, "commanderred", "commanderred", "commanderred");
        put(m, "devil", "devil", "devil");
        put(m, "generalblue", "generalblue", "generalblue");
        put(m, "generalwhite", "generalwhite", "generalwhite");
        put(m, "giran", "giran", "giran");
        put(m, "grandpagohan", "grandpagohan", "grandpagohan");
        put(m, "jackiechun", "jackiechun", "jackiechun");
        // Restored: DBC Jackie Chun variants with their own DBC skins (max power / no shirt).
        put(m, "jackiechun2", "jackiechun2", "jackiechun2");
        put(m, "jackiechun3", "jackiechun3", "jackiechun3");
        put(m, "kingchappa", "kingchappa", "kingchappa");
        put(m, "kingpiccolo", "kingpiccolo", "kingpiccolo");
        // Restored: DBC King Piccolo second form (Entity KingPiccolo2 -> king_piccolo_young).
        put(m, "kingpiccolo2", "kingpiccolo2", "kingpiccolo2");
        put(m, "launch", "launch", "launch");
        put(m, "mai", "mai", "mai");
        put(m, "maimecha", "maimecha", "maimecha");
        put(m, "majormetallitron", "majormetallitron", "majormetallitron");
        put(m, "manwolf", "manwolf", "manwolf");
        put(m, "mastershen", "mastershen", "mastershen");
        put(m, "nam", "nam", "nam");
        put(m, "ninjamurasaki", "ninjamurasaki", "ninjamurasaki");
        // Restored: Red Ribbon Army rank and file. DBC labels RedRibbonSoldier the Gunner and
        // RedRibbonSoldier3 the Bazooka (opposite of the b-suffix reading), so bind them accordingly.
        put(m, "redribbonsoldiergunner", "redribbonsoldiergunner", "redribbonsoldiergunner");
        put(m, "redribbonsoldierbazooka", "redribbonsoldierbazooka", "redribbonsoldierbazooka");
        put(m, "piano", "piano", "piano");
        // Restored: King Piccolo's other spawn, from ninjinentities 1.3 at the geo's 90x90 texture size.
        put(m, "cymbal", "cymbal", "cymbal");
        put(m, "drum", "drum", "drum");
        put(m, "pilaf", "pilaf", "pilaf");
        put(m, "pilafmecha", "pilafmecha", "pilafmecha");
        // Restored: the combined Pilaf gang mecha, its own 512x256 DBC texture.
        put(m, "pilafmechacombined", "pilafmechacombined", "pilafmechacombined");
        put(m, "puar", "puar", "puar");
        put(m, "shu", "shu", "shu");
        put(m, "shumecha", "shumecha", "shumecha");
        put(m, "upa", "upa", "upa");
        put(m, "vampire", "vampire", "vampire");
        put(m, "daimagoku", "daimagoku", "daimagoku");
        put(m, "daimagokussj", "daimagokussj", "daimagokussj");
        put(m, "daimagokussj2", "daimagokussj2", "daimagokussj2");
        put(m, "daimagokussj3", "daimagokussj3", "daimagokussj3");
        put(m, "daimagokussj4", "daimagokussj4", "daimagokussj4");
        put(m, "daimamajinduu", "daimamajinduu", "daimamajinduu");
        put(m, "daimamajinduussj3", "daimamajinduussj3", "daimamajinduussj3");
        put(m, "daimamajinkuu", "daimamajinkuu", "daimamajinkuu");
        put(m, "daimaporunga", "daimaporunga", "daimaporunga");
        put(m, "daimavegetassj3", "daimavegetassj3", "daimavegetassj3");
        put(m, "gomahthirdeye", "gomahthirdeye", "gomahthirdeye");
        put(m, "gomahthirdeyegigantic", "gomahthirdeyegigantic", "gomahthirdeyegigantic");
        put(m, "kaioshinchild", "kaioshinchild", "kaioshinchild");
        put(m, "tamagami_1", "tamagami_1", "tamagami_1");
        put(m, "tamagami_2", "tamagami_2", "tamagami_2");
        put(m, "tamagami_3", "tamagami_3", "tamagami_3");
        put(m, "bb", "bb", "bb");
        put(m, "bb2", "bb2", "bb2");
        put(m, "bbgoldoozaru", "bbgoldoozaru", "bbgoldoozaru");
        put(m, "bbssj", "bbssj", "bbssj");
        put(m, "bbssj2", "bbssj2", "bbssj2");
        put(m, "goku", "goku", "goku");
        // GT Goku variants: one shared geo, distinct skins (texture-only variants).
        put(m, "gtgokuchild", "goku", "gtgokuchild");
        put(m, "gtgokussj", "goku", "gtgokussj");
        put(m, "gtgokussj2", "goku", "gtgokussj2");
        put(m, "gtgokussj3", "goku", "gtgokussj3");
        put(m, "ledgic", "ledgic", "ledgic");
        put(m, "omega", "omega", "omega");
        put(m, "omega2", "omega2", "omega2");
        // Restored: Super Android 17 (GT), painted with ninjinentities' HD Super17 skin (1280x1280, x20 of 64x64).
        put(m, "supera17", "supera17", "supera17");
        put(m, "2stars", "2stars", "2stars");
        put(m, "3or4stars", "3or4stars", "3or4stars");
        // 3/4 Star Shadow Dragon skins sharing the 3or4stars geo (texture-only variants).
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
        put(m, "blockgohanultimate", "blockgohanultimate", "blockgohanultimate");
        put(m, "blockgoku", "blockgoku", "blockgoku");
        put(m, "blockgokussjr", "blockgokussjr", "blockgokussjr");
        put(m, "blockgokussjr3", "blockgokussjr3", "blockgokussjr3");
        put(m, "trunksfutursuper", "trunksfutursuper", "trunksfutursuper");
        put(m, "trunksfutursuperssj", "trunksfutursuperssj", "trunksfutursuperssj");
        put(m, "trunksfutursuperssj2", "trunksfutursuperssj2", "trunksfutursuperssj2");
        put(m, "trunksfutursuperssjg3", "trunksfutursuperssjg3", "trunksfutursuperssjg3");
        put(m, "trunksfutursuperssjrage", "trunksfutursuperssjrage", "trunksfutursuperssjrage");
        // Restored: base Zamasu, ninjinentities 1.3 skin at the geo's 64x32 texture size.
        put(m, "zamasu", "zamasu", "zamasu");
        put(m, "zamasu_fused", "zamasu_fused", "zamasu_fused");
        put(m, "zamasu_fused2", "zamasu_fused2", "zamasu_fused2");
        put(m, "zamasumerged", "zamasumerged", "zamasumerged");
        // Restored: Vegeta Copy and its blue form (Entity VegetaCopy / VegetaCopyBlue), own DBC skins.
        put(m, "vegetacopy", "vegetacopy", "vegetacopy");
        put(m, "vegetacopyblue", "vegetacopyblue", "vegetacopyblue");
        put(m, "galacticpatrolsoldier", "galacticpatrolsoldier", "galacticpatrolsoldier");
        put(m, "merus", "merus", "merus");
        put(m, "whis", "whis", "whis");
        put(m, "beerus", "beerus", "beerus");
        put(m, "sorbet", "sorbet", "sorbet");
        put(m, "tagoma", "tagoma", "tagoma");
        put(m, "dbsbrolybuff", "dbsbrolybuff", "dbsbrolybuff");
        put(m, "dbsbrolylegendary", "dbsbrolylegendary", "dbsbrolylegendary");
        put(m, "dbsbrolynormal", "dbsbrolynormal", "dbsbrolynormal");
        put(m, "dbsbroly2", "dbsbrolynormal", "dbsbroly2");
        put(m, "dbsparagus", "dbsparagus", "dbsparagus");
        put(m, "cellmax", "cellmax", "cellmax");
        put(m, "cellperfectmax", "cellperfectmax", "cellperfectmax");
        put(m, "gamma1", "gamma1", "gamma1");
        put(m, "gamma2", "gamma2", "gamma2");
        put(m, "monaka", "monaka", "monaka");
        put(m, "beerusmonaka", "beerusmonaka", "beerusmonaka");
        put(m, "beerusmonaka2", "beerusmonaka2", "beerusmonaka2");
        put(m, "beerusmonaka3", "beerusmonaka3", "beerusmonaka3");
        put(m, "angelkusu", "angelkusu", "angelkusu");
        put(m, "godrumsshi", "godrumsshi", "godrumsshi");
        put(m, "murichim", "murichim", "murichim");
        put(m, "napapa", "napapa", "napapa");
        put(m, "obni", "obni", "obni");
        put(m, "rylibeu", "rylibeu", "rylibeu");
        put(m, "cocotte", "cocotte", "cocotte");
        put(m, "kahseral", "kahseral", "kahseral");
        put(m, "kettle", "kettle", "kettle");
        put(m, "tupper", "tupper", "tupper");
        put(m, "zoire", "zoire", "zoire");
        put(m, "angelmarcarita", "angelmarcarita", "angelmarcarita");
        put(m, "biarra", "biarra", "biarra");
        put(m, "dyspo", "dyspo", "dyspo");
        put(m, "godbelmod", "godbelmod", "godbelmod");
        put(m, "jiren", "jiren", "jiren");
        put(m, "jiren_full_power", "jiren_full_power", "jiren_full_power");
        put(m, "toppo", "toppo", "toppo");
        put(m, "toppo_god", "toppo_god", "toppo_god");
        put(m, "angelsour", "angelsour", "angelsour");
        put(m, "godheles", "godheles", "godheles");
        put(m, "kakunsa", "kakunsa", "kakunsa");
        put(m, "roasie", "roasie", "roasie");
        put(m, "angelcamparri", "angelcamparri", "angelcamparri");
        put(m, "aniraza", "aniraza", "aniraza");
        put(m, "aniraza2", "aniraza2", "aniraza2");
        put(m, "godmosco", "godmosco", "godmosco");
        put(m, "koitsukai", "koitsukai", "koitsukai");
        put(m, "narirama", "narirama", "narirama");
        put(m, "panchia", "panchia", "panchia");
        put(m, "paparoni", "paparoni", "paparoni");
        put(m, "angelcognac", "angelcognac", "angelcognac");
        put(m, "dercori", "dercori", "dercori");
        put(m, "ganos", "ganos", "ganos");
        put(m, "godquitela", "godquitela", "godquitela");
        put(m, "majora", "majora", "majora");
        put(m, "shosa", "shosa", "shosa");
        put(m, "hit", "hit", "hit");
        put(m, "saonel", "saonel", "saonel");
        put(m, "angelkorn", "angelkorn", "angelkorn");
        put(m, "botamo", "botamo", "botamo");
        put(m, "cabba", "cabba", "cabba");
        put(m, "cabbassj", "cabba", "cabbassj");
        put(m, "caulifla", "caulifla", "caulifla");
        put(m, "cauliflassj", "cauliflassj", "cauliflassj");
        put(m, "champa", "champa", "champa");
        put(m, "kale", "kale", "kale");
        put(m, "kalessj", "kalessj", "kalessj");
        put(m, "magetta", "magetta", "magetta");
        put(m, "angelmohito", "angelmohito", "angelmohito");
        put(m, "basil", "basil", "basil");
        // Restored: the other two Trio de Dangers wolves (Universe 9), own DBC skins.
        put(m, "bergamo", "bergamo", "bergamo");
        put(m, "lavender", "lavender", "lavender");
        put(m, "godsidra", "godsidra", "godsidra");
        put(m, "kaioshin", "kaioshin", "kaioshin");
        put(m, "kibito", "kibito", "kibito");
        put(m, "yakon", "yakon", "yakon");
        // Restored: the Majin Buu forms, each bound to its own DBC skin (Entity Buu* -> *MajinBuu*).
        put(m, "buufat", "buufat", "buufat");
        put(m, "buukid", "buukid", "buukid");
        put(m, "buuevil", "buuevil", "buuevil");
        put(m, "buusuper", "buusuper", "buusuper");
        put(m, "buusuper_buffed", "buusuper_buffed", "buusuper_buffed");
        put(m, "buusuper_fusion", "buusuper_fusion", "buusuper_fusion");
        put(m, "buusuper_piccolo", "buusuper_piccolo", "buusuper_piccolo");
        put(m, "buusuper_ultimate", "buusuper_ultimate", "buusuper_ultimate");
        put(m, "garlicjr", "garlicjr", "garlicjr");
        put(m, "garlicjrsuper", "garlicjrsuper", "garlicjrsuper");
        put(m, "ginger", "ginger", "ginger");
        put(m, "nicky", "nicky", "nicky");
        put(m, "sansho", "sansho", "sansho");
        put(m, "brolyzlssj", "brolyzlssj", "brolyzlssj");
        put(m, "brolyzbio", "brolyzbio", "brolyzbio");
        // Restored: the Movie 8 Legendary Super Saiyan Broly model, ninjinentities 1.3 LSSJ skin (128x64).
        put(m, "broly", "broly", "broly");
        put(m, "janemba", "janemba", "janemba");
        put(m, "janembasuper", "janembasuper", "janembasuper");
        put(m, "pikkon", "pikkon", "pikkon");
        put(m, "officeogre", "officeogre", "officeogre");
        // Restored: Fusion Reborn office ogre variant (Entity OfficeOgre2 -> office_ogre_b).
        put(m, "officeogre2", "officeogre2", "officeogre2");
        put(m, "hirudegarnbottom", "hirudegarnbottom", "hirudegarnbottom");
        put(m, "hirudegarnpost", "hirudegarnpost", "hirudegarnpost");
        put(m, "hirudegarnpre", "hirudegarnpre", "hirudegarnpre");
        put(m, "hirudegarnupper", "hirudegarnupper", "hirudegarnupper");
        put(m, "hoi", "hoi", "hoi");
        put(m, "biowarrior1", "biowarrior1", "biowarrior1");
        put(m, "biowarrior2", "biowarrior2", "biowarrior2");
        put(m, "biowarrior3", "biowarrior3", "biowarrior3");
        put(m, "biowarrior4", "biowarrior4", "biowarrior4");
        put(m, "biowarrior5", "biowarrior5", "biowarrior5");
        put(m, "biowarrior6", "biowarrior6", "biowarrior6");
        put(m, "biomen", "biomen", "biomen");
        put(m, "drwheelo", "drwheelo", "drwheelo");
        put(m, "amond", "amond", "amond");
        put(m, "cacao", "cacao", "cacao");
        put(m, "daiz", "daiz", "daiz");
        put(m, "rasin", "rasin", "rasin");
        put(m, "turles1", "turles1", "turles1");
        put(m, "turles2", "turles2", "turles2");
        put(m, "turles3", "turles3", "turles3");
        put(m, "rasinlakasei", "rasinlakasei", "rasinlakasei");
        put(m, "angila", "angila", "angila");
        put(m, "medamatcha", "medamatcha", "medamatcha");
        put(m, "medamatchaclone", "medamatchaclone", "medamatchaclone");
        put(m, "sluggiant", "sluggiant", "sluggiant");
        put(m, "slugold", "slugold", "slugold");
        put(m, "slugsoldier", "slugsoldier", "slugsoldier");
        put(m, "slugyoung", "slugyoung", "slugyoung");
        put(m, "wings", "wings", "wings");
        put(m, "zeeun", "zeeun", "zeeun");
        put(m, "cooler", "cooler", "cooler");
        put(m, "coolerfifthform", "coolerfifthform", "coolerfifthform");
        put(m, "dore", "dore", "dore");
        put(m, "neiz", "neiz", "neiz");
        put(m, "salza", "salza", "salza");
        put(m, "coolermetal", "coolermetal", "coolermetal");
        put(m, "coolermetaltrueform", "coolermetaltrueform", "coolermetaltrueform");
        put(m, "coolermetalrealform", "coolermetalrealform", "coolermetalrealform");
        put(m, "android13", "android13", "android13");
        put(m, "android13super", "android13super", "android13super");
        put(m, "android14", "android14", "android14");
        put(m, "android15", "android15", "android15");
        put(m, "brolyzssj", "brolyzssj", "brolyzssj");
        put(m, "paragus", "paragus", "paragus");
        put(m, "paragussoldier", "paragussoldier", "paragussoldier");
        put(m, "bido", "bido", "bido");
        put(m, "bojack", "bojack", "bojack");
        put(m, "bojacksuper", "bojacksuper", "bojacksuper");
        put(m, "bujin", "bujin", "bujin");
        put(m, "zangya", "zangya", "zangya");
        put(m, "bdkninjin", "bdkninjin", "bdkninjin");
        put(m, "abo", "abo", "abo");
        put(m, "kado", "kado", "kado");
        put(m, "abokado", "abokado", "abokado");
        put(m, "futuregohan", "futuregohan", "futuregohan");
        put(m, "futuregohan2", "futuregohan2", "futuregohan2");
        put(m, "futuregohan2ssj", "futuregohan2ssj", "futuregohan2ssj");
        put(m, "futuregohanssj", "futuregohanssj", "futuregohanssj");
        put(m, "hatchiyack", "hatchiyack", "hatchiyack");
        put(m, "hatchiyackgiant", "hatchiyackgiant", "hatchiyackgiant");
        put(m, "hatchiyacksuper", "hatchiyacksuper", "hatchiyacksuper");
        put(m, "bdkssjninjin", "bdkssjninjin", "bdkssjninjin");
        put(m, "chilled", "chilled", "chilled");
        put(m, "berryblue", "berryblue", "berryblue");
        // Generic saiyan grunts for the planet garrison SAIYAN family: DragonMineZ's three Frieza-Force armoured
        // soldier assets (saga_friezasoldier01..03), copied into this tree with the human race's five tail bones
        // appended so they read as saiyans. Each keeps its own coupled texture. See PlanetGarrisonRoster.
        put(m, "saiyan_grunt_1", "saiyan_grunt_1", "saiyan_grunt_1");
        put(m, "saiyan_grunt_2", "saiyan_grunt_2", "saiyan_grunt_2");
        put(m, "saiyan_grunt_3", "saiyan_grunt_3", "saiyan_grunt_3");
        // Batch 2 (August 2026): 114 key-gated models appended after the curated set. Sourced from GATED_ENTRIES so
        // the GATED membership set is derived from the exact same rows and cannot drift from this table.
        for (String[] row : GATED_ENTRIES) {
            put(m, row[0], row[1], row[2]);
        }
        return m;
    }

    private static Map<String, String> buildLegacy() {
        // Intentionally empty: the August 2026 pack replacement dropped all prior legacy remaps (see the
        // LEGACY_IDS javadoc). Add a row here only when a future rename must keep already-spawned NPCs on a model.
        return new LinkedHashMap<>(8);
    }

    private static Set<String> buildGated() {
        // Derived from GATED_ENTRIES (column 0), the same rows build() appends to ENTRIES, so the key-locked set can
        // never drift from the table. Each id therefore exists in ENTRIES by construction; the gate only decides
        // whether the command surface offers or accepts it (see the GATED javadoc), it never removes it.
        Set<String> s = new HashSet<>(256);
        for (String[] row : GATED_ENTRIES) {
            s.add(row[0]);
        }
        return s;
    }

    private static void put(Map<String, Entry> m, String id, String geoId, String texture) {
        m.put(id, new Entry(geoId, texture));
    }
}
