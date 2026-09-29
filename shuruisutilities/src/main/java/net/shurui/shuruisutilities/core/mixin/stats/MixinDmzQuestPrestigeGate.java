package net.shurui.shuruisutilities.core.mixin.stats;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.core.config.Features;
import net.shurui.shuruisutilities.prestige.PrestigeManager;
import net.shurui.shuruisutilities.prestige.PrestigeSettings;

/**
 * Server-side prestige gate for DMZ quests. DMZ's {@code QuestService.startQuest(ServerPlayer, String)} is the
 * single entry point every quest-start path funnels through, and it returns a {@link Component} that DMZ's caller
 * displays to the player exactly the way it displays its own start-requirement failures. We inject at its
 * {@code HEAD}: if the requested quest is gated to a prestige level above the player's active-slot prestige (per
 * {@link PrestigeSettings}), we set the return value to a red rejection message and cancel, so DMZ never runs its
 * start logic and the player sees a normal-looking failure line.
 *
 * <p>{@code remap = false}: {@code startQuest} and the target class resolve against DMZ's own (non-Mojmap) names,
 * matching SU's other DMZ mixins. {@code require = 0}: degrade to a no-op (quests behave as if ungated) if DMZ
 * renames the method in a future version, rather than hard-failing the mixin apply. The whole body is wrapped in a
 * {@code try/catch (Throwable)} so a check error can never break DMZ's quest handling.</p>
 */
@Mixin(targets = "com.dragonminez.common.quest.QuestService", remap = false)
public abstract class MixinDmzQuestPrestigeGate
{
    // Full descriptor, not a bare name: QuestService also declares a private 5-arg startQuest overload
    // (ServerPlayer, ServerPlayer, ResolvedQuest, StatsData, Difficulty). A name-only selector would match
    // BOTH, and the selected-but-incompatible 5-arg target can raise an injection validation error at apply
    // time (which require = 0 does NOT suppress). Pinning the descriptor selects only the public (ServerPlayer,
    // String) overload. Do not simplify this back to a bare method name.
    @Inject(
            method = "startQuest(Lnet/minecraft/server/level/ServerPlayer;Ljava/lang/String;)Lnet/minecraft/network/chat/Component;",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void su$rejectLockedQuest(ServerPlayer player, String questId, CallbackInfoReturnable<Component> cir)
    {
        try
        {
            if (!Features.enabled(Features.PRESTIGE))
                return;
            if (player == null || player.getServer() == null || questId == null)
                return;
            MinecraftServer server = player.getServer();
            int required = PrestigeSettings.get(server).getQuestRequired(questId);
            if (required <= 0)
                return; // unlocked
            if (PrestigeManager.level(player) >= required)
                return; // player qualifies

            // Locked: hand DMZ's caller a red failure Component and skip the real start logic.
            cir.setReturnValue(Component.literal("That quest requires prestige " + required
                    + " (you are prestige " + PrestigeManager.level(player) + ").").withStyle(ChatFormatting.RED));
        }
        catch (Throwable ignored)
        {
            // Never let the gate check break DMZ's quest handling; on any error, fall through (no cancel).
        }
    }
}
