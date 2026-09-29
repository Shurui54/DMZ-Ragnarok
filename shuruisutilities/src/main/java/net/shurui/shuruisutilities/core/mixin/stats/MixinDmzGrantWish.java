package net.shurui.shuruisutilities.core.mixin.stats;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dragonminez.common.dragonball.DragonDefinition;
import com.dragonminez.common.init.entities.dragon.DragonWishEntity;
import com.dragonminez.common.wish.Wish;
import com.dragonminez.common.wish.WishManager;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.compat.dmz.SuSsgKnowledgeWish;
import net.shurui.shuruisutilities.compat.dmz.SuSsj5Wish;
import net.shurui.shuruisutilities.ritual.WishRitualManager;

/**
 * Two jobs on DMZ's server-side wish grant, both reading the {@code GrantWishC2S} packet itself.
 *
 * <h2>Per-wish counter for the rituals</h2>
 * DMZ has no wish counter of its own, and its {@code DMZEvent.DragonSummonedEvent} fires per SUMMON (not per wish) and
 * does not say how many wishes were taken, so accurate per-set-per-wish counting has to read the packet. We re-find the
 * same nearest, un-granted, owned dragon DMZ is about to grant from (mirroring DMZ's own 50-block search and owner
 * filter), read its ball set ({@code earth} / {@code namek}) and its per-summon wish cap, and feed
 * {@link WishRitualManager} the number of wishes this summon grants.
 *
 * <h2>Server-side eligibility gate</h2>
 * The wish list a client sees is a menu, and hiding one of our two Earth ritual rows there is only a suggestion: a
 * modified or stale client can still send the index of a wish it should not have. DMZ resolves the picked indices
 * against its own list and, crucially, calls {@code dragon.setGrantedWish(true)} BEFORE running any wish, so a wish that
 * refuses itself (a non-saiyan taking the SSG knowledge, a saiyan below the level floor, a repeat) would still spend the
 * dragon and leave the player with nothing. So we resolve the same indices against the same list here, at the HEAD of
 * DMZ's grant runnable, and if any resolved row is one of ours that this player is not eligible to take, we CANCEL the
 * whole grant. The dragon is never marked granted, the player is told why and reminded to choose again, and because the
 * dragon is untouched they simply re-open it (it opens client-side on interact) and pick another wish.
 *
 * <p>{@code require = 0} so a DMZ rename degrades to no counting and no gate rather than a failed mixin; everything is
 * wrapped so it never disturbs DMZ's own wish handling except by the deliberate cancel above.
 */
@Mixin(targets = "com.dragonminez.common.network.C2S.GrantWishC2S", remap = false)
public abstract class MixinDmzGrantWish
{
    @Shadow @Final private List<Integer> selectedWishIndices;

    @Inject(method = "lambda$handle$1", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void su$countWish(Supplier<NetworkEvent.Context> ctxSupplier, CallbackInfo ci)
    {
        try
        {
            ServerPlayer player = ctxSupplier.get().getSender();
            if (player == null)
                return;
            ServerLevel level = player.serverLevel();
            // The same dragon DMZ resolves against: nearest within 50 blocks that this player owns and has not spent.
            String ownerName = player.getName().getString();
            DragonWishEntity dragon = level
                    .getEntitiesOfClass(DragonWishEntity.class, player.getBoundingBox().inflate(50.0D),
                            e -> !e.hasGrantedWish() && ownerName.equals(e.getOwnerName()))
                    .stream().findFirst().orElse(null);
            if (dragon == null)
                return;
            DragonDefinition def = dragon.getDragonDefinition();
            if (def == null)
                return;

            // Resolve the picked indices exactly as GrantWishC2S will: de-duplicated, capped at the wish count, and
            // dropping out-of-range indices, against the server's authoritative list for this dragon's wish screen.
            if (su$blocked(player, def))
            {
                player.sendSystemMessage(Component.translatable("ritual.dmz_ragnarok.wish.retry"));
                ci.cancel();
                return;
            }

            String ballSet = def.getBallSetId();
            int cap = Math.max(1, def.getWishCount());
            int wishes = selectedWishIndices == null ? 0
                    : (int) selectedWishIndices.stream().filter(i -> i != null && i >= 0).distinct().count();
            wishes = Math.min(wishes, cap);
            if (wishes <= 0)
                return;

            WishRitualManager.onWishGranted(player, ballSet, wishes);
        }
        catch (Throwable ignored)
        {
            // never disturb DMZ's own wish handling
        }
    }

    // True when at least one resolved wish is one of ours that this player cannot take. Sends the specific reason for
    // each blocked row (not a saiyan, below the level floor, already earned) as a side effect, so the caller only adds
    // the shared "choose another" line. Never throws.
    private boolean su$blocked(ServerPlayer player, DragonDefinition def)
    {
        List<Wish> all = WishManager.getAllWishes().get(def.getWishScreenId());
        if (all == null || all.isEmpty() || selectedWishIndices == null)
            return false;
        int maxWishes = Math.max(0, def.getWishCount());
        int taken = 0;
        boolean blocked = false;
        for (int index : new LinkedHashSet<>(selectedWishIndices))
        {
            if (taken >= maxWishes)
                break;
            if (index < 0 || index >= all.size())
                continue;
            taken++;
            Wish wish = all.get(index);
            if (SuSsgKnowledgeWish.isSsgKnowledge(wish) && WishRitualManager.ssgKnowledgeWishBlocked(player))
                blocked = true;
            else if (SuSsj5Wish.isSsj5(wish) && WishRitualManager.ssj5WishBlocked(player))
                blocked = true;
        }
        return blocked;
    }
}
