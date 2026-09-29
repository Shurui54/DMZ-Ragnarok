package net.shurui.dev.shuruis_raid_bosses.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_raid_bosses.data.RaidData;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Client -&gt; server. A hub-menu button asks the server to gather data and open one editor. Op-gated. */
public class OpenEditorRequestPacket {

    private final String which;

    public OpenEditorRequestPacket(String which) {
        this.which = which;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(which);
    }

    public static OpenEditorRequestPacket decode(FriendlyByteBuf buf) {
        return new OpenEditorRequestPacket(buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !player.hasPermissions(2)) {
                return;
            }
            switch (which) {
                case "raid" -> {
                    List<CompoundTag> defs = new ArrayList<>();
                    for (RaidBossDef def : RaidData.get(player.getServer()).allDefs().values()) {
                        defs.add(def.save());
                    }
                    RaidNet.sendToPlayer(new OpenEditorPacket(defs), player);
                }
                case "rift" -> {
                    List<CompoundTag> rifts = new ArrayList<>();
                    for (net.shurui.dev.shuruis_raid_bosses.rift.RiftDef def
                            : net.shurui.dev.shuruis_raid_bosses.rift.RiftDefs.get(player.getServer())
                            .allDefs().values()) {
                        rifts.add(def.save());
                    }
                    List<CompoundTag> raids = new ArrayList<>();
                    for (RaidBossDef def : RaidData.get(player.getServer()).allDefs().values()) {
                        if (def.id != null && !def.id.isBlank()) {
                            raids.add(def.save());
                        }
                    }
                    List<String> regions = new ArrayList<>();
                    for (net.shurui.shuruisutilities.npcregion.NpcRegion region
                            : net.shurui.shuruisutilities.npcregion.NpcRegionManager.instance().all()) {
                        if (region != null && region.name != null && !region.name.isBlank()) {
                            regions.add(region.name);
                        }
                    }
                    RaidNet.sendToPlayer(new OpenRiftEditorPacket(rifts, raids, regions), player);
                }
                default -> {
                }
            }
        });
        context.setPacketHandled(true);
    }
}
