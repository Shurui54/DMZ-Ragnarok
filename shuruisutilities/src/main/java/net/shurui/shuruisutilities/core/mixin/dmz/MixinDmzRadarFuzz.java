package net.shurui.shuruisutilities.core.mixin.dmz;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;

import net.shurui.shuruisutilities.compat.dmz.RadarFuzz;

/**
 * Replaces the exact ball positions in the SERVER's radar list with an approximate spot for the normal sets, so a
 * modified client can never read the true coordinate.
 *
 * <p>Grave totems holding balls are NOT added here, though an earlier attempt did exactly that. This method is
 * reached only through DMZ's per-dimension loop in {@code buildRadarPacket}, which visits just the dimensions in the
 * SET's own definition, so a super totem in the overworld is never even asked about (super scatters on planet
 * surfaces only). {@code MixinDmzRadarTotems} adds totems once, at the point the whole packet is assembled, where
 * every level is reachable. It fuzzes them itself, so totems get the same treatment the balls below do.
 *
 * <p>WHY here. {@code DragonBallSavedData.getAllKnownPositionsForRadar(setId)} is the single seam DMZ calls, per
 * set, when it builds the radar sync packet in {@code DragonBallsHandler.buildRadarPacket}. It already returns a
 * FRESH list (a copy gathered from the active and pending maps) used only for the radar, and it receives the set
 * id, so it is the least invasive place to intercept: rewriting its return value fuzzes both active AND pending
 * balls, touches nothing but the outgoing radar data, and leaves the stored active/pending coordinates that
 * scatter, pickup and the summon scan depend on completely untouched. The set-id gate lives in {@link RadarFuzz}
 * so Super and Black Star stay exact.
 *
 * <p>remap=false: the {@code @Mixin} target and the {@code getAllKnownPositionsForRadar} selector are DMZ's own
 * names (official in the prod jar), not vanilla overrides, so nothing here needs the refmap. require=0 per the
 * standing rule: if DMZ renames or reshapes this method the injector degrades to a no-op (radar keeps DMZ's exact
 * positions) instead of crashing mod load. The body is wrapped in try/catch(Throwable) so any drift inside the
 * fuzzing helper likewise fails OPEN, returning DMZ's exact positions rather than breaking the radar.
 */
@Mixin(targets = "com.dragonminez.server.world.data.DragonBallSavedData", remap = false)
public abstract class MixinDmzRadarFuzz
{
    @Inject(method = "getAllKnownPositionsForRadar", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void su$fuzzRadarPositions(String setId, CallbackInfoReturnable<List<BlockPos>> cir)
    {
        try
        {
            List<BlockPos> exact = cir.getReturnValue();
            List<BlockPos> fuzzed = RadarFuzz.fuzzForRadar(setId, exact);
            if (fuzzed != exact)
            {
                cir.setReturnValue(fuzzed);
            }
        }
        catch (Throwable ignored)
        {
            // Fail open: leave DMZ's exact positions in place so a helper/API drift degrades to today's radar
            // rather than breaking it.
        }
    }
}
