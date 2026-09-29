package net.shurui.shuruisutilities.space;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client START-OF-PHASE marker for the planet DOOM SEQUENCE, the visual half of a planet bust. The bust is
 * a two-phase animation the server drives on a timer (see {@link PlanetBusterModule}): first a RED RAMP where the doomed
 * planet tints deeper and deeper red, then a SHATTER where it comes apart into flying cube shards. This packet only ever
 * announces the START of one of those phases plus how long it runs; the client then runs its OWN local timer from the
 * moment of receipt, so there is NO per-tick sync traffic for the animation, just one small packet per phase change.
 *
 * <p>It carries everything the client renderer needs to draw the phase without deriving anything itself: the planet id
 * (so the renderer can match a still-drawn body during the ramp, and key the shatter after the body is gone), the body's
 * position, visual radius and tint (so the shatter, which outlives the planet in the client's draw list, still knows
 * where the planet was, how big it was and what colour its debris is), the phase, and the phase duration in ticks.
 *
 * <p>WHY THE SHATTER NEEDS ITS OWN POSITION/RADIUS/TINT. When the ramp ends the server destroys the planet, which
 * resyncs the layout and makes every client STOP drawing that body. So the shatter cannot read the body out of the
 * renderer's derived list any more (it is gone by design); it is drawn purely from the state this packet delivered.
 *
 * <p>A player who joins DURING a sequence simply misses it: there is no resync for an in-flight doom, and that is fine,
 * a missed two-second animation costs nothing and the planet is either intact or already rubble by the time they arrive.
 *
 * <p>The whole client side is behind {@link DistExecutor} so the client doom-effects class never loads on a dedicated
 * server, exactly like {@link net.shurui.shuruisutilities.guilds.raid.clone.PacketCloneAppearance}. Registered in the SU
 * channel setup (see ShuruisUtilities), broadcast only to players in the space dimension (see PlanetBusterModule).
 */
public class PacketPlanetDoom implements ISUPacket
{
    // the red-tint ramp: the planet is still present and drawn, its colour blends toward red over the duration.
    public static final byte PHASE_RAMP = 0;
    // the shatter: the planet has just been destroyed and removed from every client's draw list, so the client draws
    // flying cube shards from this packet's own position/radius/tint for the duration, then stops.
    public static final byte PHASE_SHATTER = 1;

    private String planetId;
    private Vec3 pos;
    private float radius;
    private int tint;
    private byte phase;
    private int durationTicks;

    public PacketPlanetDoom()
    {
    }

    /** Build a phase marker for a doomed planet. {@code durationTicks} is how long this phase runs on the client. */
    public static PacketPlanetDoom of(String planetId, Vec3 pos, float radius, int tint, byte phase, int durationTicks)
    {
        PacketPlanetDoom p = new PacketPlanetDoom();
        p.planetId = planetId == null ? "" : planetId;
        p.pos = pos;
        p.radius = radius;
        p.tint = tint;
        p.phase = phase;
        p.durationTicks = durationTicks;
        return p;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(planetId);
        buf.writeDouble(pos.x);
        buf.writeDouble(pos.y);
        buf.writeDouble(pos.z);
        buf.writeFloat(radius);
        buf.writeInt(tint);
        buf.writeByte(phase);
        buf.writeVarInt(durationTicks);
    }

    public static PacketPlanetDoom decode(FriendlyByteBuf buf)
    {
        PacketPlanetDoom p = new PacketPlanetDoom();
        p.planetId = buf.readUtf();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        p.pos = new Vec3(x, y, z);
        p.radius = buf.readFloat();
        p.tint = buf.readInt();
        p.phase = buf.readByte();
        p.durationTicks = buf.readVarInt();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // Client-only: hand the phase off to the client doom-effects holder, which lives entirely in the client package
        // so it never loads on a dedicated server. The renderer reads its state to draw the ramp and the shatter.
        final String id = planetId;
        final Vec3 p = pos;
        final float rad = radius;
        final int t = tint;
        final byte ph = phase;
        final int dur = durationTicks;
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.client.space.PlanetDoomEffects.accept(id, p, rad, t, ph, dur));
    }

    public static void handler(final PacketPlanetDoom message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
