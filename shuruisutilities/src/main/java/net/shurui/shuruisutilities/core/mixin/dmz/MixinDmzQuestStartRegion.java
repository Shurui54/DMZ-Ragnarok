package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.regions.RegionBypass;
import net.shurui.shuruisutilities.regions.RegionEventHandler;
import net.shurui.shuruisutilities.regions.RegionFlag;

/**
 * Server-side enforcement of the {@link RegionFlag#QUEST_START} region flag. DMZ's
 * {@code QuestService.startQuest(ServerPlayer, String)} is the single non-admin funnel every quest-start path
 * reaches: the quest menu, the saga menu, side quests and talking to a quest NPC all send
 * {@code QuestActionC2S(START)}, whose handler calls exactly this method (proven by SU's own
 * {@code MixinDmzQuestPrestigeGate} and sdu's saga gate hooking the same funnel). We inject at its {@code HEAD}:
 * if the requesting player is standing in a region that DENIES {@code quest-start}, we set the return value to a
 * red rejection message and cancel, so DMZ never accepts the quest, never initialises objectives and never spawns
 * the quest mob. DMZ's caller displays the returned {@link Component} the same way it shows its own start-failure
 * lines, so the client button may still appear but the server refuses.
 *
 * <p>The position checked is the requester's position AT THE MOMENT of the request (the {@code player} argument),
 * which is exactly where DMZ spawns the quest's kill objectives, so this closes the reported abuse of starting a
 * saga at spawn so its mob spawns at spawn. In a party, DMZ starts the quest from a single requester and spawns
 * around that requester; refusing when the requester is in a denied region means the party quest is simply not
 * started from inside the region (no member is force-started there), while a member elsewhere can still start it.
 *
 * <p>{@code RESUMMON} is gated too, on the same flag and the same requester position: it re-spawns the quest mob
 * around the player and so produces the identical "mob at spawn" outcome for an already-accepted quest. It is NOT
 * one of the actions that must keep working inside the region (progress / turn-in / complete / claim / abandon /
 * track), and blocking it does not stop an active quest from ticking. {@code TURN_IN} (completing/claiming) is
 * left untouched.
 *
 * <p>Staff with region bypass ({@code /serverclaim bypass}) are exempt. DMZ's admin quest commands
 * ({@code /dmzquest start}, {@code startsaga}) accept quests directly via {@code pqd.acceptQuest} and do NOT reach
 * this funnel, so they remain a permission-gated override, deliberately not touched.
 *
 * <p>{@code remap = false}: {@code startQuest} / {@code resummonQuest} and the target class resolve against DMZ's
 * own (non-Mojmap) names, like SU's other DMZ mixins. {@code require = 0}: degrade to a no-op (quests behave as if
 * the flag were unset) if DMZ renames these methods, rather than hard-failing the mixin apply. This fails SAFE
 * (the flag simply stops biting, no crash), so it must be launch-tested, not trusted on a green build. The whole
 * check is wrapped in {@code try/catch (Throwable)} so a region-lookup error can never break DMZ's quest handling.
 */
@Mixin(targets = "com.dragonminez.common.quest.QuestService", remap = false)
public abstract class MixinDmzQuestStartRegion
{
    // Full descriptor, not a bare name: QuestService also declares a private 5-arg startQuest overload
    // (ServerPlayer, ServerPlayer, ResolvedQuest, StatsData, Difficulty). A name-only selector would match both
    // and the selected-but-incompatible 5-arg target can raise an apply-time validation error (which require = 0
    // does NOT suppress). Pinning the descriptor selects only the public (ServerPlayer, String) overload.
    @Inject(
            method = "startQuest(Lnet/minecraft/server/level/ServerPlayer;Ljava/lang/String;)Lnet/minecraft/network/chat/Component;",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void su$denyQuestStartInRegion(ServerPlayer player, String questId,
                                                  CallbackInfoReturnable<Component> cir)
    {
        if (su$deniedHere(player) && !su$sagaBypasses(questId))
            cir.setReturnValue(su$deniedMessage());
    }

    @Inject(
            method = "resummonQuest(Lnet/minecraft/server/level/ServerPlayer;Ljava/lang/String;)Lnet/minecraft/network/chat/Component;",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void su$denyResummonInRegion(ServerPlayer player, String questId,
                                                CallbackInfoReturnable<Component> cir)
    {
        if (su$deniedHere(player) && !su$sagaBypasses(questId))
            cir.setReturnValue(su$deniedMessage());
    }

    /**
     * Per-saga opt-out set in the sdu saga editor ("Allow starting in quest-blocked regions"): when the quest's
     * owning saga has the toggle on, the region deny does not apply, so the quest starts (and resummons) here
     * anyway. The lookup lives in sdu (the tree the others read from; sdu never imports shuruisutilities), and
     * fails CLOSED, so on any fault the region flag still bites. Guarded here too so this can never break DMZ's
     * quest handling if sdu is somehow absent at runtime.
     */
    private static boolean su$sagaBypasses(String questId)
    {
        try
        {
            return net.shurui.dev.sdu.saga.SagaRegionBypass.allowsStartInBlockedRegion(questId);
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    private static boolean su$deniedHere(ServerPlayer player)
    {
        try
        {
            if (player == null || RegionBypass.isBypassing(player.getUUID()))
                return false;
            return RegionEventHandler.playerFlagDenied(player, RegionFlag.QUEST_START);
        }
        catch (Throwable ignored)
        {
            // Never let the region check break DMZ's quest handling; on any error, fall through (no cancel).
            return false;
        }
    }

    private static Component su$deniedMessage()
    {
        return Component.translatable("message.dmz_ragnarok.region.quest_start_denied")
                .withStyle(ChatFormatting.RED);
    }
}
