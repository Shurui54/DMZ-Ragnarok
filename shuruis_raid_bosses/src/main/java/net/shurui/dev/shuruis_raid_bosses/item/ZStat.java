package net.shurui.dev.shuruis_raid_bosses.item;

import com.dragonminez.common.stats.character.Stats;

/**
 * DMZ stat channels a Z-Soul can raise past the global cap. Each maps to a DMZ {@code BonusStats} key (the
 * lever that pushes a stat past {@code maxValue}) and to the raw stat that must be maxed before beyond-cap
 * growth is allowed. Six of the seven have a dedicated soul; {@link #DEFENSE} is rainbow-only.
 */
public enum ZStat {
    //        bonusKey  rawStat  display          itemName        texture colour
    VITALITY("VIT", "vit", "Vitality", "vitality", "red"),
    STAMINA("STM", "res", "Stamina", "stamina", "orange"),
    STRENGTH("STR", "str", "Strength", "strength", "green"),
    KI_POWER("PWR", "pwr", "Ki Power", "ki_power", "blue"),
    STRIKE_POWER("SKP", "skp", "Strike Power", "strike_power", "magenta"),
    ENERGY("ENE", "ene", "Energy", "energy", "periwinkle"),
    DEFENSE("DEF", "res", "Defense", null, null);

    /** DMZ {@code BonusStats} key; a {@code "+"} bonus here raises the effective stat above the global cap. */
    public final String bonusKey;
    /** Raw DMZ stat key that gates beyond-cap growth (it must be at the global cap first). */
    public final String rawStatKey;
    public final String display;
    /** Registry-name fragment of the dedicated soul, or null if this channel has no dedicated soul. */
    public final String itemName;
    /** Texture-colour fragment used to source the item texture, or null for a channel with no soul. */
    public final String colour;

    ZStat(String bonusKey, String rawStatKey, String display, String itemName, String colour) {
        this.bonusKey = bonusKey;
        this.rawStatKey = rawStatKey;
        this.display = display;
        this.itemName = itemName;
        this.colour = colour;
    }

    /** Whether a dedicated (non-rainbow) soul exists for this channel. */
    public boolean hasDedicatedSoul() {
        return itemName != null;
    }

    /** Translation-key suffix for this stat's display name. {@link #DEFENSE} has no {@link #itemName}, so it uses {@code defense}. */
    public String itemNameOrDefense() {
        return itemName != null ? itemName : "defense";
    }

    /** Current value of the raw DMZ stat that backs this channel (used for the "max the base first" gate). */
    public int baseStat(Stats stats) {
        return switch (rawStatKey) {
            case "vit" -> stats.getVitality();
            case "res" -> stats.getResistance();
            case "str" -> stats.getStrength();
            case "pwr" -> stats.getKiPower();
            case "skp" -> stats.getStrikePower();
            case "ene" -> stats.getEnergy();
            default -> 0;
        };
    }
}
