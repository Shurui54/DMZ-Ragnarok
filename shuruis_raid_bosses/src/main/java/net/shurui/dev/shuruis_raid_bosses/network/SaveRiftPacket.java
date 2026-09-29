package net.shurui.dev.shuruis_raid_bosses.network;

import java.util.function.Supplier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.dev.shuruis_raid_bosses.rift.RiftDef;
import net.shurui.dev.shuruis_raid_bosses.rift.RiftDefs;
import net.shurui.dev.shuruis_raid_bosses.util.TextUtil;

/** Client -&gt; server. Persist an edited rift (op only). */
public class SaveRiftPacket {

    private final CompoundTag riftNbt;

    public SaveRiftPacket(CompoundTag riftNbt) {
        this.riftNbt = riftNbt;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeNbt(riftNbt);
    }

    public static SaveRiftPacket decode(FriendlyByteBuf buf) {
        return new SaveRiftPacket(buf.readNbt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp == null || !sp.hasPermissions(2)) {
                return;
            }
            RiftDef def = RiftDef.load(riftNbt);
            if (def.id == null || def.id.isBlank()) {
                sp.sendSystemMessage(Component.translatable("editor.dmz_ragnarok.rift.save.empty_id"));
                return;
            }
            RiftDefs.get(sp.getServer()).putDef(def);
            // Drop the timer so an edited interval takes effect now, not after the wait rolled under the
            // old config (for a rift measured in hours, the difference between "worked" and "did nothing").
            net.shurui.dev.shuruis_raid_bosses.api.key.RaidKeyHooks.get().riftEdited(def.id);
            sp.sendSystemMessage(Component.translatable("editor.dmz_ragnarok.rift.save.ok",
                    TextUtil.color(def.name)));
        });
        ctx.get().setPacketHandled(true);
    }
}
