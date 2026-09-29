package net.shurui.dev.sdu.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server -> client. Opens DMZ's own character/stats V-menu on the staff member's screen in a READ-ONLY
 * "viewing" mode showing the TARGET player's DragonMineZ progression, driven by {@code /dmzinfo}.
 *
 * <p>The payload is the target's {@code StatsData.save()} blob (DMZ's own serialize), the target's display
 * name and UUID. The client handler deserialises the blob into a detached {@code StatsData} held by
 * {@link net.shurui.dev.sdu.client.PlayerInfoView}, flips viewing mode on, and opens DMZ's
 * {@code CharacterStatsScreen}. While viewing mode is on, sdu mixins make DMZ's menu screens read the
 * detached data instead of the local player's, and block every DMZ client-to-server packet so nothing the
 * staff member clicks can modify their own or the target's data.
 *
 * <p>Client-only work goes through {@link DistExecutor} so no client class is loaded on a dedicated server.
 */
public class PlayerInfoOpenPacket {

    private final String targetName;
    private final UUID targetUuid;
    private final CompoundTag statsNbt;

    public PlayerInfoOpenPacket(String targetName, UUID targetUuid, CompoundTag statsNbt) {
        this.targetName = targetName;
        this.targetUuid = targetUuid;
        this.statsNbt = statsNbt;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(targetName == null ? "" : targetName);
        buf.writeUUID(targetUuid == null ? new UUID(0L, 0L) : targetUuid);
        buf.writeNbt(statsNbt);
    }

    public static PlayerInfoOpenPacket decode(FriendlyByteBuf buf) {
        String name = buf.readUtf();
        UUID uuid = buf.readUUID();
        CompoundTag nbt = buf.readNbt();
        return new PlayerInfoOpenPacket(name, uuid, nbt);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.dev.sdu.client.PlayerInfoView.open(targetName, targetUuid, statsNbt)));
        ctx.get().setPacketHandled(true);
    }
}
