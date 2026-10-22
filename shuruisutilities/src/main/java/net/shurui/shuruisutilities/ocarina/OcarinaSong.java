package net.shurui.shuruisutilities.ocarina;

/**
 * The songs the ocarina can play. Each one is a DragonMineZ SKILL of its own ({@link #skillId}), bought and levelled
 * at Tapion exactly like a DMZ ki attack is bought at its master: the skill's level is the song's level.
 *
 * <p>The order is the WIRE FORMAT: the client sends the ordinal when it picks one, so songs may be added to the end
 * but never reordered.
 *
 * <p>Each carries the four-note phrase shown in the menu. Notes are lane indices into the rhythm chart (up, left,
 * down, right), which is what lets one instrument use the melee clash's own chart machinery.
 */
public enum OcarinaSong
{
    /** Heals you and your party and guild; heal and reach grow with the level. */
    MENDING("ocarina_healing", new int[] { 0, 2, 1, 3 }),

    /** Ocarina Boost: raises your DMZ stats for a while; from a set level it reaches your allies too. */
    VALOUR("ocarina_boost", new int[] { 3, 1, 0, 2 }),

    /** Ocarina Drain: lowers the stats of everyone around who is not an ally, and slows them. */
    DISCORD("ocarina_drain", new int[] { 2, 3, 1, 0 }),

    /** Hildegarn's Seal: binds nearby enemies in place for a moment, the melody that sealed the demon. */
    SEAL("ocarina_seal", new int[] { 1, 1, 3, 2 }),

    /** Lullaby of Konats: hostile creatures nearby lose interest and cannot pick a target for a while. */
    LULLABY("ocarina_lullaby", new int[] { 0, 3, 0, 1 }),

    /** Ki Resonance: you and your allies recover ki and stamina every second while it rings. */
    RESONANCE("ocarina_resonance", new int[] { 2, 0, 3, 3 });

    /** The DragonMineZ skill id (skills.json key, lang {@code skill.dragonminez.<id>}). */
    public final String skillId;
    /** The phrase, as rhythm lanes. Shown in the menu so a player can learn it by sight. */
    public final int[] phrase;

    OcarinaSong(String skillId, int[] phrase)
    {
        this.skillId = skillId;
        this.phrase = phrase;
    }

    /** Lang key of the song's name (the DMZ skill name). */
    public String nameKey()
    {
        return "skill.dragonminez." + skillId;
    }

    public static OcarinaSong byId(int id)
    {
        OcarinaSong[] all = values();
        return id < 0 || id >= all.length ? MENDING : all[id];
    }

    /** The song bought as this DMZ skill, or null. */
    public static OcarinaSong bySkill(String skillId)
    {
        if (skillId == null)
            return null;
        for (OcarinaSong s : values())
            if (s.skillId.equalsIgnoreCase(skillId))
                return s;
        return null;
    }
}
