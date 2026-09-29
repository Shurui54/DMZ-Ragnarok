package net.shurui.dev.shuruis_dmz_dungeons.block;

import net.minecraft.util.RandomSource;

import java.util.Locale;

// one ki blast the spawner hands to a spawned sdu:dmz_fighter. round-trips the flat token
// TYPE:cdMin:cdMax:size:colorMain stored on SpawnerConfig. roll() picks a concrete cd in [cdMin,cdMax] and
// emits a per-spawn TYPE:cd:size:colorMain for the SduKiMovesCsv NBT key SduDmzFighter reads (maps back via
// KiSkillType.valueOf + DMZ's colour-aware addKiSkill). heads up: DMZ internally scales the entered cooldown
// (~x2, times an AI-tier factor); the min/max variance still survives.
public class KiMoveEntry {

    // every DBSagasEntity.KiSkillType in enum order (DMZ 2.1.3). stored string = enum constant name.
    // DOUBLE_SUNDAY is the 2.1.3-new one at the end, keep it last to match declaration order.
    public static final String[] TYPES = {
            "KAMEHAMEHA", "GALICK_GUN", "MAKANKOSAPPO", "KI_LASER", "KI_EXPLOSION",
            "KI_BARRIER", "OOZARU_ROAR", "GENERIC_KI_WAVE", "OOZARU_BEAM", "KI_VOLLEY",
            "KI_SMALL", "BLUE_HURRICANE", "TRIPLE_LASER", "KIENZAN", "DEATH_BALL",
            "MASENKO", "BIG_BANG", "FINAL_FLASH", "MAJIN_CANDY", "KI_AIR_VOLLEY", "DOUBLE_SUNDAY"
    };

    public String type = "KI_LASER";
    public int cdMin = 100;      // interval range lower bound (ticks between uses)
    public int cdMax = 100;      // interval range upper bound
    public double size = 1.0;    // projectile scale
    public int colorMain = 0xFFFFFF; // 0xRRGGBB main colour

    public static KiMoveEntry fromToken(String token) {
        KiMoveEntry m = new KiMoveEntry();
        if (token == null || token.isBlank()) {
            return m;
        }
        String[] p = token.split(":");
        if (p.length >= 1 && !p[0].isBlank()) {
            m.type = p[0].trim().toUpperCase(Locale.ROOT);
        }
        if (p.length >= 2) {
            m.cdMin = parseInt(p[1], m.cdMin);
        }
        if (p.length >= 3) {
            m.cdMax = parseInt(p[2], m.cdMax);
        }
        if (p.length >= 4) {
            m.size = parseDouble(p[3], m.size);
        }
        if (p.length >= 5) {
            m.colorMain = parseColor(p[4], m.colorMain);
        }
        if (m.cdMax < m.cdMin) {
            int t = m.cdMin;
            m.cdMin = m.cdMax;
            m.cdMax = t;
        }
        return m;
    }

    // TYPE:cdMin:cdMax:size:colorMain
    public String toToken() {
        return type + ":" + cdMin + ":" + cdMax + ":" + trim(size) + ":" + (colorMain & 0xFFFFFF);
    }

    // per-spawn token TYPE:cd:size:colorMain, cd random in [cdMin,cdMax]. spawner comma-joins these into SduKiMovesCsv.
    public String roll(RandomSource random) {
        int lo = Math.max(1, Math.min(cdMin, cdMax));
        int hi = Math.max(1, Math.max(cdMin, cdMax));
        int cd = lo >= hi ? lo : lo + random.nextInt(hi - lo + 1);
        return type + ":" + cd + ":" + trim(size) + ":" + (colorMain & 0xFFFFFF);
    }

    // interval range token for the summary line: "100t" or "100-200t"
    public String rangeLabel() {
        return cdMin == cdMax ? (cdMin + "t") : (cdMin + "-" + cdMax + "t");
    }

    // size shown without a trailing ".0" when whole
    public String sizeLabel() {
        return trim(size);
    }

    // six-digit upper-case hex of the main colour (no leading #)
    public String hex6() {
        return String.format(Locale.ROOT, "%06X", colorMain & 0xFFFFFF);
    }

    public String colorHex() {
        return String.format(Locale.ROOT, "#%06X", colorMain & 0xFFFFFF);
    }

    // blank/invalid -> white
    public void setColorHex(String hex) {
        this.colorMain = parseColor(hex, 0xFFFFFF);
    }

    private static String trim(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private static int parseInt(String s, int fb) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fb;
        }
    }

    private static double parseDouble(String s, double fb) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fb;
        }
    }

    // accepts plain decimal int or "#RRGGBB"/"RRGGBB" hex; falls back to fb
    private static int parseColor(String s, int fb) {
        if (s == null) {
            return fb;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return fb;
        }
        try {
            if (t.startsWith("#")) {
                return Integer.parseInt(t.substring(1), 16) & 0xFFFFFF;
            }
            if (t.length() == 6 && t.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                return Integer.parseInt(t, 16) & 0xFFFFFF;
            }
            return Integer.parseInt(t) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fb;
        }
    }
}
