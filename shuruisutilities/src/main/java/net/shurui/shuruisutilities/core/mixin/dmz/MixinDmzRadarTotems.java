package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.MinecraftServer;

import com.dragonminez.common.network.S2C.RadarSyncS2C;

import net.shurui.shuruisutilities.compat.dmz.RadarForeignDimensionBalls;
import net.shurui.shuruisutilities.compat.dmz.RadarTotems;

/**
 * Adds the grave totems holding dragon balls to the radar packet, so a ball a player died or logged out with stays
 * findable rather than vanishing from every radar.
 *
 * <p>WHY at the packet and not at the per-set seam. {@code buildRadarPacket} gathers each set's positions by looping
 * over that SET's own valid dimensions, so a set is only ever asked about the dimensions it scatters in. Super
 * scatters on planet surfaces only, which is exactly why a super totem in the overworld produced no blip while an
 * earth one did. Injecting after the loop, where the whole packet exists and every level is reachable, is the only
 * place that covers a totem wherever a player happened to die. Widening the set's dimension list would have
 * "fixed" the radar by making DMZ scatter a second set into the overworld.
 *
 * <p>The packet's three collections are reached through {@link AccessorRadarSyncS2C}; both the per-set map and the
 * dedicated earth/namek lists are updated, because DMZ's own earth and namek radars read the latter while custom
 * radars read the former. {@link RadarTotems} fuzzes totem positions exactly as the balls are fuzzed.
 *
 * <p>This same inject also folds in loose balls that sit in a dimension OUTSIDE a set's own scatter list, via
 * {@link RadarForeignDimensionBalls}, for the same reason and at the same seam: the per-set loop in
 * {@code buildRadarPacket} only asks each set about its home dimensions, so a ball registered elsewhere is invisible
 * until it is added here where every level is reachable. The two additions are disjoint (totems are grave containers,
 * this reads {@code DragonBallSavedData}) and neither double counts DMZ's own home-dimension gather.
 *
 * <p>remap=false: the target, {@code buildRadarPacket} and the packet type are all DMZ's own names. require=0 per
 * the standing rule, and the body catches Throwable, so any DMZ drift costs the totem blips and leaves the rest of
 * the radar working rather than breaking the packet or mod load.
 */
@Mixin(targets = "com.dragonminez.server.events.DragonBallsHandler", remap = false)
public abstract class MixinDmzRadarTotems
{
    @Inject(method = "buildRadarPacket", at = @At("RETURN"), require = 0, remap = false)
    private static void su$addTotemBalls(MinecraftServer server, CallbackInfoReturnable<RadarSyncS2C> cir)
    {
        try
        {
            RadarSyncS2C packet = cir.getReturnValue();
            if (packet == null || server == null)
            {
                return;
            }
            AccessorRadarSyncS2C access = (AccessorRadarSyncS2C) (Object) packet;
            RadarTotems.addTo(server, access.su$positionsBySet(), access.su$earthPositions(),
                    access.su$namekPositions());
            // Also fold in loose balls that sit in a dimension outside the set's own scatter list (a ball placed or
            // grave-restored somewhere the set does not scatter), so every radar can point at every ball wherever it
            // ended up. Only locally hosted, non-home dimensions are swept, so this never double counts DMZ's own
            // home-dimension gather and never overlaps the cross-shard additions.
            RadarForeignDimensionBalls.addTo(server, access.su$positionsBySet(), access.su$earthPositions(),
                    access.su$namekPositions());
        }
        catch (Throwable ignored)
        {
            // fail open: DMZ's own radar picture still goes out, just without the totems
        }
    }
}
