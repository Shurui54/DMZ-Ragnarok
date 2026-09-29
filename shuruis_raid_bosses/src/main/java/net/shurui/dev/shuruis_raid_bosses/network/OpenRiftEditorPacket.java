package net.shurui.dev.shuruis_raid_bosses.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * S2C: opens the rift editor with every rift, plus two lists the screens cannot derive: the raid ids a
 * rift may point at, and the NPC region names it may open in. Both are server-only (SavedData, and
 * Shurui's Utilities), and free-text fields are how a rift ends up pointing at a raid that does not exist.
 */
public class OpenRiftEditorPacket {

    private final List<CompoundTag> rifts;
    // Whole raids, not just ids: opening the encounter editor of a rift that still POINTS at a raid seeds
    // the encounter as a copy of that raid, which needs the definition.
    private final List<CompoundTag> raids;
    private final List<String> regionNames;

    public OpenRiftEditorPacket(List<CompoundTag> rifts, List<CompoundTag> raids, List<String> regionNames) {
        this.rifts = rifts;
        this.raids = raids;
        this.regionNames = regionNames;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(rifts.size());
        for (CompoundTag t : rifts) {
            buf.writeNbt(t);
        }
        buf.writeVarInt(raids.size());
        for (CompoundTag t : raids) {
            buf.writeNbt(t);
        }
        buf.writeVarInt(regionNames.size());
        for (String s : regionNames) {
            buf.writeUtf(s);
        }
    }

    public static OpenRiftEditorPacket decode(FriendlyByteBuf buf) {
        int riftCount = buf.readVarInt();
        List<CompoundTag> rifts = new ArrayList<>(riftCount);
        for (int i = 0; i < riftCount; i++) {
            rifts.add(buf.readNbt());
        }
        int raidCount = buf.readVarInt();
        List<CompoundTag> raids = new ArrayList<>(raidCount);
        for (int i = 0; i < raidCount; i++) {
            raids.add(buf.readNbt());
        }
        int regionCount = buf.readVarInt();
        List<String> regions = new ArrayList<>(regionCount);
        for (int i = 0; i < regionCount; i++) {
            regions.add(buf.readUtf());
        }
        return new OpenRiftEditorPacket(rifts, raids, regions);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> net.shurui.dev.shuruis_raid_bosses.client.ClientPacketHandler
                                .openRiftEditor(rifts, raids, regionNames)));
        ctx.get().setPacketHandled(true);
    }
}
