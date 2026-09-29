package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.network.C2S.IncreaseStatC2S;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.buff.TokenStatDiscount;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

// Publishes the buying player into TokenStatDiscount.CURRENT_BUYER for the duration of DMZ's stat-purchase
// work, so StatsDataCostMixin can discount getSingleStatCost for that player only.
//
// We do NOT target handle: it only schedules the work via enqueueWork and returns, so on a dedicated server
// the runnable runs LATER on the server thread and a handle-HEAD set would clear before any cost is computed.
// Instead we target the enqueued runnable, DMZ's lambda$handle$1(Supplier, IncreaseStatC2S). It runs on the
// server thread and, inline, resolves the sender and does the affordability + charge calc (both through
// getSingleStatCost). HEAD-set / RETURN-clear is exact.
//
// Buyer resolved as DMZ does: ctx.get().getSender(). RETURN always clears (DMZ's null-sender early return is
// still a RETURN). A per-tick sweep in TokenBuffCarryOver bounds any leak if the RETURN clear fails to bind.
//
// require = 0 (safe-off): a DMZ reshape of lambda$handle$1 makes these no-op; with no buyer published,
// StatsDataCostMixin sees null and DMZ's full price stands. remap=false. Registered in the COMMON mixins array.
@Mixin(targets = "com.dragonminez.common.network.C2S.IncreaseStatC2S", remap = false)
public abstract class IncreaseStatC2SMixin {

    @Inject(
        method = "lambda$handle$1(Ljava/util/function/Supplier;Lcom/dragonminez/common/network/C2S/IncreaseStatC2S;)V",
        at = @At("HEAD"),
        require = 0,
        remap = false
    )
    private static void sdu$setBuyer(Supplier<NetworkEvent.Context> ctx, IncreaseStatC2S msg, CallbackInfo ci) {
        try {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                TokenStatDiscount.CURRENT_BUYER.set(player);
            }
        } catch (Throwable t) {
            // never break the buy: no buyer set means full DMZ price
            TokenStatDiscount.CURRENT_BUYER.remove();
        }
    }

    @Inject(
        method = "lambda$handle$1(Ljava/util/function/Supplier;Lcom/dragonminez/common/network/C2S/IncreaseStatC2S;)V",
        at = @At("RETURN"),
        require = 0,
        remap = false
    )
    private static void sdu$clearBuyer(Supplier<NetworkEvent.Context> ctx, IncreaseStatC2S msg, CallbackInfo ci) {
        // clear on every return so it never leaks past this purchase
        TokenStatDiscount.CURRENT_BUYER.remove();
        // Then tell the buyer what the server actually counted. The stat screen prices and enables its buttons from
        // the client's synced copy of the tokens, and any drift between that copy and the server's (a death, an
        // expiry, a missed sync) shows as a price the server then refuses. Resending after every attempt means one
        // refused click is the most that drift can ever cost.
        try {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                // The Ragnarok Key's resync (feature tokenbuffs); keyless there is nothing to tell and it is a no-op.
                net.shurui.dev.sdu.api.key.TokenBuffHooks.get().resync(player);
            }
        } catch (Throwable t) {
            // a failed resync leaves the client's copy as it was
        }
    }
}
