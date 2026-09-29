package net.shurui.shuruisutilities.energy;

/**
 * The three role energies: the resource behind the shadow dragon techniques, the God of Destruction abilities and
 * the Angel abilities.
 *
 * <p>DELIBERATELY NOT A DMZ STAT. Every one of these runs 0..{@link EnergyManager#MAX} on a flat scale that no DMZ
 * stat feeds, so a move costs the same share of the bar for a fresh character as for a maxed one and the bar reads
 * as a role resource rather than a second ki pool. Do not derive the maximum from battle power or anything else:
 * the flat 100 IS the design.
 *
 * <p>The ordinal is written to the wire by {@link PacketEnergySync}, so the ORDER of these constants is a
 * persisted/protocol detail. Append new kinds at the end; never reorder or remove.
 */
public enum EnergyKind
{
    /** Shadow dragon malice. Drawn top half red over bottom half blood red. */
    MALICE("malice"),
    /** God of Destruction energy. Drawn purple. */
    DESTRUCTION("destruction"),
    /** Angelic energy. Drawn silvery blue. */
    ANGELIC("angelic");

    private final String id;

    EnergyKind(String id)
    {
        this.id = id;
    }

    /** Stable string id, used as the NBT key so the stored value survives any future reordering of the enum. */
    public String id()
    {
        return id;
    }

    /** Resolve from {@link #id()}, or null when unknown (a kind saved by a newer build, or a typo). */
    public static EnergyKind byId(String id)
    {
        for (EnergyKind k : values())
            if (k.id.equals(id))
                return k;
        return null;
    }

    /** Resolve from the wire ordinal, or null when out of range. */
    public static EnergyKind byOrdinal(int ordinal)
    {
        EnergyKind[] all = values();
        return ordinal < 0 || ordinal >= all.length ? null : all[ordinal];
    }
}
