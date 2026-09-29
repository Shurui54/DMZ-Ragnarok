package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.shard.DimensionHandoff;
import net.shurui.shuruisutilities.shard.ShardConfig;
import net.shurui.shuruisutilities.shard.ShardDimensions;
import net.shurui.shuruisutilities.shard.ShardTransfer;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Hands an Instant Transmission across shards instead of letting it silently no-op.
 *
 * <h2>The bug this closes</h2>
 * DMZ's waypoint / master Instant Transmission ({@code InstantTransmissionTravelC2S}) finishes by calling
 * {@code ServerPlayer.teleportTo(ServerLevel, ...)}. Forge patches that method to go through
 * {@code ForgeHooks.onTravelToDimension}, so {@link ShardDimensions#onTravel} cancels a hop into a dimension this
 * shard does not host, and a cancelled cross-dimension teleport is a SILENT no-op at the call site: the player does
 * not move and gets no exception. DMZ deducts the ki and starts the teleport cooldown BEFORE that call, so a player
 * on the SMP shard IT-ing to an overworld waypoint (the overworld lives on the OW shards) pays full ki plus cooldown
 * and stays put.
 *
 * <h2>Why a redirect on the teleport, not an earlier hook</h2>
 * We intercept the {@code teleportTo} INVOKE itself, the very last thing the handler does, so ALL of DMZ's own logic
 * has already run: the skill-level gate, the master lookup, the same-dimension / too-far checks, the ki and cooldown
 * spend, and the dismount. The player has paid the cost and expressed the intent; we only change WHERE the move
 * lands. When the target is hosted here nothing changes at all, we call the real teleport. When it is not, we record
 * the destination on the player's persistent NBT ({@link DimensionHandoff}) and hand them to the shard that owns the
 * dimension ({@link ShardTransfer#connect}), where {@code DimensionHandoffEvents} finishes the teleport on arrival. The cost
 * DMZ already charged is exactly right: the player really is being teleported, just onto another server.
 *
 * <h2>The injection target is a lambda</h2>
 * The handler body is inside {@code enqueueWork(() -> ...)} and then inside a {@code LazyOptional.ifPresent(data ->
 * ...)}, so the {@code teleportTo} call lives in the synthetic {@code lambda$handle$0(ServerPlayer, StatsData)}, NOT
 * in {@code handle}. Confirmed by disassembling the shipped jar: {@code javap -p -c} shows the
 * {@code ServerPlayer.m_8999_} (teleportTo) invoke at offset 428 inside a method that loads the {@code ServerPlayer}
 * as its first parameter and reads {@code this.masterId}, which is {@code lambda$handle$0}. The name is unique in the
 * class so the {@code method} selector needs no descriptor.
 *
 * <p>{@code remap = false} on the mixin and the redirect: {@code InstantTransmissionTravelC2S} and the synthetic
 * lambda name are DMZ's own, never remapped. But the {@code @At} target is a Minecraft method, so it carries
 * {@code remap = true} of its own: written with the official name {@code teleportTo} here, it matches the deobfuscated
 * names in a dev run and the refmap rewrites it to {@code m_8999_} at runtime. {@code require = 0} per the standing
 * DMZ mixin rule, so if DMZ restructures the handler the injector degrades to DMZ's own (silently cancelled) teleport
 * rather than failing mod load. A green build does NOT prove this bound, so this must be launch-tested against 2.1.3.
 */
@Mixin(targets = "com.dragonminez.common.network.C2S.InstantTransmissionTravelC2S", remap = false)
public abstract class MixinDmzInstantTransmissionHandoff
{
    @Redirect(
            method = "lambda$handle$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V",
                    remap = true),
            require = 0,
            remap = false)
    private void su$handoffInstantTransmission(ServerPlayer player, ServerLevel targetLevel,
            double x, double y, double z, float yaw, float pitch)
    {
        ResourceKey<Level> dim = targetLevel.dimension();
        try
        {
            // Hosted here: nothing to hand off, behave exactly as DMZ does.
            if (ShardDimensions.hosts(dim))
            {
                player.teleportTo(targetLevel, x, y, z, yaw, pitch);
                return;
            }
            String owner = ShardDimensions.owner(dim);
            String self = ShardConfig.get().serverId;
            // No recorded owner, or the owner is somehow us: fall through to the normal call so behaviour is
            // unchanged (that call is the one Forge cancels, but that is the pre-existing behaviour, not ours to
            // paper over here). Only a DIFFERENT, named owner is worth a cross-server hop.
            if (owner == null || owner.isBlank() || owner.equalsIgnoreCase(self))
            {
                player.teleportTo(targetLevel, x, y, z, yaw, pitch);
                return;
            }
            // Record the destination on the player's persistent NBT (rides the vault payload across the reconnect)
            // and ask the proxy to move them. DimensionHandoffEvents finishes the teleport on arrival.
            DimensionHandoff.mark(player, dim, x, y, z, yaw, pitch);
            ShardTransfer.connect(player, owner);
            LoggingHandler.sulog.info("[shard] Handing {} to '{}' to finish an Instant Transmission into {}.",
                    player.getGameProfile().getName(), owner, dim.location());
        }
        catch (Throwable t)
        {
            // Never make IT worse than DMZ's own behaviour: on any failure, do the normal teleport (which may be
            // cancelled, exactly as before this mixin existed) rather than eating the move here.
            LoggingHandler.sulog.error("[shard] Could not hand off an Instant Transmission for {}: {}",
                    player.getGameProfile().getName(), t.toString());
            player.teleportTo(targetLevel, x, y, z, yaw, pitch);
        }
    }
}
