package net.shurui.dev.shuruis_raid_bosses.raid;

import java.util.ArrayList;
import java.util.List;

/**
 * One stage of a {@link RaidType#BOSS_RUSH} raid: a boss spawned after the previous one dies, following a
 * per-stage {@code delaySeconds} pause. Each stage carries its own entity, name, health, DMZ stats and
 * ki-move pool, so a rush can string together completely different bosses.
 *
 * <p>Pipe-delimited token:
 * {@code entityType|bossName|health|legacyBattlePower|scale|delaySeconds|kiCsv|meleeDamage|kiPower|defense|rgModelId},
 * {@code kiCsv} being comma-separated {@link KiMove} tokens. A {@code 0} stat keeps the entity/def
 * default; {@code health 0} falls back to the participant-scaled base health. Slot 3 (battle power) is
 * now DERIVED at spawn (round(melee + ki power)); kept only so old tokens parse, written 0, never applied.
 */
public class BossStage {
    public String entityType = "minecraft:warden";
    public String bossName = "Boss";
    public double health = 0;       // 0 = use the raid's scaled base health
    public double meleeDamage = 0;  // 0 = fall back to the def's melee damage, then the entity default
    public double kiPower = 0;      // 0 = fall back to the def's ki power, then the entity default
    public double defense = 0;      // 0 = fall back to the def's baseDefense (0 there = no mitigation)
    public double scale = 0;        // 0 = keep default
    public int delaySeconds = 10;   // pause before this stage spawns
    public final List<String> kiMoves = new ArrayList<>();

    /**
     * Which ragnarok NPC character this stage's boss wears when its entity is {@code dmz_ragnarok:rgnpc};
     * blank leaves it alone. Per STAGE, not per raid, since a rush strings together different bosses and
     * the def's own {@code bossModelId} says nothing about stage three. The whole rgnpc cast shares one
     * entity type, so this is the only field that tells stages apart.
     */
    public String rgModelId = "";

    /**
     * Allies spawned WHEN THIS STAGE BEGINS, as {@link AllySpawn} tokens. Per stage, so different phases
     * bring different fighters. Allies from earlier stages are NOT removed at a new stage; they fight on
     * until they die or the encounter ends, so a stage list is additive.
     */
    public final List<String> allies = new ArrayList<>();

    /**
     * When true, every live ally becomes an enemy and every live enemy an ally when this stage begins. A
     * runtime move, not a respawn: entities keep health, position and current damage, only side, glow and
     * targeting change. Respawning would reset a boss to full health mid fight and throw away the player's
     * damage.
     *
     * <p>Ordering inside the stage is FIXED and matters: swap first, then the stage's boss spawns, then its
     * allies, so a swapped-in ally is not swapped back out and the stage's own boss is not caught by it.
     */
    public boolean swapSides = false;

    public BossStage() {}

    public static BossStage fromToken(String token) {
        BossStage s = new BossStage();
        if (token == null || token.isBlank()) return s;
        String[] p = token.split("\\|", -1);
        if (p.length >= 1 && !p[0].isBlank())
            s.entityType = net.shurui.shuruisutilities.ragnarok.LegacyIds.normalize(p[0].trim());
        if (p.length >= 2 && !p[1].isBlank()) s.bossName = p[1];
        if (p.length >= 3) s.health = parseDouble(p[2], s.health);
        // p[3] = legacy battle power (ignored; BP is derived from melee + ki power at spawn)
        if (p.length >= 5) s.scale = parseDouble(p[4], s.scale);
        if (p.length >= 6) s.delaySeconds = Math.max(0, parseInt(p[5], s.delaySeconds));
        if (p.length >= 7 && !p[6].isBlank()) {
            for (String k : p[6].split(",")) if (!k.isBlank()) s.kiMoves.add(k.trim());
        }
        if (p.length >= 8) s.meleeDamage = parseDouble(p[7], s.meleeDamage);
        if (p.length >= 9) s.kiPower = parseDouble(p[8], s.kiPower);
        if (p.length >= 10) s.defense = parseDouble(p[9], s.defense);
        // slot 11, appended Aug 2026, last so older tokens have no p[10] and keep the blank default
        if (p.length >= 11) s.rgModelId = p[10].trim();
        // slots 12/13 for the ally / phase-swap work, append-only: an older stage has no p[11]/p[12] and
        // reads back with no allies and no swap
        if (p.length >= 12 && !p[11].isBlank()) {
            for (String a : p[11].split(String.valueOf(AllySpawn.NESTED_ENTRY_SEP))) {
                if (!a.isBlank()) s.allies.add(a.replace(AllySpawn.NESTED_FIELD_SEP, '|'));
            }
        }
        if (p.length >= 13) s.swapSides = Boolean.parseBoolean(p[12].trim());
        return s;
    }

    public String toToken() {
        String kiCsv = String.join(",", kiMoves);
        String name = bossName == null ? "" : bossName.replace('|', ' ');
        // ally tokens are pipe-delimited too, so written with AllySpawn's nested separator instead; a raw
        // pipe would split this token apart and corrupt every field after slot 12
        StringBuilder allyCsv = new StringBuilder();
        for (String a : allies) {
            if (a == null || a.isBlank()) continue;
            if (allyCsv.length() > 0) allyCsv.append(AllySpawn.NESTED_ENTRY_SEP);
            allyCsv.append(a.replace('|', AllySpawn.NESTED_FIELD_SEP));
        }
        return entityType + "|" + name + "|" + health + "|0|" + scale
                + "|" + delaySeconds + "|" + kiCsv + "|" + meleeDamage + "|" + kiPower + "|" + defense
                + "|" + (rgModelId == null ? "" : rgModelId.trim())
                + "|" + allyCsv + "|" + swapSides;
    }

    public String summary() {
        String shortId = entityType.contains(":") ? entityType.substring(entityType.indexOf(':') + 1) : entityType;
        StringBuilder sb = new StringBuilder("§e" + shortId);
        // beside the entity id, else a rush of rgnpc stages is a list of identical rows
        if (rgModelId != null && !rgModelId.isBlank()) sb.append(" §d").append(rgModelId);
        if (bossName != null && !bossName.isBlank()) sb.append(" §f\"").append(bossName).append("\"");
        sb.append(" §7(+").append(delaySeconds).append("s");
        if (health > 0) sb.append(", ").append(trim(health)).append(" HP");
        if (meleeDamage > 0) sb.append(", M").append(trim(meleeDamage));
        if (kiPower > 0) sb.append(", K").append(trim(kiPower));
        if (defense > 0) sb.append(", D").append(trim(defense));
        if (!kiMoves.isEmpty()) sb.append(", ").append(kiMoves.size()).append(" ki");
        sb.append(")");
        // phase markers read as what the stage DOES, not stats, so they sit outside the bracket
        if (!allies.isEmpty()) sb.append(" §a+").append(allies.size()).append(" ally");
        if (swapSides) sb.append(" §dSWAP");
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
}
