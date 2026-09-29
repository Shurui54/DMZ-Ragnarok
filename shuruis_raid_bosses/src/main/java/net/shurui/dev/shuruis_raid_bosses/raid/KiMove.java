package net.shurui.dev.shuruis_raid_bosses.raid;

/**
 * One editable ki move for a raid boss. Round-trips to/from {@code TYPE:cooldown:size} tokens on a
 * {@link RaidBossDef}, applied via {@code DBSagasEntity.addKiSkill}. {@link #TYPES} mirrors
 * {@code DBSagasEntity.KiSkillType} in declaration order; the stored string is the enum constant name,
 * mapped back with {@code KiSkillType.valueOf}.
 */
public class KiMove {
    public static final String[] TYPES = {
            "KAMEHAMEHA", "GALICK_GUN", "MAKANKOSAPPO", "KI_LASER", "KI_EXPLOSION",
            "KI_BARRIER", "OOZARU_ROAR", "GENERIC_KI_WAVE", "OOZARU_BEAM", "KI_VOLLEY",
            "KI_SMALL", "BLUE_HURRICANE", "TRIPLE_LASER", "KIENZAN", "DEATH_BALL",
            "MASENKO", "BIG_BANG", "FINAL_FLASH", "MAJIN_CANDY", "KI_AIR_VOLLEY",
            "DOUBLE_SUNDAY"
    };

    public String type = "KI_LASER";
    public int cooldown = 100;   // ticks between uses
    public double size = 1.0;    // projectile scale

    public static KiMove fromToken(String token) {
        KiMove m = new KiMove();
        if (token == null || token.isBlank()) return m;
        String[] parts = token.split(":");
        if (parts.length >= 1 && !parts[0].isBlank()) m.type = parts[0].trim().toUpperCase();
        if (parts.length >= 2) m.cooldown = parseInt(parts[1], m.cooldown);
        if (parts.length >= 3) m.size = parseDouble(parts[2], m.size);
        return m;
    }

    public String toToken() {
        return type + ":" + cooldown + ":" + size;
    }

    public String summary() {
        return type + "  §7(cd " + cooldown + "t, size " + trim(size) + ")";
    }

    private static String trim(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return Long.toString((long) v);
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
}
