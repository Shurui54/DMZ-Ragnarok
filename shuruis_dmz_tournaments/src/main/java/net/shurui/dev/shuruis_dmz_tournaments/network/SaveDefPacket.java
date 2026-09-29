package net.shurui.dev.shuruis_dmz_tournaments.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_dmz_tournaments.data.TournamentData;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentDef;
import net.shurui.dev.shuruis_dmz_tournaments.util.TextUtil;

import java.util.function.Supplier;

/** C2S. Persist an edited tournament definition (op only). */
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
            TournamentDef def = TournamentDef.load(defNbt);
            if (def.id == null || def.id.isBlank()) {
                sp.sendSystemMessage(TextUtil.color("&cTournament id is empty; not saved."));
                return;
            }
            TournamentData.get(sp.getServer()).putDef(def);
            sp.sendSystemMessage(TextUtil.color("&aSaved tournament '" + def.name + "'."));
        });
        ctx.get().setPacketHandled(true);
    }
}
