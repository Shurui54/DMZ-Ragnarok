package net.shurui.shuruisutilities.space;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Server -&gt; client descriptor of the generated planet the receiving player is standing on: its surface
 * {@link SurfaceStamp.Theme} (so the client can colour the per-planet sky, {@link PlanetSurfaceEffects}) and its
 * SPACE-body position (so the client can draw the sun and the sibling planets in their true directions relative to this
 * planet, {@link SpaceBodyRenderer}).
 *
 * <p>WHY THIS IS NEEDED (and cannot be derived client side). On the shared surface dimension a planet's id folds one-way
 * to a grid cell ({@link SurfaceDimension}), so the client cannot invert its own position back to the planet, its
 * stamped theme, or where that planet sits in the open-space layout. The server keeps all three per player in
 * {@code SurfaceTravelData} and resolves the stamped theme through {@code GeneratedPlanetClaims}; this is the only seam
 * that carries them to the client. It is sent only when the answer CHANGES (a landing, a walk into a different cell, a
 * relog onto the surface, or leaving the surface), by {@link SurfaceSkySync}, so it is a rare, tiny packet.
 *
 * <p>{@code present == false} clears the client sky state (the player is not on a resolved surface planet): the sky then
 * falls back to the biome-driven look (Beerus violet, Vegeta green, else black void), exactly the pre-B1 behaviour.
 * {@code hasBody == false} keeps a theme but draws no sky bodies (a planet resolved from position with no derivable
 * space anchor); the atmosphere dome still shows.
 *
 * <p>Client-only handling is behind {@link DistExecutor} so the client sky-state class never loads on a dedicated
 * server, exactly like {@link PacketPlanetDoom}. Registered on core's shared SU channel (id 82).
 */
public class PacketSurfaceSky implements ISUPacket
{
    private boolean present;
    // SurfaceStamp.Theme ordinal, or -1 when present but with no theme (should not normally happen; defensive).
    private int themeOrdinal;
    // the stable planet key (its generated id). The client needs it to compute this planet's own deterministic weather
    // (PlanetWeather keys on it) and to know whether the planet it stands on is ringed (PlanetRings keys on it), so its
    // own ring system can be drawn arcing across the sky. May be "" when a theme is known but no id is (defensive).
    private String planetKey = "";
    private boolean hasBody;
    private double bodyX;
    private double bodyY;
    private double bodyZ;

    public PacketSurfaceSky()
    {
    }

    /** Player is on a themed planet {@code planetKey}, with (optionally) a known space-body anchor. */
    public static PacketSurfaceSky present(String planetKey, SurfaceStamp.Theme theme, Vec3 body)
    {
        PacketSurfaceSky p = new PacketSurfaceSky();
        p.present = true;
        p.planetKey = planetKey == null ? "" : planetKey;
        p.themeOrdinal = theme == null ? -1 : theme.ordinal();
        p.hasBody = body != null;
        if (body != null)
        {
            p.bodyX = body.x;
            p.bodyY = body.y;
            p.bodyZ = body.z;
        }
        return p;
    }

    /** Player is not on a resolved surface planet: clear the client sky state. */
    public static PacketSurfaceSky absent()
    {
        return new PacketSurfaceSky();
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(present);
        buf.writeUtf(planetKey, 128);
        buf.writeVarInt(themeOrdinal);
        buf.writeBoolean(hasBody);
        buf.writeDouble(bodyX);
        buf.writeDouble(bodyY);
        buf.writeDouble(bodyZ);
    }

    public static PacketSurfaceSky decode(FriendlyByteBuf buf)
    {
        PacketSurfaceSky p = new PacketSurfaceSky();
        p.present = buf.readBoolean();
        p.planetKey = buf.readUtf(128);
        p.themeOrdinal = buf.readVarInt();
        p.hasBody = buf.readBoolean();
        p.bodyX = buf.readDouble();
        p.bodyY = buf.readDouble();
        p.bodyZ = buf.readDouble();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        final boolean pres = present;
        final String key = planetKey;
        final int ord = themeOrdinal;
        final boolean body = hasBody;
        final Vec3 pos = new Vec3(bodyX, bodyY, bodyZ);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> applyOnClient(pres, key, ord, body, pos));
    }

    // Client-only. Kept in a separate method so the DistExecutor lambda is the only thing that names the client class.
    private static void applyOnClient(boolean present, String planetKey, int themeOrdinal, boolean hasBody, Vec3 body)
    {
        if (!present)
        {
            net.shurui.shuruisutilities.client.space.SurfaceSkyState.clear();
            return;
        }
        SurfaceStamp.Theme[] themes = SurfaceStamp.Theme.values();
        SurfaceStamp.Theme theme = (themeOrdinal >= 0 && themeOrdinal < themes.length) ? themes[themeOrdinal] : null;
        net.shurui.shuruisutilities.client.space.SurfaceSkyState.set(planetKey, theme, hasBody ? body : null);
    }

    public static void handler(final PacketSurfaceSky message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
