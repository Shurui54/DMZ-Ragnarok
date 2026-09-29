package net.shurui.dev.shuruis_raid_bosses.raid;

import java.util.Locale;

/**
 * One entry in a {@link RaidType#PARALLEL_QUEST} raid: an entity type spawned {@code count} times with
 * the per-enemy stats below. Pipe-delimited token (pipe not colon, because entity ids contain colons):
 * {@code entityType|count|health|legacyBattlePower|scale|meleeDamage|kiPower|wave|defense|rgModelId}.
 *
 * <p>A {@code 0} stat keeps the entity's own default. Slot 3 (battle power) is now DERIVED at spawn
 * (round(melee + ki power)); the slot survives only so old tokens still parse, written as 0, never
 * applied. {@code wave} groups enemies sequentially: wave 2 spawns once wave 1 is cleared, then the
 * optional boss finale.
 */
public class EnemyWave {
    public String entityType = "minecraft:zombie";
    public int count = 3;
    public double health = 0;       // 0 = keep the entity's own max health
    public double meleeDamage = 0;  // 0 = fall back to the def's melee damage, then the entity default
    public double kiPower = 0;      // 0 = fall back to the def's ki power, then the entity default
    public double defense = 0;      // 0 = fall back to the def's baseDefense (0 there = no mitigation)
    public double scale = 0;        // 0 = keep default (DMZ saga entities only)
    public int wave = 1;            // which sequential wave this entry belongs to (1 = first)

    /**
     * Which ragnarok NPC character a spawned {@code dmz_ragnarok:rgnpc} wears; blank leaves it alone.
     * The whole rgnpc cast shares ONE entity type, so {@link #entityType} alone can only reach the
     * default look, and this field selects the character. Ignored by every other entity type.
     */
    public String rgModelId = "";

    public EnemyWave() {}

    public static EnemyWave fromToken(String token) {
        EnemyWave w = new EnemyWave();
        if (token == null || token.isBlank()) return w;
        String[] p = token.split("\\|");
        if (p.length >= 1 && !p[0].isBlank())
            w.entityType = net.shurui.shuruisutilities.ragnarok.LegacyIds.normalize(p[0].trim());
        if (p.length >= 2) w.count = Math.max(1, parseInt(p[1], w.count));
        if (p.length >= 3) w.health = parseDouble(p[2], w.health);
        // p[3] = legacy battle power (ignored; BP is derived from melee + ki power at spawn)
        if (p.length >= 5) w.scale = parseDouble(p[4], w.scale);
        if (p.length >= 6) w.meleeDamage = parseDouble(p[5], w.meleeDamage);
        if (p.length >= 7) w.kiPower = parseDouble(p[6], w.kiPower);
        if (p.length >= 8) w.wave = Math.max(1, parseInt(p[7], w.wave));
        if (p.length >= 9) w.defense = parseDouble(p[8], w.defense);
        // slot 10, appended Aug 2026, kept last so older tokens have no p[9] and keep the blank default
        if (p.length >= 10) w.rgModelId = p[9].trim();
        return w;
    }

    public String toToken() {
        return entityType + "|" + count + "|" + health + "|0|" + scale + "|" + meleeDamage + "|" + kiPower
                + "|" + wave + "|" + defense + "|" + (rgModelId == null ? "" : rgModelId.trim());
    }

    public String summary() {
        String shortId = entityType.contains(":") ? entityType.substring(entityType.indexOf(':') + 1) : entityType;
        StringBuilder sb = new StringBuilder("§bW" + wave + " §f" + count + "§7x §e" + shortId);
        // character reads as the enemy's identity, so it sits next to the entity id, not in the stat bracket
        if (rgModelId != null && !rgModelId.isBlank()) sb.append(" §d").append(rgModelId);
        java.util.List<String> bits = new java.util.ArrayList<>();
        if (health > 0) bits.add(trim(health) + " HP");
        if (meleeDamage > 0) bits.add("M" + trim(meleeDamage));
        if (kiPower > 0) bits.add("K" + trim(kiPower));
        if (defense > 0) bits.add("D" + trim(defense));
        if (!bits.isEmpty()) sb.append(" §7(").append(String.join(", ", bits)).append(")");
        return sb.toString();
    }

    private static String trim(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return Long.toString((long) v);
        return Double.toString(v);
    }

    private static int parseInt(String s, int fb) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fb; }
    }

    private static double parseDouble(String s, double fb) {
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return fb; }
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
