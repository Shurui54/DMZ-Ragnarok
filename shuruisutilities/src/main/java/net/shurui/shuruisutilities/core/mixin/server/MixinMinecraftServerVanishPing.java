package net.shurui.shuruisutilities.core.mixin.server;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import net.shurui.shuruisutilities.api.key.VanishHooks;

/**
 * Keeps vanished players out of the vanilla server-list ping, both the online number and the hover sample.
 *
 * <p>The server-list ping is answered with nobody logged in, so there is no "is this viewer vanished" question to
 * ask: it simply must not count or name a vanished player. In 1.20.1 the ping's player block is built by
 * {@code MinecraftServer#buildPlayerStatus}, which takes {@code this.playerList.getPlayers()} and uses both its
 * size (the count) and a random slice of it (the sample). Redirecting that one call to a vanished-free copy
 * corrects both at once and leaves the rest of vanilla's logic untouched.
 *
 * <p>This is the LOCAL count only, which is what a server-list ping has always shown; the network-wide {online}
 * figure the tab list reports is a separate, per-viewer path (see ModuleTabList / ShardTabList).
 */
@Mixin(MinecraftServer.class)
public class MixinMinecraftServerVanishPing
{

    @Redirect(method = "buildPlayerStatus",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;getPlayers()Ljava/util/List;"))
    private List<ServerPlayer> su$hideVanishedFromPing(PlayerList list)
    {
        List<ServerPlayer> visible = new ArrayList<>(list.getPlayers().size());
        for (ServerPlayer p : list.getPlayers())
        {
            if (!VanishHooks.isVanished(p.getUUID()))
                visible.add(p);
        }
        return visible;
    }
}
