package net.shurui.dev.sdu.mixin;

import com.dragonminez.client.model.DMZPlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.compat.dmz.DmzRaceGeoGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Keeps a player's race geo from killing the render thread.
//
// DMZ resolves an unrecognised customModel to dragonminez:geo/entity/races/<model><variant>.geo.json and accepts it
// if the FILE READS (DMZPlayerModel.fileExists, memoised and never cleared). GeckoLib accepts it only if the file
// was LISTED during the last resource reload, and throws GeckoLibException out of getBakedModel when it was not.
// The rgnpc race aliases sit exactly in that gap: the streamed pack answers <entry>_male / _female / _slim out of
// the bare entry, and only the bare entry is ever listed, so a GENDERED race reads fine and was never baked.
// See DmzRaceGeoGuard, which is where the decision lives; this is only the attachment point.
//
// RETURN rather than HEAD: every one of DMZ's many return paths is covered, including the stock races, and the
// normal case costs one map lookup. Parameters are the target's exactly (the erased AbstractClientPlayer overload,
// not the GeoAnimatable bridge), plus the CIR.
//
// require = 0 + remap = false (DMZ's own class): if DMZ reshapes getModelResource this simply stops guarding,
// which is where we were before.
@Mixin(value = DMZPlayerModel.class, remap = false)
public abstract class DmzPlayerModelGeoGuardMixin {

    @Inject(method = "getModelResource(Lnet/minecraft/client/player/AbstractClientPlayer;)Lnet/minecraft/resources/ResourceLocation;",
            at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void sdu$guardUnbakedGeo(AbstractClientPlayer animatable, CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation wanted = cir.getReturnValue();
        ResourceLocation safe = DmzRaceGeoGuard.bakedOrDefault(wanted);
        if (safe != wanted) {
            cir.setReturnValue(safe);
        }
    }
}
