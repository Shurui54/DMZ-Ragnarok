package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.player.Player;

import net.shurui.shuruisutilities.compat.tournaments.TournamentPvpBridge;

/**
 * A party never suppresses PvP inside a tournament bout.
 *
 * <p>DMZ parties carry their own PvP switch, and it is OFF by default so friends do not club each other while
 * questing. That is the right default everywhere except a tournament, where two players who happen to be partied
 * would step into the ring and find neither could hurt the other: the bout cannot be fought, cannot be won and
 * has to be voided. Being in a party is not a statement about a match you both entered on purpose.
 *
 * <p>{@code isPartyPvpEnabled} is the one place DMZ asks the question, and both of its callers want the same
 * answer here, which is why the gate goes on the query rather than on either of them. {@code CombatEvent} reads
 * it to decide whether to cancel the hurt, and {@code TargetHelper} reads it to decide whether the fighter can
 * be TARGETED at all: patching only the damage side would leave two tournament fighters unable to lock on to
 * each other, which is half a fix that reads as the same bug.
 *
 * <p>Scoped as tightly as it can be. {@link TournamentPvpBridge#isActiveFighter} is only true while a tournament
 * is in the MATCH state and this player is in the live bout, undowned and uneliminated, so it is self-releasing:
 * nothing is written anywhere, and the moment the bout ends the party's own setting is back in charge. The
 * bridge also fails CLOSED on any error, so a broken or absent tournaments module can never force PvP on.
 *
 * <p>{@code remap = false} because the target is DMZ's own class, and {@code require = 0} so a rename in a future
 * DMZ degrades to "parties behave as they always did" instead of failing the mixin apply.
 */
@Mixin(targets = "com.dragonminez.common.quest.PartyManager", remap = false)
public abstract class MixinDmzPartyPvpTournament
{
    @Inject(
            method = "isPartyPvpEnabled(Lnet/minecraft/world/entity/player/Player;)Z",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void su$partyPvpAlwaysOnInTournament(Player player, CallbackInfoReturnable<Boolean> cir)
    {
        try
        {
            if (player == null)
                return;
            if (TournamentPvpBridge.isActiveFighter(player.getUUID()))
                cir.setReturnValue(Boolean.TRUE);
        }
        catch (Throwable ignored)
        {
            // A failed check must never break DMZ's party handling: fall through to DMZ's own answer.
        }
    }
}
