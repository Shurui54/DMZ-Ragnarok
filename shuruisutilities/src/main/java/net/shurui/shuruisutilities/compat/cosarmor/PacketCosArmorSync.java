package net.shurui.shuruisutilities.compat.cosarmor;

import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;

/**
 * Server -&gt; client: one player's whole Cosmetic Armor Reworked inventory as NBT, delivered over the SUITE's own
 * channel ({@code dmz_ragnarok:fe-network}) instead of Cosmetic Armor's.
 *
 * <p>WHY this exists at all. Cosmetic Armor's client is a pure push receiver: its only store is a
 * {@code LoadingCache<UUID, InventoryCosArmor>} written solely by inbound {@code PacketSyncCosArmor}, with no
 * client-side re-request anywhere, and its server never sends a joiner their OWN cosmetics (its login handler does
 * {@code if (other == player) continue;}). On a Velocity backend hop the client wipes that cache
 * ({@code ClientCache.invalidateAll()} on {@code LoggingOut}) and the destination's per-slot pushes never reach it,
 * so the wearer's own cosmetics vanish for them. Five attempts to re-broadcast over Cosmetic Armor's channel failed:
 * those bytes provably do not arrive after a hop. This channel does (tab list, ghosts and chat all survive a hop
 * today), so we carry the inventory ourselves and write it straight into Cosmetic Armor's client cache.
 *
 * <p>This class touches NO Cosmetic Armor type. It is registered unconditionally at mod construction so client and
 * server agree on the channel whether or not the optional mod is present; all reflection into Cosmetic Armor lives
 * behind {@link CosArmorClientSync}, which is only reached on the client, inside the dist guard below, and only when
 * the mod is actually loaded.
 */
public class PacketCosArmorSync implements ISUPacket
{
    public UUID target;
    public CompoundTag inventory;

    public PacketCosArmorSync() {}

    public PacketCosArmorSync(UUID target, CompoundTag inventory)
    {
        this.target = target;
        this.inventory = inventory;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUUID(target);
        buf.writeNbt(inventory);
    }

    public static PacketCosArmorSync decode(FriendlyByteBuf buf)
    {
        UUID id = buf.readUUID();
        CompoundTag tag = buf.readNbt();
        return new PacketCosArmorSync(id, tag);
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        // Client only. The method reference is not resolved on a dedicated server, so CosArmorClientSync (and the
        // Cosmetic Armor reflection it holds) is never classloaded there.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CosArmorClientSync.apply(target, inventory));
    }

    public static void handler(final PacketCosArmorSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> message.handle(context));
        context.setPacketHandled(true);
    }
}
