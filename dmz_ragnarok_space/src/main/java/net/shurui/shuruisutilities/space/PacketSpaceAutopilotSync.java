package net.shurui.shuruisutilities.space;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: the autopilot target and constant per-tick step for the controlling pilot, the core of the
 * space-pod jitter fix. The server used to drive the pod with a per-tick {@code teleportTo} +
 * {@code ClientboundMoveVehiclePacket}, which lands in the {@code absMoveTo} branch on the controlling client and
 * destroys interpolation (once {@code xo == x} there is nothing left to lerp), so the pod advanced in 20 discrete hops a
 * second. That whole path is gone. Instead the client's own {@code SpacePodEntity.travel} integrates the pod one clamped
 * step toward the synced target each tick (see {@code core.mixin.client.MixinDmzSpacePodTravel}) and reports position
 * through the ordinary {@code ServerboundMoveVehiclePacket} path, exactly like manual flight, which is smooth precisely
 * because it never hits {@code absMoveTo}.
 *
 * <p>Sent only on CHANGE: when an autopilot starts, when its target or step changes (a launch crossing into space
 * switches the target from the ascent column to the destination body), and when it stops. Never per tick: a per-tick
 * stream is exactly the thing being removed. The client integration is deterministic and shares the same target/step, so
 * the position the client reports agrees with what the server integrates and the vehicle handshake is a confirmation
 * rather than a fight.
 *
 * <p>The whole client side is reached only through {@link DistExecutor} so {@link
 * net.shurui.shuruisutilities.client.autopilot.SpaceAutopilotClient} never classloads on a dedicated server, mirroring
 * {@link PacketPlanetClashCam}. Sent only to the one controlling pilot (see {@link SpaceTravelModule}), never broadcast.
 */
public class PacketSpaceAutopilotSync implements ISUPacket
{
    // A placeholder dimension written when inactive, so the wire format is fixed-shape and never has to branch on the
    // active flag. It is never read while inactive (the client clears its state on an inactive sync).
    private static final ResourceLocation PLACEHOLDER_DIM = Level.OVERWORLD.location();

    // true while the pod is under autopilot; false the instant it stops (landing, dismount, abort).
    private boolean active;
    // the dimension the target lives in. The client only integrates while its pod's dimension matches this, so a fresh
    // pod that has just crossed into space is never dragged toward the previous dimension's stale target.
    private ResourceLocation dimension;
    // the world-space point the pod is flying toward (the ascent column top while climbing, the destination body centre
    // in space). Landing detection on the server intercepts well before the pod reaches a body centre.
    private double targetX;
    private double targetY;
    private double targetZ;
    // the constant per-tick travel distance. Constant (not eased) on purpose: it keeps the client and server integrations
    // in lockstep and keeps the reported move within the vehicle "moved too quickly" tolerance (which compares against
    // the pod's own deltaMovement, itself set to this step).
    private double step;

    public PacketSpaceAutopilotSync()
    {
    }

    /** Build an ACTIVE sync: the pod is flying toward {@code target} (in {@code dim}) at {@code step} blocks per tick. */
    public static PacketSpaceAutopilotSync active(ResourceLocation dim, Vec3 target, double step)
    {
        PacketSpaceAutopilotSync p = new PacketSpaceAutopilotSync();
        p.active = true;
        p.dimension = dim;
        p.targetX = target.x;
        p.targetY = target.y;
        p.targetZ = target.z;
        p.step = step;
        return p;
    }

    /** Build an INACTIVE sync: the autopilot has stopped; the client drops its integration state. */
    public static PacketSpaceAutopilotSync inactive()
    {
        PacketSpaceAutopilotSync p = new PacketSpaceAutopilotSync();
        p.active = false;
        p.dimension = PLACEHOLDER_DIM;
        p.targetX = 0.0;
        p.targetY = 0.0;
        p.targetZ = 0.0;
        p.step = 0.0;
        return p;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(active);
        buf.writeResourceLocation(dimension == null ? PLACEHOLDER_DIM : dimension);
        buf.writeDouble(targetX);
        buf.writeDouble(targetY);
        buf.writeDouble(targetZ);
        buf.writeDouble(step);
    }

    public static PacketSpaceAutopilotSync decode(FriendlyByteBuf buf)
    {
        PacketSpaceAutopilotSync p = new PacketSpaceAutopilotSync();
        p.active = buf.readBoolean();
        p.dimension = buf.readResourceLocation();
        p.targetX = buf.readDouble();
        p.targetY = buf.readDouble();
        p.targetZ = buf.readDouble();
        p.step = buf.readDouble();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        final boolean a = active;
        final ResourceLocation dim = dimension;
        final double x = targetX;
        final double y = targetY;
        final double z = targetZ;
        final double s = step;
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.client.autopilot.SpaceAutopilotClient.accept(a, dim, x, y, z, s));
    }

    public static void handler(final PacketSpaceAutopilotSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
