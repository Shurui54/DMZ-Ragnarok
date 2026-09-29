package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.MinecraftServer;

import com.dragonminez.common.network.S2C.RadarSyncS2C;

import net.shurui.shuruisutilities.compat.dmz.CrossShardRadar;

/**
 * Adds the balls hosted by OTHER shards to the radar packet, so a player sees a set network wide rather than only
 * the part in dimensions the local shard hosts.
 *
 * <p>Same seam and same reasoning as {@code MixinDmzRadarTotems}. {@code buildRadarPacket} gathers a set's
 * positions by looping the SET's own valid dimensions and resolving {@code server.getLevel(dim)}; a dimension
 * hosted by a different shard has no level here, so its balls never make the packet. Injecting after the loop, where
 * the whole packet exists, is where the cross-shard view can be folded in without widening any set's dimension list
 * (which also drives where DMZ SCATTERS balls). {@link CrossShardRadar#addRemoteTo} adds only the REMOTE dimensions
 * (local ones are already in the packet) and fuzzes normal-set positions exactly as local balls and totems are.
 *
 * <p>The packet's three collections are reached through {@link AccessorRadarSyncS2C}: the per-set map that custom
 * radars read, and the dedicated earth/namek lists that DMZ's own radars read.
 *
 * <p>remap=false: target, {@code buildRadarPacket} and the packet type are DMZ's own names. require=0 per the
 * standing rule, and the body catches Throwable, so any DMZ drift costs the cross-shard blips and leaves the rest of
 * the radar working rather than breaking the packet or mod load.
 */
@Mixin(targets = "com.dragonminez.server.events.DragonBallsHandler", remap = false)
public abstract class MixinDmzRadarCrossShard
{
    @Inject(method = "buildRadarPacket", at = @At("RETURN"), require = 0, remap = false)
    private static void su$addRemoteShardBalls(MinecraftServer server, CallbackInfoReturnable<RadarSyncS2C> cir)
    {
        try
        {
            RadarSyncS2C packet = cir.getReturnValue();
            if (packet == null || server == null)
            {
                return;
            }
            AccessorRadarSyncS2C access = (AccessorRadarSyncS2C) (Object) packet;
            CrossShardRadar.addRemoteTo(server, access.su$positionsBySet(), access.su$earthPositions(),
                    access.su$namekPositions());
        }
        catch (Throwable ignored)
        {
            // fail open: DMZ's own local radar picture still goes out, just without the cross-shard blips
        }
    }
}
