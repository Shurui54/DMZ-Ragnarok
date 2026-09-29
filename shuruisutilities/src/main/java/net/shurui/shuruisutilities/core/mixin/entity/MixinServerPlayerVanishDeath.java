package net.shurui.shuruisutilities.core.mixin.entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import net.shurui.shuruisutilities.api.key.VanishHooks;

/**
 * Keeps a vanished player's death message off ordinary players' screens, matching the same per-viewer rule vanish
 * uses everywhere else: other vanished staff see it, ordinary players do not.
 *
 * <p>In 1.20.1 the death broadcast lives in {@code ServerPlayer#die}, which calls
 * {@code PlayerList#broadcastSystemMessage(Component, boolean)} once for the ordinary (non-team) case. The dying
 * player has already been sent their own {@code ClientboundPlayerCombatKillPacket} (their death screen) before this
 * call, so routing the broadcast to the seers only takes the line off ordinary players without hiding it from the
 * player who died. Reads the persisted/replicated vanish state via {@link VanishHooks#isVanished}.
 *
 * <p>Death messages are LOCAL: nothing in the suite republishes them across shards, so filtering here is the whole
 * fix. This deliberately does not touch the two team-visibility paths ({@code broadcastSystemToTeam} /
 * {@code broadcastSystemToAllExceptTeam}), which are a different target and only used when a vanished player is on a
 * scoreboard team with a non-default death message visibility, a combination this suite does not use.
 */
@Mixin(ServerPlayer.class)
public class MixinServerPlayerVanishDeath
{

    @Redirect(method = "die",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void su$suppressVanishedDeathMessage(PlayerList list, Component message, boolean overlay)
    {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (VanishHooks.isVanished(self.getUUID()))
        {
            // Vanished: do not tell the whole server. Other vanished staff can see this player, so they get the
            // death line; ordinary players (who never see the player) get nothing.
            VanishHooks.announcePresenceToSeers(self, message);
            return;
        }
        list.broadcastSystemMessage(message, overlay);
    }
}
