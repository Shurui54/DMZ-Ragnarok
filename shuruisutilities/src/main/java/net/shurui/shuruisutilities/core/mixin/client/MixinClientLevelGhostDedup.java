package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;

import net.shurui.shuruisutilities.client.shard.GhostManager;
import net.shurui.shuruisutilities.client.shard.GhostPlayer;

/**
 * Closes the cross-shard ghost invisibility race at the exact point it happens: the moment a REAL player entity is
 * added to the client level.
 *
 * <h2>The race</h2>
 * A ghost is a real client entity built with the remote player's REAL uuid, and vanilla's {@code EntityLookup} keeps
 * one entity per uuid: a second arrival under a held uuid is logged {@code Duplicate entity UUID} and DROPPED. So
 * while a client holds a ghost of somebody who then arrives on this shard for real, that player's own add packet is
 * thrown away and they are invisible, nameless and untargetable until a re-track (leave tracking range and come back,
 * which is what "tp away and back" forces manually).
 *
 * <p>{@code ShardGhosts.onlyThoseNotHere} (server) and {@code GhostManager.accept}'s present-player skip (client)
 * already stop a NEW ghost being drawn over a present player, but both act on the ten-hertz ghost stream. The real
 * add can land in the window between two ghost updates, or while the server's guard missed a beat to a deadlock. This
 * hook removes any ghost for the incoming uuid FIRST, so the uuid is free before the real player is inserted, whatever
 * order the ghost despawn and the real add would otherwise have arrived in.
 *
 * <p>{@code addPlayer} is the client level's own entry point for remote players (from
 * {@code ClientPacketListener.handleAddPlayer}) and runs on the client thread, the same thread that owns the ghost
 * map, so no cross-thread work is involved. A {@link GhostPlayer} being (re)added is ignored, so a ghost never drops
 * itself.
 */
@Mixin(ClientLevel.class)
public class MixinClientLevelGhostDedup
{
    @Inject(method = "addPlayer", at = @At("HEAD"), require = 0)
    private void su$dropGhostBeforeRealPlayer(int id, AbstractClientPlayer player, CallbackInfo ci)
    {
        if (player instanceof GhostPlayer)
            return;
        GhostManager.onRealPlayerJoin(player.getUUID());
    }
}
