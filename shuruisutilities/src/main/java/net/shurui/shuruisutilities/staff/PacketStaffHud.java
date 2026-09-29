package net.shurui.shuruisutilities.staff;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * server -> client: the task to keep on screen, or nothing at all.
 *
 * <p>The server decides visibility, not the client. Whether a reminder should be up is a function of the roster,
 * the clock and the task's status, and every one of those lives on the server; a client working it out for itself
 * would need all three mirrored to it and would still be wrong for the moment between a change and its sync.
 *
 * <p>So there is exactly one rule here and the server applies it: {@link #push} sends a visible reminder only when
 * the player is clocked in AND holds an accepted task, and sends the empty form otherwise. Clocking out, finishing
 * the task, dropping it or being taken off the roster all reach the client as the same "nothing to show".
 */
public class PacketStaffHud implements ISUPacket
{
    public boolean active;
    public String title = "";
    public String description = "";

    /** Why it was last sent back, if it was. Shown under the description so the fix is on screen with the job. */
    public String denialReason = "";

    public PacketStaffHud() {}

    /**
     * Work out what this player should be seeing and send it.
     *
     * <p>Call after anything that could change the answer: clocking in or out, accepting, completing, dropping,
     * a denial, or the player arriving. Cheap enough that calling it when nothing changed costs nothing.
     */
    public static void push(ServerPlayer player)
    {
        if (player == null)
            return;
        PacketStaffHud packet = new PacketStaffHud();
        // The roster, the clock and the board live in the Ragnarok Key; keyless this is always the empty form.
        StaffTask task = net.shurui.shuruisutilities.api.key.StaffHooks.get().hudTask(player.getUUID());
        if (task != null)
        {
            packet.active = true;
            packet.title = task.title;
            packet.description = task.description;
            packet.denialReason = task.lastDenialReason == null ? "" : task.lastDenialReason;
        }
        try
        {
            NetworkUtils.sendTo(packet, player);
        }
        catch (Throwable ignored)
        {
            // A HUD that failed to sync is not worth taking anything else down for.
        }
    }

    /** Same as {@link #push}, for the call sites that only hold a uuid. Silent when they are not online. */
    public static void pushTo(java.util.UUID id)
    {
        if (id == null)
            return;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;
        push(server.getPlayerList().getPlayer(id));
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(active);
        if (!active)
            return;
        buf.writeUtf(title, 256);
        buf.writeUtf(description, 1024);
        buf.writeUtf(denialReason, 512);
    }

    public static PacketStaffHud decode(FriendlyByteBuf buf)
    {
        PacketStaffHud packet = new PacketStaffHud();
        packet.active = buf.readBoolean();
        if (!packet.active)
            return packet;
        packet.title = buf.readUtf(256);
        packet.description = buf.readUtf(1024);
        packet.denialReason = buf.readUtf(512);
        return packet;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> this::applyClient);
    }

    private void applyClient()
    {
        net.shurui.shuruisutilities.client.hud.StaffTaskHudState.set(active, title, description, denialReason);
    }

    public static void handler(final PacketStaffHud message, Supplier<NetworkEvent.Context> ctx)
    {
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
