package net.shurui.dev.shuruis_raid_bosses.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * S2C: start or stop the boss music loop on one client.
 *
 * <p>The server sends {@code play=true} with the encounter's {@code bossMusic} sound id when a fighter enters
 * the fight (and again if they reconnect mid-fight), and {@code play=false} when the fight ends for them (their
 * death, the boss dying, a wipe, leaving, or the encounter closing). One loop per client, never stacked:
 * {@code BossMusicClient} treats a start for the id already playing as a no-op and a stop as a clean cut.
 *
 * <p>Edge triggered, not a per-tick heartbeat: the server tracks who it has told, so a start is only ever sent
 * once per entry and a stop once per exit. The client owns suppression of vanilla background music and the
 * player's own on/off preference; the server just says when the fight is on.
 */
public class BossMusicPacket {

    private final boolean play;
    private final String soundId;

    public BossMusicPacket(boolean play, String soundId) {
        this.play = play;
        this.soundId = soundId == null ? "" : soundId;
    }

    public static BossMusicPacket start(String soundId) {
        return new BossMusicPacket(true, soundId);
    }

    public static BossMusicPacket stop() {
        return new BossMusicPacket(false, "");
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(play);
        buf.writeUtf(soundId);
    }

    public static BossMusicPacket decode(FriendlyByteBuf buf) {
        boolean play = buf.readBoolean();
        String soundId = buf.readUtf();
        return new BossMusicPacket(play, soundId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (play) {
                net.shurui.dev.shuruis_raid_bosses.client.BossMusicClient.start(soundId);
            } else {
                net.shurui.dev.shuruis_raid_bosses.client.BossMusicClient.stop();
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
