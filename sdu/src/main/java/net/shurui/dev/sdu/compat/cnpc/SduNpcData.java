package net.shurui.dev.sdu.compat.cnpc;

/**
 * DMZ saga-fighter settings the {@code DMZ} tab edits, mixed into Custom NPCs' {@code DataDisplay} (alongside
 * {@link SduHairHolder}). Mirrors {@code shuruis_raid_bosses}' {@code RaidBossDef} battle-power schema, so a
 * Custom NPC configures a saga fighter the same way a raid boss does: DMZ derives melee/health/ki scaling from
 * {@code battlePower}, with explicit ki blast damage/speed, model scale, AI tier and a ki-move list. No values
 * are clamped, the tab accepts any number.
 */
public interface SduNpcData {

    /** DMZ battle power, the master stat DMZ scales melee/ki/health/speed from. */
    int sdu$getBattlePower();

    void sdu$setBattlePower(int v);

    /** Explicit max health; {@code 0} = keep DMZ's battle-power-derived default. */
    double sdu$getHealth();

    void sdu$setHealth(double v);

    /** Ki blast damage; {@code 0} = keep entity default. */
    double sdu$getKiBlastDamage();

    void sdu$setKiBlastDamage(double v);

    /** Movement speed (the {@code MOVEMENT_SPEED} attribute); {@code 0} = keep entity default. */
    double sdu$getMoveSpeed();

    void sdu$setMoveSpeed(double v);

    /** Base melee (attack-damage) value; {@code 0} = keep the battle-power-derived default. */
    double sdu$getMeleeDamage();

    void sdu$setMeleeDamage(double v);

    /**
     * DMZ-scale defense for sdu's NPC-defense mitigation curve (stored as the {@code dmz_npc_defense}
     * entity key on spawn); {@code 0} = no mitigation.
     */
    double sdu$getDefense();

    void sdu$setDefense(double v);

    /** DMZ AI tier (0 SIMPLE, 1 TACTICAL, 2 ADVANCED); {@code -1} = keep default. */
    int sdu$getAiTier();

    void sdu$setAiTier(int v);

    /** Comma-separated ki-move tokens {@code TYPE:cooldown:size} (matching the raid mod's {@code KiMove}). */
    String sdu$getKiMoves();

    void sdu$setKiMoves(String csv);

    /** When true the fighter never fires ki, melee only. */
    boolean sdu$isNoRanged();

    void sdu$setNoRanged(boolean v);
}
