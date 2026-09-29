package net.shurui.shuruisutilities.core.mixin.client;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dragonminez.common.init.entities.SpacePodEntity;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;
import com.dragonminez.common.stats.character.Status;
import com.dragonminez.client.render.util.PlayerEffectQueue;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;

import net.shurui.shuruisutilities.client.autopilot.SpaceAutopilotClient;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import software.bernie.geckolib.cache.object.BakedGeoModel;

/**
 * Renders the ki aura on the LOCAL player while they are piloting a DMZ space pod under space autopilot, even when their
 * aura flag is off, as a purely visual thrust effect that costs no ki and touches no server state. The autopilot gate
 * (via {@code SpaceAutopilotClient}, the client mirror the server syncs to the controlling pilot) is the show/hide
 * decision for the pod exhaust; {@code MixinDmzAuraRenderer} gates the same condition before reshaping the queued aura.
 *
 * <p>DMZ's {@code DMZAuraLayer.render} reads the client-synced aura flag and, when it is set, queues the aura and spark
 * effects into {@link PlayerEffectQueue}; the downstream {@code AuraRenderer.processThirdPersonAuras} does not re-check
 * the flag, it just draws whatever was queued. So at the HEAD of {@code render} we can queue the aura ourselves for a
 * seated pilot whose flag is currently false, and let DMZ's own body run untouched (it is a no-op when the flag is off).
 * We queue the AURA only, never the spark: a spark with no active form reads as wrong. We deliberately do NOT drive
 * DMZ's server-side {@code setAuraActive}, which its {@code TickHandler} recomputes every tick and which would switch on
 * the movement-speed modifier, aura light, glow, particles, drain and loop sound. This is the cosmetic-only path.</p>
 *
 * <p>The same path supplies the DASH aura. {@code MixinDmzAuraDashFlip} only reorients an aura that is already being
 * drawn, so a player who dashed without having powered up had nothing to reorient and the dash rendered bare. Queueing
 * one here for any dashing player, local or remote, is the half that was missing.</p>
 *
 * <p>Android guard: DMZ's own {@code render} early-returns for an upgraded android that is not in a charging form and
 * has no lightning, so queueing an aura for one would produce a mismatched effect. We mirror that by skipping upgraded
 * androids entirely, queueing only the ordinary aura DMZ itself would draw.</p>
 *
 * <p>{@code require = 0}: DMZ is a hard dependency so the class is present, but if a future DMZ build reshapes this
 * method the injector degrades to a no-op instead of crashing the client. Client-only (listed in the "client" block of
 * {@code mixins.shuruisutilities.json}); it never loads server-side.</p>
 */
@Mixin(targets = "com.dragonminez.client.render.layer.DMZAuraLayer", remap = false)
public abstract class MixinDmzAuraLayer
{
    private static final AtomicBoolean SU_AURA_LAYER_BIND_LOGGED = new AtomicBoolean(false);

    // The erased descriptor of the generic render(T, ...) method: T is bounded by AbstractClientPlayer, so it erases to
    // that type. require = 0 keeps the whole space-pod family uniform: this is purely cosmetic, so a missed target must
    // degrade to no aura rather than fail the SU mixin config and crash clients. With require = 0 a miss is silent, so
    // the one-shot bind log below is the only proof it wove; its absence is the proof it did not.
    @Inject(method = "render", at = @At("HEAD"), remap = false, require = 0)
    private void su$queuePilotAura(PoseStack poseStack, AbstractClientPlayer player, BakedGeoModel bakedModel,
            RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
            int packedLight, int packedOverlay, CallbackInfo ci)
    {
        // log-once on first weave/invocation. require = 0 fails SILENTLY, so this line appearing is the proof the
        // injector bound. Guarded + latched so it prints exactly once and never disturbs the render path.
        if (SU_AURA_LAYER_BIND_LOGGED.compareAndSet(false, true))
        {
            try
            {
                LoggingHandler.sulog.info(
                        "[SpacePod] MixinDmzAuraLayer bound (DMZAuraLayer.render HEAD, visual-only pilot aura)");
            }
            catch (Throwable ignored)
            {
            }
        }
        try
        {
            if (player == null)
            {
                return;
            }
            // Two reasons to supply an aura DMZ is not drawing: a dash, and an autopilot pod exhaust.
            //
            // The dash is the reason MixinDmzAuraDashFlip existed but did nothing on its own. That mixin REORIENTS an
            // aura, and a player who dashes without having powered up has no aura to reorient, so the dash was flipping
            // something that was never drawn. Queueing one here is the missing half: for ANY dashing player, local or
            // remote, since a dash coming at you is exactly the one you most need to see.
            boolean dashing = net.shurui.shuruisutilities.client.combat.DashAuraState.isDashing(player.getId());
            if (!dashing && !su$isRidingSpacePod(player))
            {
                return;
            }
            // Pod exhaust is an autopilot-only cue. Queue the pilot aura only for THIS client's own player while its space
            // autopilot is armed (SpaceAutopilotClient is the client mirror the server syncs, via PacketSpaceAutopilotSync,
            // ONLY to the controlling pilot). A parked or hand-flown pod, a pod on a surface, or a remote pilot whose
            // autopilot this client cannot see, gets no aura. This is the show/hide decision; MixinDmzAuraRenderer gates
            // the SAME condition before reshaping, so a genuinely powered-up hand-flying pilot keeps a normal aura.
            if (!dashing && (player != Minecraft.getInstance().player || !SpaceAutopilotClient.isActive()))
            {
                return;
            }
            StatsData stats = StatsProvider.get(StatsCapability.INSTANCE, player).resolve().orElse(null);
            if (stats == null)
            {
                return;
            }
            Status status = stats.getStatus();
            if (status == null)
            {
                return;
            }
            // Only supply the aura when DMZ itself would not: the flag is off. An already-active aura is DMZ's job.
            if (status.isAuraActive() || status.isPermanentAura())
            {
                return;
            }
            // Upgraded androids: DMZ's render early-returns for them (outside a charging form), so a queued aura would
            // be mismatched. Skip them so we only ever add the plain aura DMZ would otherwise draw.
            if (status.isAndroidUpgraded())
            {
                return;
            }
            PlayerEffectQueue.addAura(player, bakedModel, poseStack, partialTick, packedLight);
        }
        catch (Throwable ignored)
        {
            // cosmetic only: any failure resolving stats or queueing the aura leaves DMZ's own render untouched.
        }
    }

    // true while the rendered player is riding a DMZ space pod, directly or nested. Mirrors the server-side chain walk
    // in space/PlanetCourse.isRidingSpacePod so the visual matches where autopilot considers the player in flight.
    private static boolean su$isRidingSpacePod(AbstractClientPlayer player)
    {
        for (Entity vehicle = player.getVehicle(); vehicle != null; vehicle = vehicle.getVehicle())
        {
            if (vehicle instanceof SpacePodEntity)
            {
                return true;
            }
        }
        return false;
    }
}
