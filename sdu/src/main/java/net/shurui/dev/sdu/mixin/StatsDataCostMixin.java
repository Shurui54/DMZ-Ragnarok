package net.shurui.dev.sdu.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.api.key.TokenBuffHooks;
import net.shurui.dev.sdu.buff.TokenStatDiscount;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Applies a player's active STAT-discount tokens to the per-point TP cost of buying stats.
// getSingleStatCost(int) is DMZ's single source of truth for stat pricing: both the affordability calc
// (calculateStatIncrease) and the charge (calculateRecursiveCost) call it, so discounting its return keeps
// "how many you can buy" and "what you pay" consistent.
//
// We only discount while a purchase for a known buyer is in flight: IncreaseStatC2SMixin sets CURRENT_BUYER
// for that server-thread work. Any other caller (progression/BP helpers) sees null and DMZ's original value.
//
// Discount = the strongest active STAT token (0.30 = 30% off), answered by TokenBuffHooks (the Ragnarok Key;
// keyless it is 0), clamped [0, 0.90] so a purchase can't go free/negative. Floored at 1; DMZ floors at minTPCost
// (>= 1) so this never goes below DMZ's single-point charge.
//
// require = 0 (safe-off): if a DMZ reshape moves getSingleStatCost this injector silently no-ops instead of
// hard-failing class load. This mixin only ever lowers DMZ's returned cost, so degrading it just leaves the
// full DMZ price in place.
//
// remap=false per sdu's DMZ production-jar convention. Registered in the COMMON mixins array.
@Mixin(targets = "com.dragonminez.common.stats.StatsData", remap = false)
public abstract class StatsDataCostMixin {

    @Inject(
        method = "getSingleStatCost(I)I",
        at = @At("RETURN"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void sdu$discountStatCost(int simulatedTotalStats, CallbackInfoReturnable<Integer> cir) {
        try {
            ServerPlayer buyer = TokenStatDiscount.CURRENT_BUYER.get();
            double raw;
            if (buyer != null) {
                // The discount is the Ragnarok Key's (feature tokenbuffs); keyless it answers 0 and DMZ's price stands.
                raw = TokenBuffHooks.get().statDiscount(buyer);
            } else {
                // THE PRICE ON SCREEN HAS TO MATCH THE PRICE CHARGED. This same method draws the cost and greys
                // out the button on the CLIENT, where there is no buyer in flight and the tokens (player
                // persistent data) were never sent - so DMZ was showing the full price and refusing purchases the
                // server would happily have made. The client's synced copy answers here; on a dedicated server, and
                // on the integrated server's own thread, it is zero and DMZ's price stands.
                //
                // unsafeCallWhenOn returns null off the requested dist (i.e. on the dedicated server, and on the
                // integrated server's own thread since this method also runs server-side). Take it as a boxed
                // Double and null-check it: auto-unboxing null into a double throws NPE, which the catch below
                // would swallow silently on EVERY server-side call. Treat null as "no client discount".
                Double clientDisc = net.minecraftforge.fml.DistExecutor.unsafeCallWhenOn(
                        net.minecraftforge.api.distmarker.Dist.CLIENT,
                        () -> net.shurui.dev.sdu.client.TokenBuffClient::clientStatDiscount);
                if (clientDisc == null || clientDisc <= 0.0) {
                    return;
                }
                raw = clientDisc;
            }
            double disc = Math.min(0.90, Math.max(0.0, raw));
            if (disc <= 0.0) {
                return; // player has no active stat-discount tokens
            }
            int original = cir.getReturnValueI();
            int discounted = Math.max(1, (int) Math.ceil(original * (1.0 - disc)));
            cir.setReturnValue(discounted);
        } catch (Throwable t) {
            // any failure leaves DMZ's original cost
        }
    }
}
