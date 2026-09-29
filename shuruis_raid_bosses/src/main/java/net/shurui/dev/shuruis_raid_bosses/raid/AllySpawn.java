package net.shurui.dev.shuruis_raid_bosses.raid;

/**
 * An NPC that fights ON THE PLAYER'S SIDE. Shaped like {@link EnemyWave} (same fields, pipe token,
 * append-only slot rule), minus {@code wave}, since allies arrive with the encounter or a
 * {@link BossStage}, not in waves.
 *
 * <p>Nothing here says "friendly". An ally spawns through the same pipeline as an enemy and is then
 * tracked in a different set, which decides everything: not counted as a live enemy (killing it is not
 * progress), not registered with the damage tracker (hitting it credits nobody), wears the ally glow, and
 * targets enemies never a participant with friendly fire refused both ways. That separation is what lets
 * {@link BossStage#swapSides} move live entities between sets and re-colour them without respawning.
 *
 * <p>Pipe-delimited token:
 * {@code entityType|count|health|legacyBattlePower|scale|meleeDamage|kiPower|defense|rgModelId|name}.
 * Slot 4 mirrors {@link EnemyWave}'s dead battle-power slot to keep the two formats aligned; battle power
 * is DERIVED at spawn from melee + ki power.
 */
public class AllySpawn {

    public String entityType = "dmz_ragnarok:raid_npc";
    public int count = 1;
    public double health = 0;       // 0 = keep the entity's own max health
    public double meleeDamage = 0;  // 0 = fall back to the def's melee damage, then the entity default
    public double kiPower = 0;      // 0 = fall back to the def's ki power, then the entity default
    public double defense = 0;      // 0 = fall back to the def's baseDefense (0 there = no mitigation)
    public double scale = 0;        // 0 = keep default (DMZ saga entities only)

    /** which ragnarok NPC character a spawned {@code dmz_ragnarok:rgnpc} wears; blank leaves it alone */
    public String rgModelId = "";

    /** display name above the ally; blank keeps the entity's own */
    public String name = "";

    /**
     * This ally's OWN ki loadout, as {@code TYPE:cooldown:size} tokens, not shared with the boss. Allies
     * used to spawn with an empty list, which the stat pass read as "unset" and filled from the
     * encounter's {@code kiMoves}, so the partner NPC threw the boss's own attacks back at it. An empty
     * list now means no ki moves, never the boss's. Comma-separated in token slot 11, appended after
     * {@code name} so older tokens still parse as no moves.
     */
    public final java.util.List<String> kiMoves = new java.util.ArrayList<>();

    public AllySpawn() {
    }

    public static AllySpawn fromToken(String token) {
        AllySpawn a = new AllySpawn();
        if (token == null || token.isBlank()) {
            return a;
        }
        String[] p = token.split("\\|", -1);
        if (p.length >= 1 && !p[0].isBlank()) {
            a.entityType = net.shurui.shuruisutilities.ragnarok.LegacyIds.normalize(p[0].trim());
        }
        if (p.length >= 2) {
            a.count = Math.max(1, parseInt(p[1], a.count));
        }
        if (p.length >= 3) {
            a.health = parseDouble(p[2], a.health);
        }
        // p[3] = legacy battle power slot, kept only to stay aligned with EnemyWave. Never read, never applied.
        if (p.length >= 5) {
            a.scale = parseDouble(p[4], a.scale);
        }
        if (p.length >= 6) {
            a.meleeDamage = parseDouble(p[5], a.meleeDamage);
        }
        if (p.length >= 7) {
            a.kiPower = parseDouble(p[6], a.kiPower);
        }
        if (p.length >= 8) {
            a.defense = parseDouble(p[7], a.defense);
        }
        if (p.length >= 9) {
            a.rgModelId = p[8].trim();
        }
        if (p.length >= 10) {
            a.name = p[9];
        }
        if (p.length >= 11) {
            a.kiMoves.clear();
            for (String move : p[10].split(",")) {
                String tk = move.trim();
                if (!tk.isEmpty()) {
                    a.kiMoves.add(tk);
                }
            }
        }
        return a;
    }

    public String toToken() {
        return entityType + "|" + count + "|" + health + "|0|" + scale + "|" + meleeDamage + "|" + kiPower
                + "|" + defense + "|" + (rgModelId == null ? "" : rgModelId.trim())
                + "|" + safeName()
                + "|" + String.join(",", kiMoves);
    }

    /**
     * Field separator when an ally token is nested inside a pipe token (a {@link BossStage}'s ally list).
     * The stage token is pipe-delimited too, so a raw {@code |} would corrupt every field after it. Same
     * token, this char standing in for the pipe, translated back on read: one format, one parser.
     */
    public static final char NESTED_FIELD_SEP = '^';

    /** Separator between ally tokens inside a stage's list. */
    public static final char NESTED_ENTRY_SEP = ';';

    /** {@link #toToken()} escaped for nesting inside a stage token. */
    public String toNestedToken() {
        return toToken().replace('|', NESTED_FIELD_SEP);
    }

    /** Inverse of {@link #toNestedToken()}. */
    public static AllySpawn fromNestedToken(String token) {
        return fromToken(token == null ? null : token.replace(NESTED_FIELD_SEP, '|'));
    }

    /**
     * Display name with every separator stripped: a pipe breaks the flat token, the other two break it
     * once nested inside a stage.
     */
    private String safeName() {
        if (name == null) {
            return "";
        }
        return name.replace('|', ' ').replace(NESTED_FIELD_SEP, ' ').replace(NESTED_ENTRY_SEP, ' ');
    }

    public String summary() {
        String shortId = entityType.contains(":") ? entityType.substring(entityType.indexOf(':') + 1) : entityType;
        StringBuilder sb = new StringBuilder("§aALLY §f" + count + "§7x §e" + shortId);
        if (rgModelId != null && !rgModelId.isBlank()) {
            sb.append(" §d").append(rgModelId);
        }
        if (name != null && !name.isBlank()) {
            sb.append(" §f\"").append(name).append("\"");
        }
        java.util.List<String> bits = new java.util.ArrayList<>();
        if (health > 0) {
            bits.add(trim(health) + " HP");
        }
        if (meleeDamage > 0) {
            bits.add("M" + trim(meleeDamage));
        }
        if (kiPower > 0) {
            bits.add("K" + trim(kiPower));
        }
        if (defense > 0) {
            bits.add("D" + trim(defense));
        }
        if (!bits.isEmpty()) {
            sb.append(" §7(").append(String.join(", ", bits)).append(")");
        }
        return sb.toString();
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
}
