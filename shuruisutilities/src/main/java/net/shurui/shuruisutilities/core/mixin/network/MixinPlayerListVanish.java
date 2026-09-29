package net.shurui.shuruisutilities.core.mixin.network;

import com.mojang.authlib.GameProfile;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.api.key.VanishHooks;
import net.shurui.shuruisutilities.permissions.PermissionSettings;

/**
 * Suppresses the yellow "multiplayer.player.joined" broadcast from {@link PlayerList#placeNewPlayer} when the
 * (re)connecting player is vanished. We read the PERSISTED vanish state (via {@link VanishHooks#isVanished})
 * because at this point in login the in-memory set may not have been repopulated yet, so the persisted store is
 * the source of truth. Only the join system message is affected; every other broadcast passes through
 * untouched.
 *
 * <p>The redirect swallows exactly the one {@code broadcastSystemMessage(Component, boolean)} call inside
 * {@code placeNewPlayer}; the joining player is read from the method's own {@code ServerPlayer} parameter, which
 * a Mixin redirect appends after the redirected call's arguments.
 */
@Mixin(PlayerList.class)
public class MixinPlayerListVanish
{

    @Redirect(method = "placeNewPlayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void su$suppressVanishedJoinMessage(PlayerList list, Component message, boolean overlay,
            Connection connection, ServerPlayer joining)
    {
        if (joining != null && VanishHooks.isVanished(joining.getUUID()))
        {
            // Vanished: do not tell the whole server. Other vanished staff CAN see this player, so they get the
            // join line; ordinary players (who never see the player) get nothing.
            VanishHooks.announcePresenceToSeers(joining, message);
            return;
        }
        list.broadcastSystemMessage(message, overlay);
    }

    /**
     * Lets a connecting player past the "server full" check in {@code canPlayerLogin} when they either hold the SU
     * permission {@code su.playerlimit.bypass} OR have a Patreon tier that grants the
     * {@link net.shurui.shuruisutilities.patreon.PatreonAPI#REWARD_PLAYER_LIMIT_BYPASS} reward, so donators/staff who
     * are not ops can still join at max-players. Both are checked offline by UUID because the player entity does not
     * exist yet at this point.
     *
     * <p>The Patreon path reads the grace-aware entitlement cache: a supporter confirmed within the grace window
     * still gets in even if the backend is currently unreachable, while a player who was never confirmed (empty or
     * expired cache) resolves to no tier and is NOT admitted. So a backend outage never lets a non-supporter past the
     * cap; it only keeps letting in someone who was already a known supporter.
     *
     * <p>Only the full-limit path is touched. If neither grant applies, or a subsystem is not ready, we do nothing
     * and let vanilla run (ops.json {@code bypassesPlayerLimit} plus the normal full check still apply). Bans and
     * whitelist are handled elsewhere in {@code canPlayerLogin} and are untouched.
     */
    @Inject(method = "canBypassPlayerLimit", at = @At("HEAD"), cancellable = true, remap = true)
    private void su$permissionBypassesPlayerLimit(GameProfile profile, CallbackInfoReturnable<Boolean> cir)
    {
        try
        {
            if (profile == null || profile.getId() == null)
                return;
            if (APIRegistry.perms != null)
            {
                UserIdent ident = UserIdent.get(profile);
                if (ident != null && APIRegistry.perms.checkUserPermission(ident, PermissionSettings.PERM_PLAYERLIMIT_BYPASS))
                {
                    cir.setReturnValue(true);
                    return;
                }
            }
            if (net.shurui.shuruisutilities.patreon.PatreonAPI.hasReward(profile.getId(),
                    net.shurui.shuruisutilities.patreon.PatreonAPI.REWARD_PLAYER_LIMIT_BYPASS))
                cir.setReturnValue(true);
        }
        catch (Throwable t)
        {
            // Never crash login: if a subsystem chokes, just fall through to vanilla logic.
        }
    }
}
