package net.shurui.shuruisutilities.core.mixin.dmz;

import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import com.dragonminez.common.network.S2C.RadarSyncS2C;

import net.shurui.shuruisutilities.compat.dmz.BallDormancy;

/**
 * Strips a DORMANT ball set's positions from the radar packet, so a set turned to stone by a wish cannot be found
 * on any radar until it wakes. Same seam as {@code MixinDmzRadarTotems} and {@code MixinDmzRadarCrossShard}: the
 * RETURN of {@code DragonBallsHandler.buildRadarPacket}, where the whole packet exists. Doing it here, at the ONE
 * point every source has folded into, is what covers DMZ's own home dimension balls, grave totems, foreign
 * dimension balls and cross shard additions in a single place, whichever added a dormant ball.
 *
 * <h2>Why a high priority</h2>
 *
 * <p>{@code MixinDmzRadarTotems} and {@code MixinDmzRadarCrossShard} also inject at this RETURN and ADD positions
 * (a grave holding a dormant ball, a remote shard's copy). This strip must run AFTER them or a dormant ball they
 * add would survive. Mixin applies configs in ascending priority and a later applied injector's RETURN callback
 * runs later, so this carries a priority above their default 1000, pinning it last.
 *
 * <h2>The three collections</h2>
 *
 * <p>Reached through {@link AccessorRadarSyncS2C}: the per set map that custom radars read, plus DMZ's own
 * dedicated earth and namek lists, which are a COPY of the map's earth/namek entries (see AccessorRadarSyncS2C),
 * so a dormant earth or namek set has to be cleared from both or it would still show on DMZ's stock radar.
 *
 * <p>remap = false: target, {@code buildRadarPacket} and the packet type are DMZ's own names. require = 0 per the
 * standing rule, and the body catches Throwable, so any DMZ drift costs the strip (a dormant ball stays on radar)
 * and leaves the rest of the radar working rather than breaking the packet or mod load.
 */
@Mixin(targets = "com.dragonminez.server.events.DragonBallsHandler", remap = false, priority = 1500)
public abstract class MixinDmzRadarDormant
{
    @Inject(method = "buildRadarPacket", at = @At("RETURN"), require = 0, remap = false)
    private static void su$stripDormantBalls(MinecraftServer server, CallbackInfoReturnable<RadarSyncS2C> cir)
    {
        try
        {
            RadarSyncS2C packet = cir.getReturnValue();
            if (packet == null || server == null)
            {
                return;
            }
            List<String> dormant = BallDormancy.dormantSets(server);
            if (dormant.isEmpty())
            {
                return;
            }
            AccessorRadarSyncS2C access = (AccessorRadarSyncS2C) (Object) packet;
            Map<String, List<BlockPos>> bySet = access.su$positionsBySet();
            for (String setId : dormant)
            {
                if (bySet != null)
                {
                    List<BlockPos> positions = bySet.get(setId);
                    if (positions != null)
                    {
                        positions.clear();
                    }
                }
                // DMZ's stock earth and namek radars read the dedicated lists, not the map, so clear those too.
                if ("earth".equals(setId) && access.su$earthPositions() != null)
                {
                    access.su$earthPositions().clear();
                }
                if ("namek".equals(setId) && access.su$namekPositions() != null)
                {
                    access.su$namekPositions().clear();
                }
            }
        }
        catch (Throwable ignored)
        {
            // fail open: a dormant set staying visible on radar is a cosmetic miss, not a crash
        }
    }
}
