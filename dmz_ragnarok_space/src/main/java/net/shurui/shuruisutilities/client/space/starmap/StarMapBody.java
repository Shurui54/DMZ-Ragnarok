package net.shurui.shuruisutilities.client.space.starmap;

import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.space.SurfaceStamp;

/**
 * One body on the SPACE STAR MAP: the sun, a fixed main planet, a generated planet or a super dragon ball body. A pure
 * client-side value snapshot built by {@link StarMapData} from the SYNCED space layout (fixed bodies, generated-planet
 * derivation, super bodies, owners), so it carries only what the client can know reliably: nothing here is invented.
 * Server-only figures (defender counts, guild battle power, toughness) are deliberately absent, because the map inspects
 * an arbitrary selected body rather than the one a player is aiming at, and those are not synced for that case.
 */
public final class StarMapBody
{
    public enum Kind
    {
        /** The central sun. Drawn at the layout centre, never a travel target. */
        SUN,
        /** A fixed main planet (a real DMZ dimension: Earth, Namek, Sacred Kai world, Planet Vegeta, ...). */
        FIXED,
        /** A procedurally generated planet (a {@code sugen:} id): a system planet orbiting its sun, or a legacy one. */
        GENERATED,
        /** A super dragon ball body (one of the seven). */
        SUPER,
        /** A generated star system's SUN (a {@code sustar:} key). The centre of a system's orbit rings, never landable. */
        STAR
    }

    /** The stable key: a dimension id for a fixed body, a {@code sugen:} id for a generated one, the super id, or the
     *  sun key. Used to select, to derive the name, and (fixed bodies only) as the set-course payload. */
    public final String key;
    public final Kind kind;
    public final Component name;
    public final Vec3 position;
    /** The body's visual half-extent in blocks, used for the drawn disc radius and shown in the stats panel. */
    public final float radius;
    /** Packed 0xRRGGBB tint used to colour the disc; alpha is added at draw time. */
    public final int tint;
    /** Surface theme for a generated planet, or null for a body with no derived theme (sun, fixed, super). */
    public final SurfaceStamp.Theme theme;
    /** Surface size in blocks per side for a generated planet, or 0 when not applicable. */
    public final int surfaceSize;
    /** Owning guild name for a claimed generated planet, or "" when unclaimed / not applicable. */
    public final String owner;
    /** Whether a super body's dragon ball has been claimed. Meaningful only for {@link Kind#SUPER}. */
    public final boolean superClaimed;
    /** Whether a player can land on this body (planets and super bodies yes, the sun no). */
    public final boolean landable;
    /** Whether the star map may arm a course/autopilot to this body: main (fixed) planets AND generated planets, never
     *  the sun, a system star or a super body. */
    public final boolean courseTarget;
    /** For a system planet, the key of the SUN it orbits (so the map can group it and draw its ring); "" otherwise. For a
     *  {@link Kind#STAR} this is its own key. */
    public final String systemStarKey;

    public StarMapBody(String key, Kind kind, Component name, Vec3 position, float radius, int tint,
                       SurfaceStamp.Theme theme, int surfaceSize, String owner, boolean superClaimed,
                       boolean landable, boolean courseTarget, String systemStarKey)
    {
        this.key = key;
        this.kind = kind;
        this.name = name;
        this.position = position;
        this.radius = radius;
        this.tint = tint;
        this.theme = theme;
        this.surfaceSize = surfaceSize;
        this.owner = owner == null ? "" : owner;
        this.superClaimed = superClaimed;
        this.landable = landable;
        this.courseTarget = courseTarget;
        this.systemStarKey = systemStarKey == null ? "" : systemStarKey;
    }
}
