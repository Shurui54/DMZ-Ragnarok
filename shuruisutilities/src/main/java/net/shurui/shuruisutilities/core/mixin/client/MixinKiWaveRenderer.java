package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;

import net.shurui.shuruisutilities.combat.MeleeClashService;
import net.shurui.shuruisutilities.world.space.SpaceKeys;

import com.dragonminez.client.init.entities.renderer.ki.KiWaveRenderer;
import com.dragonminez.common.init.entities.ki.KiWaveEntity;

/**
 * Renders SU's own internal ki waves invisible: the planet clash's DEFENDING wave, so a planet buster reads as the
 * world itself resisting rather than a beam appearing from nowhere, and the melee clash PAIR, which exists only to
 * borrow DragonMineZ's beam-struggle machinery for a fist fight and was never meant to be seen.
 *
 * <p>DragonMineZ's {@link KiWaveRenderer#render} does not honour {@code Entity.isInvisible()} (it queues the beam mesh
 * unconditionally), so a server flag alone cannot hide it, and its override does not call {@code super.render}, so a
 * mixin on the vanilla {@code EntityRenderer.render} would never run for a wave. We must intercept the override itself.
 * {@code PlanetClash} stamps the answering wave with a synced technique-id sentinel
 * ({@link SpaceKeys#DEFENDER_WAVE_MARKER}); TECHNIQUE_ID is a synched field so it reaches the client, and this
 * injection cancels the render at the HEAD for any wave carrying that exact id. Only the DEFENDER wave is stamped, so
 * every ordinary player and NPC beam draws as before. Purely cosmetic: the clash is detected, paired and resolved
 * entirely server-side, untouched by whether the wave draws.</p>
 *
 * <p>The target is a class literal so the mixin processor can walk the hierarchy and map the inherited {@code render}
 * (Minecraft {@code EntityRenderer.render}, SRG {@code m_7392_}); the injection carries {@code remap = true} for that
 * vanilla name the way SU's other vanilla-override DMZ mixins do. It binds against the SYNTHETIC BRIDGE
 * {@code render(Entity, ...)} (the erased override the mapping resolves to), so the handler's first parameter is
 * {@code Entity} and the wave type is checked with an {@code instanceof}; the bridge runs before the real
 * {@code render(KiWaveEntity, ...)}, so cancelling it suppresses the draw. {@code require = 0} and the guarded body
 * mean that if DragonMineZ renames or reshapes the renderer the injection just does not bind, degrading to a visible
 * wave rather than crashing the client.</p>
 */
@Mixin(value = KiWaveRenderer.class, remap = false)
public abstract class MixinKiWaveRenderer
{
    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true, require = 0, remap = true)
    private void su$hidePlanetDefenderWave(Entity entity, float entityYaw, float partialTick,
                                           PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                           CallbackInfo ci)
    {
        try
        {
            if (!(entity instanceof KiWaveEntity wave))
                return;
            String technique = wave.getTechniqueId();
            // Two waves are ours and neither should ever be seen. The planet defender is the world answering a
            // buster; the melee clash pair is the VEHICLE for a fist fight, and beams appearing when two players
            // punch each other reads as a bug however well the struggle behind it works.
            if (SpaceKeys.DEFENDER_WAVE_MARKER.equals(technique)
                    || MeleeClashService.CLASH_WAVE_MARKER.equals(technique))
            {
                ci.cancel();
            }
        }
        catch (Throwable ignored)
        {
            // never let the render skip break the frame; on any error let DragonMineZ draw the wave normally.
        }
    }
}
