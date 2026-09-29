package net.shurui.dev.shuruis_raid_bosses.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_raid_bosses.data.RaidData;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef;
import net.shurui.dev.shuruis_raid_bosses.util.TextUtil;

import java.util.function.Supplier;

/** Client -&gt; server. Persist an edited raid definition (op only). */
public class SaveDefPacket {
    private final CompoundTag defNbt;

    public SaveDefPacket(CompoundTag defNbt) {
        this.defNbt = defNbt;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeNbt(defNbt);
    }

    public static SaveDefPacket decode(FriendlyByteBuf buf) {
        return new SaveDefPacket(buf.readNbt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp == null || !sp.hasPermissions(2)) return;
            RaidBossDef def = RaidBossDef.load(defNbt);
            if (def.id == null || def.id.isBlank()) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "editor.dmz_ragnarok.raid.save.empty_id"));
                return;
            }
            RaidData.get(sp.getServer()).putDef(def);
            sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "editor.dmz_ragnarok.raid.save.ok", TextUtil.color(def.name)));
        });
        ctx.get().setPacketHandled(true);
    }
}
