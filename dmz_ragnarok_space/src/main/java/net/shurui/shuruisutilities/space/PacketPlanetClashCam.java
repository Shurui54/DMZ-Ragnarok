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
 * Server -&gt; client: tells the FIRING player's client that the planet clash it is watching is one of OURS (a
 * planet-buster struggle, not an ordinary player-vs-player beam clash) and hands it the anchor it needs to frame the
 * struggle: the entity id of the player's own giant ball (whose live position is the exact clash point) plus the target
 * planet's centre and visual radius (so the camera can put the ball in the foreground and the planet behind it).
 *
 * <h2>Why a packet at all, and why the SERVER decides</h2>
 * The client CANNOT tell a planet clash from a normal one on its own. DragonMineZ's clash-lock state
 * ({@code AbstractKiProjectile.clashLockedLength}) is a TRANSIENT server-only field and is never synced, and a ki
 * projectile's owner UUID is not reliably present on the client either, so "is the local player's own ball clash-locked"
 * is not answerable client-side. The server, on the other hand, knows exactly which player is in a planet clash and which
 * ball entity is the anchor (see {@link PlanetBusterModule}). Keeping the "is this a planet clash" decision on the server
 * also keeps the whole feature server-authoritative: the client only ever RENDERS a struggle the server has already
 * decided is happening.
 *
 * <h2>Relationship to DragonMineZ's own clash camera</h2>
 * DragonMineZ still owns the cinematic itself (it activates/deactivates the third-person camera and drives the HUD, FOV
 * and meter off its OWN {@code BeamClashStateS2C}). This packet does not turn the camera on or off; it only marks the
 * already-running cinematic as a PLANET clash so our framing override ({@code MixinDmzClashCamera}) knows to supply a shot
 * of the clash point instead of DragonMineZ's player-framed shot. Because DragonMineZ owns activate/deactivate, a lost
 * "inactive" copy of this packet can never strand the camera: the instant DragonMineZ ends the clash its cinematic stops
 * calling {@code computeShot} at all, so our override simply stops being consulted.
 *
 * <p>The whole client side is reached only through {@link DistExecutor} so the client camera holder never classloads on a
 * dedicated server, exactly like {@link PacketPlanetDoom}. Sent only to the one firing player (see {@link
 * PlanetBusterModule}), never broadcast.
 */
public class PacketPlanetClashCam implements ISUPacket
{
    // true while the planet clash is locked and being fought; false the instant it resolves, dissolves or is dropped.
    private boolean active;
    // entity id of the firing player's own giant ball. Its LIVE client position is the struggle / clash point, so the
    // client looks the entity up every frame rather than trusting a stale position. -1 when inactive.
    private int ballEntityId;
    // the target planet's centre and visual radius, so the client can aim the camera behind the ball toward the planet
    // and keep both readable. Only meaningful while active.
    private Vec3 planetCentre;
    private float planetRadius;

    public PacketPlanetClashCam()
    {
    }

    /** Build an ACTIVE marker: the clash is locked; {@code ballEntityId} is the anchor, {@code centre}/{@code radius} the planet. */
    public static PacketPlanetClashCam active(int ballEntityId, Vec3 centre, float radius)
    {
        PacketPlanetClashCam p = new PacketPlanetClashCam();
        p.active = true;
        p.ballEntityId = ballEntityId;
        p.planetCentre = centre;
        p.planetRadius = radius;
        return p;
    }

    /** Build an INACTIVE marker: the planet clash is over; the client drops our framing override. */
    public static PacketPlanetClashCam inactive()
    {
        PacketPlanetClashCam p = new PacketPlanetClashCam();
        p.active = false;
        p.ballEntityId = -1;
        p.planetCentre = Vec3.ZERO;
        p.planetRadius = 0.0F;
        return p;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(active);
        buf.writeVarInt(ballEntityId);
        buf.writeDouble(planetCentre.x);
        buf.writeDouble(planetCentre.y);
        buf.writeDouble(planetCentre.z);
        buf.writeFloat(planetRadius);
    }

    public static PacketPlanetClashCam decode(FriendlyByteBuf buf)
    {
        PacketPlanetClashCam p = new PacketPlanetClashCam();
        p.active = buf.readBoolean();
        p.ballEntityId = buf.readVarInt();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        p.planetCentre = new Vec3(x, y, z);
        p.planetRadius = buf.readFloat();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // Client-only: hand the marker to the client camera holder, which lives entirely in the client package so it is
        // never loaded on a dedicated server. It caches the anchor and flips our framing override on or off.
        final boolean a = active;
        final int id = ballEntityId;
        final Vec3 c = planetCentre;
        final float r = planetRadius;
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.client.space.PlanetClashCamera.accept(a, id, c, r));
    }

    public static void handler(final PacketPlanetClashCam message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
