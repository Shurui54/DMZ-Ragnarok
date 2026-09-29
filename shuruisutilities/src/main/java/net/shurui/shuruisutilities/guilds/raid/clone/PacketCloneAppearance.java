package net.shurui.shuruisutilities.guilds.raid.clone;

import java.util.UUID;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client APPEARANCE CAPSULE for one guild-raid clone puppet. DragonMineZ never syncs a {@link
 * com.dragonminez.common.stats.StatsData} capability for a non-player UUID, and its player renderer resolves race,
 * customisation and active form entirely off that capability, so to make a clone render with its source member's
 * true appearance the client must be handed that member's stats itself. This packet carries exactly that: the id
 * of the driver entity the puppet slaves to, the source member's UUID and name (so the vanilla skin resolves and
 * a tab-list entry can be built), and the source member's raw {@code StatsData.save()} NBT (race, colours, hair,
 * selected form).
 *
 * <p>The whole client side is behind {@link DistExecutor} so none of the puppet classes ever load on a dedicated
 * server, and the handler runs on the client thread. If anything about the appearance fails on the client, the
 * server-side driver keeps fighting unaffected: this packet is a pure decoration channel.
 *
 * <p>ACTION: {@link #action} is either {@link #ACTION_SPAWN} (register/refresh a puppet for {@code cloneId}) or
 * {@link #ACTION_REMOVE} (drop the puppet for {@code cloneId}); on remove the NBT and name fields are empty.
 */
public class PacketCloneAppearance implements ISUPacket
{
    public static final byte ACTION_SPAWN = 0;
    public static final byte ACTION_REMOVE = 1;

    private byte action;
    private int cloneEntityId;
    private UUID sourceMember;
    private String sourceName;
    private CompoundTag statsNbt;

    public PacketCloneAppearance()
    {
    }

    /** Build a SPAWN capsule: bind a puppet for {@code cloneEntityId} to the source member's appearance. */
    public static PacketCloneAppearance spawn(int cloneEntityId, UUID sourceMember, String sourceName,
                                              CompoundTag statsNbt)
    {
        PacketCloneAppearance p = new PacketCloneAppearance();
        p.action = ACTION_SPAWN;
        p.cloneEntityId = cloneEntityId;
        p.sourceMember = sourceMember;
        p.sourceName = sourceName == null ? "" : sourceName;
        p.statsNbt = statsNbt == null ? new CompoundTag() : statsNbt;
        return p;
    }

    /** Build a REMOVE capsule: drop the puppet bound to {@code cloneEntityId}. */
    public static PacketCloneAppearance remove(int cloneEntityId)
    {
        PacketCloneAppearance p = new PacketCloneAppearance();
        p.action = ACTION_REMOVE;
        p.cloneEntityId = cloneEntityId;
        p.sourceMember = new UUID(0L, 0L);
        p.sourceName = "";
        p.statsNbt = new CompoundTag();
        return p;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeByte(action);
        buf.writeVarInt(cloneEntityId);
        buf.writeUUID(sourceMember);
        buf.writeUtf(sourceName);
        buf.writeNbt(statsNbt);
    }

    public static PacketCloneAppearance decode(FriendlyByteBuf buf)
    {
        PacketCloneAppearance p = new PacketCloneAppearance();
        p.action = buf.readByte();
        p.cloneEntityId = buf.readVarInt();
        p.sourceMember = buf.readUUID();
        p.sourceName = buf.readUtf();
        p.statsNbt = buf.readNbt();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // Client-only: hand off to the puppet manager, which lives entirely in the client package so it never loads
        // on a server. Any failure inside is swallowed there so it cannot break the fight or crash the client.
        final byte a = action;
        final int id = cloneEntityId;
        final UUID member = sourceMember;
        final String name = sourceName;
        final CompoundTag nbt = statsNbt;
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.guilds.raid.clone.client.GuildRaidPuppetManager
                        .accept(a, id, member, name, nbt));
    }

    public static void handler(final PacketCloneAppearance message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
