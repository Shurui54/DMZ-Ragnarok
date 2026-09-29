package net.shurui.shuruisutilities.space;

import net.minecraft.world.phys.Vec3;

/**
 * Key, world position and visual half-extent of a fixed body. This is all the generated-planet, asteroid, star and
 * black-hole layouts consult (they reject candidates overlapping a fixed body or the origin column), so it is the
 * payload that must agree between server and client for both to derive the same layout.
 *
 * <p>Server side it is a projection of {@link PlanetRegistry.Planet}; client side the same list arrives over
 * {@link PacketSpaceLayoutSync} and feeds the identical derivations. A pure value snapshot, not stored on an entity.
 */
public final class FixedBody
{
    public final String key;
    public final Vec3 position;
    public final float radius;

    public FixedBody(String key, Vec3 position, float radius)
    {
        this.key = key;
        this.position = position;
        this.radius = radius;
    }
}
