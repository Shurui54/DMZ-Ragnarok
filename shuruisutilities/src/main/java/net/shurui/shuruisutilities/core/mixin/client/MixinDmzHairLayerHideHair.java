package net.shurui.shuruisutilities.core.mixin.client;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import net.shurui.shuruisutilities.client.cosmetics.CosmeticRenderOptions;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticDef;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticSlot;
import net.shurui.shuruisutilities.cosmetics.wardrobe.client.CosmeticClientStore;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import software.bernie.geckolib.cache.object.GeoBone;

/**
 * Hides DragonMineZ's hair on a player who is wearing a HEAD cosmetic that fully encloses the head (a pumpkin
 * head, a full mask), so the hair does not poke through the model.
 *
 * <h2>Where it hooks</h2>
 * DMZ draws hair from {@code DMZHairLayer.renderForBone}, a GeckoLib per-bone layer that returns immediately for
 * every bone whose name is not {@code head} and otherwise calls {@code renderHair}. Cancelling at the HEAD of
 * that method is therefore the least invasive suppression there is: it skips the hair for exactly this player and
 * this frame and touches nothing else, no state, no other layer, no other player.
 *
 * <h2>The three gates, all client side</h2>
 * <ul>
 *   <li>The player must have a HEAD cosmetic equipped whose definition is marked {@link CosmeticDef#hidesHair}.
 *       Only the enclosing heads are (stamped by a one-time migration in {@code CosmeticCatalog}); a hat that
 *       sits on top leaves it false, so the hair still shows under the brim.</li>
 *   <li>This client must have the preference on ({@link CosmeticRenderOptions#hideHairUnderHelmets}, default on,
 *       flipped with {@code /cosmetichair}). A player who would rather keep their hair keeps it.</li>
 *   <li>The head cosmetic must actually be DRAWN, which is the same condition {@code WardrobeCosmeticLayer}
 *       uses: always for the local player, and for others only when {@link CosmeticRenderOptions#showOthers} is
 *       on. Hiding a remote player's hair while not drawing their enclosing hat would leave them bald.</li>
 * </ul>
 *
 * <p>{@code require = 0}: DMZ is a hard dependency so the class is present, but if a future DMZ build reshapes
 * this method the injector degrades to "the hair still shows" instead of crashing the client, which is the safe
 * direction for a cosmetic. With {@code require = 0} a miss is silent, so the one-shot bind log below is the only
 * proof it wove. Client only (listed in the {@code client} block of {@code mixins.shuruisutilities.json}); it
 * never loads server side.
 */
@Mixin(targets = "com.dragonminez.client.render.layer.DMZHairLayer", remap = false)
public abstract class MixinDmzHairLayerHideHair
{
    private static final AtomicBoolean SU_HAIR_HIDE_BIND_LOGGED = new AtomicBoolean(false);

    // The erased descriptor of the generic renderForBone(PoseStack, T, ...) method: T is bounded by
    // AbstractClientPlayer, so it erases to that type. Name-only match plus AbstractClientPlayer-typed capture
    // binds this to the real method and never to the synthetic GeoAnimatable bridge. require = 0 keeps a missed
    // target from failing the SU mixin config: a cosmetic suppression must degrade to visible hair, never crash.
    @Inject(method = "renderForBone", at = @At("HEAD"), remap = false, require = 0, cancellable = true)
    private void su$hideHairUnderEnclosingHead(PoseStack poseStack, AbstractClientPlayer player, GeoBone bone,
            RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
            int packedLight, int packedOverlay, CallbackInfo ci)
    {
        if (SU_HAIR_HIDE_BIND_LOGGED.compareAndSet(false, true))
        {
            try
            {
                LoggingHandler.sulog.info(
                        "[Cosmetics] MixinDmzHairLayerHideHair bound (DMZHairLayer.renderForBone HEAD, hair hide)");
            }
            catch (Throwable ignored)
            {
            }
        }
        try
        {
            if (player == null)
                return;
            CosmeticDef def = CosmeticClientStore.worn(player.getUUID(), CosmeticSlot.HEAD);
            if (def == null || !def.enabled)
                return;
            // Two ways a head hides hair. The definition's own flag is for a head that ENCLOSES the skull (a pumpkin
            // head): it hides regardless of the switch, because hair through a solid mask is a visual bug. The
            // player switch covers every other hat, which is what the wardrobe button means to a player: "I wear a
            // hat, put my hair away". Without the switch an ordinary hat leaves the hair showing under the brim.
            if (!def.hidesHair && !CosmeticRenderOptions.hideHairUnderHelmets())
                return;
            // Only suppress the hair when the enclosing head is actually being drawn: always for the local player,
            // for others only when their cosmetics are shown. Otherwise a remote player would go bald with no hat.
            Minecraft mc = Minecraft.getInstance();
            boolean self = mc != null && player == mc.player;
            if (!self && !CosmeticRenderOptions.showOthers())
                return;
            ci.cancel();
        }
        catch (Throwable ignored)
        {
            // Any failure resolving the wardrobe leaves DMZ's own hair render untouched.
        }
    }
}
