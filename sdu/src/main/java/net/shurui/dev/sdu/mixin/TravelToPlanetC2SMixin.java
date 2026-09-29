package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.init.entities.SpacePodEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.function.Supplier;

// Ground-snap for the saiyan space pod so you never arrive buried.
//
// DMZ runs arrival inside the enqueueWork lambda (synthetic lambda$handle$1(Supplier)), NOT handle itself.
// resolvePosition hands back the destination's fixed y verbatim (namekow 36, otherworld 210, time_chamber
// 130) or, for coordless destinations, your raw source-dim y, with no heightmap check. Pod has noGravity so
// a y at/below the surface buries the rider. namekow y=36 buries every time.
//
// We redirect the two vanilla placement calls in the lambda and clamp y up to the surface. Both recompute
// from (x,z) independently, no shared state, order doesn't matter.
//
// Remap: @Mixin target is a DMZ class (remap=false, official names in prod jar) but the redirected calls are
// vanilla, so each @Redirect sets remap=true to map to SRG (m_8999_ / m_6034_). require=0 so a missing
// target no-ops. Registered in the COMMON mixins array of sdu.mixins.json (runs server-side).
@Mixin(targets = "com.dragonminez.common.network.C2S.TravelToPlanetC2S", remap = false)
public abstract class TravelToPlanetC2SMixin {

    // DMZ's own final field carrying the destination id the client asked to travel to. Not a vanilla
    // member, so no remap (matches the class-level remap=false). We read it to decide whether the target
    // is one of our key-locked worlds.
    @Shadow(remap = false)
    @Final
    private String destinationId;

    // Whole-world destinations locked behind the Ragnarok Key, same policy as the dungeon dimension.
    // Matched on the destination id the client sends, which maps one to
    // one to our data/dragonminez/spacepod/destinations.json entries "namekow" and "kaiow". Keep this in step
    // with those ids; do not rename the ids to satisfy this gate.
    private static final Set<String> SDU_KEY_GATED_DESTINATIONS = Set.of("namekow", "kaiow");

    // Server-side travel gate: this is the arrival lambda DMZ enqueues for the TravelToPlanetC2S packet, the
    // single chokepoint where the actual cross-dimension teleport happens. DMZ evaluates the destination's
    // unlockRules here too, but those have no concept of Shurui's Key, so we add the key check ourselves and
    // cancel the whole arrival before any teleport runs.
    //
    // Since S21 the answer comes from PrivateWorldHooks.allowTravel (the key answers KeyGate.privateWorldsUnlocked()).
    // That asks privateWorldsUnlocked(), NOT unlocked(). unlocked() passes unconditionally in
    // singleplayer and on LAN, which meant these two worlds were open to anyone running the jar solo; they are
    // our own imported content, so they are gated everywhere and a solo player opts back in through
    // Config.allowPrivateWorldsWithoutKey.
    //
    // require=0 and remap=false mirror the redirects below: a miss (DMZ update / mapping drift) must no-op, not
    // crash. That means a broken binding silently drops the gate, so this MUST be launch-tested, not trusted to
    // a green build.
    @Inject(
        method = "lambda$handle$1(Ljava/util/function/Supplier;)V",
        at = @At("HEAD"),
        require = 0,
        cancellable = true,
        remap = false
    )
    private void sdu$gateKeyedDestinations(Supplier<NetworkEvent.Context> ctxSupplier, CallbackInfo ci) {
        if (destinationId == null || !SDU_KEY_GATED_DESTINATIONS.contains(destinationId))
            return;
        ServerPlayer player = ctxSupplier.get().getSender();
        // S21: the allow decision lives in the Ragnarok Key (PrivateWorldHooks; keyless: refused, as before).
        if (net.shurui.dev.sdu.api.key.PrivateWorldHooks.get().allowTravel(player, destinationId))
            return;
        if (player != null)
            player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.spacepod.key_locked"));
        ci.cancel();
    }

    // clamp player teleport y up to the surface in the arrival lambda
    @Redirect(
        method = "lambda$handle$1(Ljava/util/function/Supplier;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V",
            remap = true
        ),
        // require=0: a redirect into a DMZ lambda must never be fatal. if the target moves
        // (DMZ update / mapping drift) the snap no-ops instead of crashing.
        require = 0,
        remap = false
    )
    private void sdu$snapPlayerLanding(ServerPlayer player, ServerLevel level,
                                       double x, double y, double z, float yaw, float pitch) {
        // Earth (overworld) has no coords in its destination JSON, so DMZ falls back to stale
        // departure-dim coords. Override x/z to the spawn column on Earth returns only; y clamp still
        // applies there. Coord-bearing destinations (namek, otherworld, time chamber) keep ground-snap only.
        if (level.dimension() == Level.OVERWORLD) {
            BlockPos spawn = level.getSharedSpawnPos();
            x = spawn.getX() + 0.5;
            z = spawn.getZ() + 0.5;
        } else {
            // Centre the fixed destinations on a single column. The JSON coords are whole numbers
            // (kaiow 0.0/-64.0), which sit exactly on a block CORNER, so an entity wider than one block
            // straddles four columns. kaiow's live region has the sampled column (0,-64) topping out at
            // y=104 while its neighbours (0,-63) and (1,-64) top out at y=105, so an off-centre pod was
            // half-buried even though the one column we probed looked clear. Landing at floor+0.5 seats
            // the arrival in the middle of one column instead. Earth already gets its own +0.5 above.
            x = Math.floor(x) + 0.5;
            z = Math.floor(z) + 0.5;
        }
        player.teleportTo(level, x, sdu$snap(level, x, z, y), z, yaw, pitch);
    }

    // clamp the pod's setPos y up to the surface so it lands on terrain not inside it (pod has noGravity,
    // won't fall). DMZ uses Entity.setPos(DDD) (SRG m_6034_), not moveTo (m_6027_); same (DDD)V descriptor
    // so the SRG number disambiguates.
    @Redirect(
        method = "lambda$handle$1(Ljava/util/function/Supplier;)V",
        at = @At(
            value = "INVOKE",
            // owner must be SpacePodEntity: bytecode is invokevirtual SpacePodEntity.m_6034_(DDD)V. naming
            // Entity (declaring superclass) is an owner mismatch -> 0 matches. remap=true keeps
            // setPos->m_6034_; DMZ owner stays literal.
            target = "Lcom/dragonminez/common/init/entities/SpacePodEntity;setPos(DDD)V",
            remap = true
        ),
        // require=0: see snapPlayerLanding
        require = 0,
        remap = false
    )
    private void sdu$snapPodLanding(SpacePodEntity pod, double x, double y, double z) {
        ServerLevel level = (ServerLevel) pod.level();
        // mirror the player override: on Earth returns DMZ hands back stale departure coords (empty
        // destination JSON), so snap the pod to the spawn column too to keep it with its rider.
        if (level.dimension() == Level.OVERWORLD) {
            BlockPos spawn = level.getSharedSpawnPos();
            x = spawn.getX() + 0.5;
            z = spawn.getZ() + 0.5;
        } else {
            // Same block-centring as the player injector, and it MUST match: if the pod and its rider
            // round their whole-number coords differently they end up in different columns. floor+0.5
            // lands both in the middle of one column so the kaiow corner case (0.0/-64.0 straddling four
            // columns, neighbours a block taller than the sampled one) can no longer bury the pod.
            x = Math.floor(x) + 0.5;
            z = Math.floor(z) + 0.5;
        }
        pod.setPos(x, sdu$snap(level, x, z, y), z);
    }

    // max(destinationY, surface): never below the surface but keeps an intentionally high landing
    // (otherworld y=210). MOTION_BLOCKING_NO_LEAVES so we land on solid ground not tree canopy. fails open.
    //
    // We sample a FOOTPRINT, not a single column, and take the MAXIMUM surface over it. A point sample was
    // not enough: kaiow's fixed destination (0.0/-64.0) put the arrival on a block corner, and the column we
    // happened to probe (0,-64) topped out at y=104 while its neighbours (0,-63) and (1,-64) topped out at
    // y=105, so a pod (wider than one block) sat half-embedded in terrain the single sample never saw. Even
    // with the caller now centring on floor+0.5, an entity up to 3 blocks wide still overhangs the eight
    // columns around the centre, so we scan a 3x3 centred on the landing block and clamp above the tallest of
    // them. Each sampled chunk is forced to FULL first, exactly as the single-column read used to, so the
    // heightmap is populated before we read it. Anything up to 3 blocks wide is fully covered.
    private static double sdu$snap(ServerLevel level, double x, double z, double y) {
        try {
            int cx = (int) Math.floor(x);
            int cz = (int) Math.floor(z);
            int surface = Integer.MIN_VALUE;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int bx = cx + dx;
                    int bz = cz + dz;
                    // force chunk to FULL so the heightmap is populated at every sampled column
                    level.getChunk(bx >> 4, bz >> 4, ChunkStatus.FULL, true);
                    int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
                    if (h > surface)
                        surface = h;
                }
            }
            return Math.max(y, surface);
        } catch (Throwable t) {
            return y; // never break travel
        }
    }
}
