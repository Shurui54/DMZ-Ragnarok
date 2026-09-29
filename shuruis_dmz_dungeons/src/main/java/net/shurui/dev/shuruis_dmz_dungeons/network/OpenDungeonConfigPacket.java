package net.shurui.dev.shuruis_dmz_dungeons.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// S2C: opens the /rg dungeon config GUI, prefilled with the current rules and ordered floor list. keyUnlocked
// tells the client whether procedural floors are available (Ragnarok Key gate); a keyless client hides the floor
// section, leaving only time limit / cooldown / pvp / ki-block editable. floors travel as NBT so the client
// needs no server-only state.
public class OpenDungeonConfigPacket {

    private final boolean keyUnlocked;
    private final boolean pvp;
    private final boolean kiBlockDestruction;
    private final boolean blockEditing;
    private final int timeLimitSeconds;
    private final int cooldownSeconds;
    private final List<CompoundTag> floors;

    public OpenDungeonConfigPacket(boolean keyUnlocked, boolean pvp, boolean kiBlockDestruction,
                                   boolean blockEditing, int timeLimitSeconds, int cooldownSeconds,
                                   List<CompoundTag> floors) {
        this.keyUnlocked = keyUnlocked;
        this.pvp = pvp;
        this.kiBlockDestruction = kiBlockDestruction;
        this.blockEditing = blockEditing;
        this.timeLimitSeconds = timeLimitSeconds;
        this.cooldownSeconds = cooldownSeconds;
        this.floors = floors;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(keyUnlocked);
        buf.writeBoolean(pvp);
        buf.writeBoolean(kiBlockDestruction);
        buf.writeBoolean(blockEditing);
        buf.writeInt(timeLimitSeconds);
        buf.writeInt(cooldownSeconds);
        buf.writeVarInt(floors.size());
        for (CompoundTag t : floors) {
            buf.writeNbt(t);
        }
    }

    public static OpenDungeonConfigPacket decode(FriendlyByteBuf buf) {
        boolean keyUnlocked = buf.readBoolean();
        boolean pvp = buf.readBoolean();
        boolean kiBlockDestruction = buf.readBoolean();
        boolean blockEditing = buf.readBoolean();
        int timeLimitSeconds = buf.readInt();
        int cooldownSeconds = buf.readInt();
        int n = buf.readVarInt();
        List<CompoundTag> floors = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            floors.add(buf.readNbt());
        }
        return new OpenDungeonConfigPacket(keyUnlocked, pvp, kiBlockDestruction, blockEditing,
                timeLimitSeconds, cooldownSeconds, floors);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        net.shurui.dev.shuruis_dmz_dungeons.client.ClientPacketHandler.openDungeonConfig(
                                keyUnlocked, pvp, kiBlockDestruction, blockEditing,
                                timeLimitSeconds, cooldownSeconds, floors)));
        ctx.get().setPacketHandled(true);
    }
}
