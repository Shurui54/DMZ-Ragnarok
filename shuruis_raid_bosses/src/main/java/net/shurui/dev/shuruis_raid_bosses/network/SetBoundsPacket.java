package net.shurui.dev.shuruis_raid_bosses.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_raid_bosses.data.RaidData;
import net.shurui.dev.shuruis_raid_bosses.region.Region;
import net.shurui.dev.shuruis_raid_bosses.region.WorldEditBridge;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef;

import java.util.function.Supplier;

/**
 * C2S "set the arena from my WorldEdit selection": server reads the sender's WorldEdit cuboid, stores it
 * on the definition, replies with the updated definition so the editor refreshes.
 */
public class SetBoundsPacket {
    private final String defId;
    private final String regionKey;

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
            RaidBossDef def = RaidData.get(sp.getServer()).getDef(defId);
            if (def == null) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "editor.dmz_ragnarok.raid.bounds.save_first"));
                return;
            }
            BlockPos[] sel = WorldEditBridge.getSelection(sp);
            if (sel == null) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "editor.dmz_ragnarok.raid.bounds.no_selection"));
                RaidNet.sendToPlayer(new BoundsResultPacket(defId, regionKey, false, def.save()), sp);
                return;
            }
            // Arena is flattened to the full build column, keeping only the selection's X/Z (it is an X/Z
            // containment box players surface-snap inside, so any-height selections work). The player spawn
            // keeps the selection EXACTLY as drawn including its Y, a specific place an admin chose.
            boolean spawnKey = "playerspawn".equals(regionKey);
            BlockPos a;
            BlockPos b;
            if (spawnKey) {
                a = sel[0];
                b = sel[1];
            } else {
                int minY = sp.serverLevel().getMinBuildHeight();
                int maxY = sp.serverLevel().getMaxBuildHeight() - 1;
                a = new BlockPos(sel[0].getX(), minY, sel[0].getZ());
                b = new BlockPos(sel[1].getX(), maxY, sel[1].getZ());
            }
            Region region = new Region(sp.serverLevel().dimension(), a, b);
            if ("arena".equals(regionKey)) def.arena = region;
            else if (spawnKey) def.playerSpawn = region;
            RaidData.get(sp.getServer()).putDef(def);
            sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "editor.dmz_ragnarok.raid.bounds.set", regionKey, region.toString()));
            RaidNet.sendToPlayer(new BoundsResultPacket(defId, regionKey, true, def.save()), sp);
        });
        ctx.get().setPacketHandled(true);
    }
}
