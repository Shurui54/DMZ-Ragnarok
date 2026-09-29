package net.shurui.dev.sdu.mixin;

import com.dragonminez.client.events.ClientStatsEvents;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.shurui.dev.sdu.combat.WeaponBlocking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Owner request: "add the ability to block with weapons". DMZ already supports weapon blocking everywhere
// EXCEPT the client block input. The server (UpdateStatC2S) raises the guard flag with no item check, and the
// damage reduction (CombatEvent) reads only Status.isBlocking(), so a weapon block gets the exact same
// reduction as an unarmed one, no new balance number needed. The one thing that stopped it is DMZ's client
// gate in ClientStatsEvents.lambda$onClientTick$3, which only raises and keeps the guard while BOTH hands are
// empty (the two getItemInHand reads for handsEmpty, re-checked at the start branch). We redirect exactly
// those getItemInHand reads so a recognised weapon reads as an empty hand; DMZ then raises the guard, sends
// its own BLOCK packet, plays base.block and suppresses the left-click attack, all unchanged.
//
// Scope is airtight: LocalPlayer.getItemInHand is invoked four times in the whole class, all inside
// lambda$onClientTick$3 and all part of the block gate (verified by javap against the pinned 2.1.3 jar), so
// this touches nothing else. Ki weapons, technique charging and open screens still cancel the guard because
// DMZ gates those on isKiWeaponActive()/isChargingTechnique()/the screen, not on the hand item. We never
// touch right-click use, so a weapon's own right-click (Tinkers abilities, axe stripping) still fires: the
// DMZ block key defaults to right mouse, so both happen and we do not hijack the button.
//
// remap = false for the DMZ class and its synthetic lambda name; the redirect is remap = true so the
// Minecraft getItemInHand target refmaps to production, matching the AbstractKiProjectileMixin idiom.
// require = 0: a DMZ refactor makes this a silent no-op (blocking falls back to empty-hand only) instead of
// crashing the client. Client-only, so it does not appear in a dedicated-server mixin log.
@Mixin(value = ClientStatsEvents.class, remap = false)
public abstract class WeaponBlockInputMixin {

    @Redirect(
            method = "lambda$onClientTick$3",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;"),
            remap = true,
            require = 0)
    private static ItemStack sdu$weaponBlockHand(LocalPlayer player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        // Report a recognised weapon as an empty hand for DMZ's block gate only. Empty and non-weapon items
        // (shields, bows, food) pass through, so vanilla shield blocking and use-items stay unaffected.
        if (WeaponBlocking.isBlockableWeapon(held)) {
            return ItemStack.EMPTY;
        }
        return held;
    }
}
