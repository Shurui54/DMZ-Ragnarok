package net.shurui.shuruisutilities.racing.entity;

/**
 * What a {@link RaceKiOrbEntity} represents, driving its colour and behaviour. A synched byte on the entity, so
 * the ORDINALS are pinned: never reorder, only append.
 *
 * <p>These are RACE projectiles that borrow DMZ ki visuals; they carry no DMZ technique or damage logic (owner
 * rule). GREEN is the Ki Blast (green shell), RED the Hellzone Grenade (red shell), SPIRIT the Spirit Bomb (blue
 * shell), MINE the Ki Mine (banana), FAKE the Fake Dragon Ball (fake item box). Append only; the ordinal is synced.
 */
public enum RaceOrbKind
{
    GREEN,
    RED,
    SPIRIT,
    MINE,
    FAKE;

    public byte id()
    {
        return (byte) ordinal();
    }

    public static RaceOrbKind byId(int id)
    {
        RaceOrbKind[] all = values();
        return id >= 0 && id < all.length ? all[id] : GREEN;
    }
}
