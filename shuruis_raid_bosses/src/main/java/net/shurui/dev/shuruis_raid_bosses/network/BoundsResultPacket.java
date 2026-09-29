package net.shurui.dev.shuruis_raid_bosses.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C result of a {@link SetBoundsPacket}: the refreshed definition so the open edit screen updates its
 * "bounds set" indicator without leaving the editor.
 */
public class BoundsResultPacket {
    private final String defId;
    private final String regionKey;
    private final boolean success;
    private final CompoundTag defNbt;

    public BoundsResultPacket(String defId, String regionKey, boolean success, CompoundTag defNbt) {
        this.defId = defId;
        this.regionKey = regionKey;
        this.success = success;
        this.defNbt = defNbt;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(defId);
        buf.writeUtf(regionKey);
        buf.writeBoolean(success);
        buf.writeNbt(defNbt);
    }

    public static BoundsResultPacket decode(FriendlyByteBuf buf) {
        return new BoundsResultPacket(buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readNbt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> net.shurui.dev.shuruis_raid_bosses.client.ClientPacketHandler
                                .boundsResult(defId, regionKey, success, defNbt)));
        ctx.get().setPacketHandled(true);
    }
}
