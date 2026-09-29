package net.shurui.dev.shuruis_dmz_dungeons.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_dmz_dungeons.block.CrateMetal;
import net.shurui.dev.shuruis_dmz_dungeons.block.DungeonCrateLoot;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloorConfig;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloors;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// S2C: per-floor crate tier inputs the client colour handler needs to paint each crate WITHOUT a per-block packet.
// Rarity is rolled per player per refresh window from (position, epoch, viewer UUID, weights); the client knows
// position, epoch and its UUID, so this carries only what it cannot derive: each floor's tier WEIGHTS, refresh
// window and THEME (body tint). Sent once on dungeon entry (and re-login inside one), keyed by floor number; the
// client resolves a crate's floor from its X (DungeonFloorLayout).
public class SyncCrateTiersPacket {

    // one floor's colour inputs. weights = four tier weights in CrateTier ordinal order; refreshHours slices the
    // epoch; theme drives the body (tintindex 1) palette. Plain data, no client types, so the packet stays
    // loadable on the dedicated server. metalWeights rides alongside so the client picks a crate's MODEL
    // (rarity x metal) itself, nothing stored per container.
    public record CrateTierInfo(int floor, String theme, int[] weights, int[] metalWeights, int refreshHours) {
    }

    private final List<CrateTierInfo> entries;

    public SyncCrateTiersPacket(List<CrateTierInfo> entries) {
        this.entries = entries;
    }

    // build the payload for every configured floor from the live DungeonFloors table (server side).
    public static SyncCrateTiersPacket forAllFloors(DungeonFloors floors) {
        List<CrateTierInfo> list = new ArrayList<>();
        int count = floors.count();
        for (int floor = 1; floor <= count; floor++) {
            DungeonFloorConfig config = floors.get(floor);
            if (config == null) {
                continue;
            }
            DungeonCrateLoot loot = config.crateLoot != null ? config.crateLoot : new DungeonCrateLoot();
            list.add(new CrateTierInfo(floor, config.theme == null ? "OVERWORLD" : config.theme,
                    loot.weights(), loot.metalWeights(), loot.refreshHours));
        }
        return new SyncCrateTiersPacket(list);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (CrateTierInfo e : entries) {
            buf.writeVarInt(e.floor());
            buf.writeUtf(e.theme());
            int[] w = e.weights();
            for (int i = 0; i < DungeonCrateLoot.TIER_COUNT; i++) {
                buf.writeVarInt(i < w.length ? w[i] : 0);
            }
            int[] mw = e.metalWeights();
            for (int i = 0; i < CrateMetal.METAL_COUNT; i++) {
                buf.writeVarInt(i < mw.length ? mw[i] : 0);
            }
            buf.writeVarInt(e.refreshHours());
        }
    }

    public static SyncCrateTiersPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<CrateTierInfo> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int floor = buf.readVarInt();
            String theme = buf.readUtf();
            int[] w = new int[DungeonCrateLoot.TIER_COUNT];
            for (int j = 0; j < DungeonCrateLoot.TIER_COUNT; j++) {
                w[j] = buf.readVarInt();
            }
            int[] mw = new int[CrateMetal.METAL_COUNT];
            for (int j = 0; j < CrateMetal.METAL_COUNT; j++) {
                mw[j] = buf.readVarInt();
            }
            int refreshHours = buf.readVarInt();
            list.add(new CrateTierInfo(floor, theme, w, mw, refreshHours));
        }
        return new SyncCrateTiersPacket(list);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        net.shurui.dev.shuruis_dmz_dungeons.client.ClientPacketHandler.applyCrateTiers(entries)));
        ctx.get().setPacketHandled(true);
    }
}
