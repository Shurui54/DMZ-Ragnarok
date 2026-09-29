package net.shurui.dev.sdu.mixin;

import com.dragonminez.client.render.layer.DMZSkinLayer;
import com.dragonminez.client.util.SkinGathererProvider;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.DmzNpc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

import java.util.function.BiConsumer;

// Custom frost-demon 4th body layer: an extra "gem" layer tinted by the player's hair/gem colour.
//
// WHY THIS EXISTS: a custom-model frost demon (e.g. the Golden/Black Frieza forms) renders through
// gatherBodyLayers' isLayered/default branch, which emits exactly three tinted body layers and has
// NO hair layer, so the crown/chest/knee gems that normally live on the hair layer have nowhere to
// go. We add a 4th tinted layer, "<model>_<bodyType>_layer4.png", tinted by the character's hair
// colour so the gems follow the colour the player chose.
//
// WHY IT WAS BROKEN (the reported bug): DMZ keys those layer paths on the player's SELECTED body
// type: "<race>/<model><gen>_<bodyType>_layerN.png". Only layer1 is given a "_0_" fallback; layer2
// and layer3 use the single-arg getSafeTexture, which returns DMZ's BLANK null.png when the file is
// missing. The Frieza pack ships only "_0_" art, so on body types 1 and 2 layer2 and layer3 resolve
// to null.png. The previous version of this mixin derived the gem path from the RESOLVED layer3 and
// only emitted when that path ended in "layer3.png"; on any non-zero body type that path was
// null.png, so the gems silently disappeared. Body type 0 worked (its "_0_layer3.png" exists), every
// other body type did not. That is exactly "arcosian body types do not all show hair gems".
//
// THE FIX: anchor on layer1 instead of layer3. Layer1 ALWAYS resolves to a real, correctly-keyed
// file because DMZ gives it the "_0_" fallback, so its path always ends in "layer1.png" and already
// carries whichever body-type segment DMZ actually used (the per-type file if present, otherwise
// "_0_"). Deriving "<...>_layer4.png" from it therefore lands on the right texture for every body
// type with zero coupling to DMZ's form/config key resolution. Emitting the gem layer immediately
// after layer1 is safe here: the frost-demon layer2/layer3 art is fully transparent (verified: 0
// opaque texels; they exist only as placeholders), and layer1 is transparent at the gem texels, so
// nothing drawn before or after the gem layer can cover it.
//
// WHY @Redirect and not @Inject+CAPTURE_FAILSOFT: a whole-frame failsoft capture silently DROPPED in
// an earlier attempt (gems invisible, no error). A redirect binds one unambiguous invokeinterface so
// it applies or fails loudly at load. require=0 keeps it optional if DMZ moves the call. A missing
// layer4 resolves to DMZ's BLANK via getSafeTexture, so any layered race that ships no gem layer is
// unaffected (we skip the blank rather than painting it over the body).
@Mixin(value = SkinGathererProvider.class, remap = false)
public abstract class SkinGathererLayer4Mixin
{
    // Latched so a future DMZ API change degrades to a SINGLE log line instead of flooding the log
    // once per rendered frame.
    private static boolean sdu$loggedLayer4Failure = false;

    @Redirect(
            method = "gatherBodyLayers(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/dragonminez/common/stats/StatsData;FLjava/util/function/BiConsumer;)V",
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Lcom/dragonminez/common/config/RaceCharacterConfig;getIsLayered()Ljava/lang/Boolean;",
                    remap = false)),
            at = @At(value = "INVOKE",
                    target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V",
                    ordinal = 0),
            remap = false, require = 0)
    private void sdu$layer1PlusHair(BiConsumer<ResourceLocation, float[]> consumer,
                                    Object layer1Tex, Object b1,
                                    AbstractClientPlayer player, StatsData stats, float partialTick,
                                    BiConsumer<ResourceLocation, float[]> methodConsumer)
    {
        // DMZ's original layer1 (body) emit, unchanged.
        consumer.accept((ResourceLocation) layer1Tex, (float[]) b1);

        // Additive gem layer, fully guarded so it can never break the base body render.
        try
        {
            ResourceLocation l1 = (ResourceLocation) layer1Tex;
            if (l1 != null && l1.getPath().endsWith("layer1.png"))
            {
                ResourceLocation l4Raw = ResourceLocation.fromNamespaceAndPath(
                        l1.getNamespace(), l1.getPath().replace("layer1.png", "layer4.png"));
                ResourceLocation l4 = DMZSkinLayer.getSafeTexture(l4Raw);
                // Only emit when a REAL layer4 exists. getSafeTexture hands back BLANK (null.png) for
                // races that ship no gem layer; a blank path no longer ends in "layer4.png", so those
                // races are skipped and never get a stray transparent pass painted over the body.
                if (l4 != null && l4.getPath().endsWith("layer4.png"))
                {
                    // Tint by the BASE character hair colour (not the form-overridden local), so the
                    // gems follow the colour the player chose. This matches the behaviour body type 0
                    // already had before the fix.
                    float[] hair = (stats != null && stats.getCharacter() != null)
                            ? stats.getCharacter().getRgbHairColor() : null;
                    if (hair != null)
                    {
                        methodConsumer.accept(l4, hair);
                    }
                }
            }
        }
        catch (Throwable t)
        {
            if (!sdu$loggedLayer4Failure)
            {
                sdu$loggedLayer4Failure = true;
                DmzNpc.LOGGER.debug("[{}] layer4 gem tint skipped: {}", DmzNpc.MODID, t.toString());
            }
        }
    }
}
