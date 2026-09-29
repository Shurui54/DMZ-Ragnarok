package net.shurui.dev.shuruis_dmz_tournaments.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_dmz_tournaments.data.TournamentData;
import net.shurui.dev.shuruis_dmz_tournaments.region.Region;
import net.shurui.dev.shuruis_dmz_tournaments.region.WorldEditBridge;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentDef;
import net.shurui.dev.shuruis_dmz_tournaments.util.TextUtil;

import java.util.function.Supplier;

/**
 * C2S, "set bounds from my WorldEdit selection" for a tournament region. Server reads the sender's current
 * WorldEdit cuboid, stores it on the def, and replies with the updated def so the editor refreshes.
 */
public class SetBoundsPacket {
    private final String defId;
    private final String regionKey; // arena / waiting / stands

    public SetBoundsPacket(String defId, String regionKey) {
        this.defId = defId;
        this.regionKey = regionKey;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(defId);
        buf.writeUtf(regionKey);
    }

    public static SetBoundsPacket decode(FriendlyByteBuf buf) {
        return new SetBoundsPacket(buf.readUtf(), buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp == null || !sp.hasPermissions(2)) return;
            TournamentDef def = TournamentData.get(sp.getServer()).getDef(defId);
            if (def == null) {
                sp.sendSystemMessage(TextUtil.color("&cSave the tournament first, then set bounds."));
                return;
            }
            BlockPos[] sel = WorldEditBridge.getSelection(sp);
            if (sel == null) {
                sp.sendSystemMessage(TextUtil.color("&cNo WorldEdit selection found. Make a cuboid selection first (//wand)."));
                TournamentNet.sendToPlayer(new BoundsResultPacket(defId, regionKey, false, def.save()), sp);
                return;
            }
            Region region = new Region(sp.serverLevel().dimension(), sel[0], sel[1]);
            switch (regionKey) {
                case "arena" -> def.arena = region;
                case "waiting" -> def.waiting = region;
                case "stands" -> def.stands = region;
                default -> { }
            }
            TournamentData.get(sp.getServer()).putDef(def);
            sp.sendSystemMessage(TextUtil.color("&a" + regionKey + " bounds set: " + region));
            TournamentNet.sendToPlayer(new BoundsResultPacket(defId, regionKey, true, def.save()), sp);
        });
        ctx.get().setPacketHandled(true);
    }
}
