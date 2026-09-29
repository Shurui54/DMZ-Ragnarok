package net.shurui.shuruisutilities.space.mixin.dmz;

import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.space.PlanetCourse;

/**
 * Turns DMZ's space-pod "travel to planet" into a compass COURSE instead of an instant teleport, but only for
 * destinations whose target dimension is one of our space-dimension planet bodies. A non-body destination (otherworld,
 * the time chamber, any non-body) keeps DMZ's original instant-travel behaviour ONLY while the player is riding a
 * space pod; if it is picked on foot (through our planet-select opener) it is refused, so DMZ never teleports the
 * player or spawns a fresh pod from an on-foot pick. All of that lives in {@link PlanetCourse#handleTravel}.
 *
 * <p>DMZ enqueues the actual travel as a lambda ({@code lambda$handle$1(Supplier)}); we inject at its HEAD and
 * cancel EARLY, before DMZ touches the pod at all. DMZ's real lambda would {@code stopRiding}, discard the
 * persistence-required pod and re-seat the player; cancelling at HEAD means none of that runs, so no orphan pod
 * is ever left behind and sdu's landing-snap redirects (which live INSIDE this same lambda) simply never fire for
 * a body destination, which is correct. For every destination we do NOT claim, the lambda proceeds exactly as
 * before and sdu's snaps still apply.
 *
 * <p>We do not capture DMZ's local variables (fragile against recompiles): we re-read the private
 * {@code destinationId} field via {@link Shadow} and hand it to {@link PlanetCourse}, which does its own
 * destination lookup, body test and (if it is a body) sets the course. PlanetCourse returns whether it claimed
 * the trip; only then do we cancel.
 *
 * <p>remap=false: the {@code @Mixin} target and the shadowed field are DMZ (official names in the prod jar). The
 * injected lambda name is a synthetic DMZ member, so remap=false there too. require=0 per the standing rule for
 * mixins into DMZ classes: if DMZ renames or restructures the handler, this degrades to a no-op (instant travel
 * keeps working) rather than crashing mod load.
 *
 * <h2>Order against sdu's TravelToPlanetC2SMixin (priority 1100)</h2>
 * sdu's {@code net.shurui.dev.sdu.mixin.TravelToPlanetC2SMixin} injects at the HEAD of this SAME lambda (its
 * {@code sdu$gateKeyedDestinations} key gate). sdu MUST run first: for an on-foot pick of a key-gated whole-world
 * destination (namekow / kaiow) with the key locked, BOTH handlers would cancel, and whichever runs first wins the
 * cancel and its message (a cancel at HEAD returns before the next HEAD callback runs). Today sdu wins and the
 * player sees the key-locked message, not this course opener's "needs pod" message. That is the behaviour to keep.
 *
 * <p>Today both mixins were the default priority (1000) and lived in configs whose load order (sdu.mixins.json
 * before mixins.shuruisutilities.json) put sdu first, because at equal priority Mixin applies the earlier-registered
 * config first and, at HEAD, the earlier-applied callback executes first. Moving this mixin into its own config
 * (dmz_ragnarok_space.mixins.json) could disturb that load-order tie-break, so the order is pinned explicitly
 * instead: {@code priority = 1100}. A higher priority is applied LATER than sdu's 1000, so at HEAD sdu's callback
 * still executes first, exactly as before, regardless of config registration order. Nothing else on this target is
 * an overwrite or conflict, so the raised priority has no other effect.
 */
@Mixin(targets = "com.dragonminez.common.network.C2S.TravelToPlanetC2S", remap = false, priority = 1100)
public abstract class MixinDmzTravelToPlanet
{
    // DMZ's private destination id (the selected menu entry's id). Re-read here rather than captured as a lambda
    // local so we do not depend on DMZ's local-variable layout.
    @Shadow
    private String destinationId;

    // HEAD of the enqueued travel lambda, before DMZ stops riding, discards the pod or re-seats the player.
    @Inject(
        method = "lambda$handle$1(Ljava/util/function/Supplier;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void su$courseInsteadOfTeleport(Supplier<NetworkEvent.Context> supplier, CallbackInfo ci)
    {
        // resolve the sender the same way DMZ does at the top of its lambda; a null/absent sender means there is
        // nothing for us to do and we let DMZ's own null guard handle it.
        NetworkEvent.Context context = supplier.get();
        ServerPlayer player = context.getSender();
        if (player == null)
        {
            return;
        }
        // PlanetCourse decides: a body destination becomes a compass course; a non-body destination picked while
        // NOT in a pod (the on-foot planet-select opener) is refused so DMZ never teleports the player or spawns an
        // extra pod. Either of those cancels DMZ's teleport. A non-body destination while IN a pod returns false and
        // DMZ travels exactly as it always has. All body/planet/pod logic lives there, off the mixin.
        if (PlanetCourse.handleTravel(player, this.destinationId))
        {
            ci.cancel();
        }
    }
}
