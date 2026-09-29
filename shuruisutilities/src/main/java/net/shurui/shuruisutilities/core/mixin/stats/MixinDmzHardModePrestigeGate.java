package net.shurui.shuruisutilities.core.mixin.stats;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dragonminez.common.quest.Difficulty;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.core.config.Features;
import net.shurui.shuruisutilities.prestige.PrestigeManager;

/**
 * Hard mode is a prestige reward: a player must be prestige 1 or higher to pick it.
 *
 * <p>The gate sits on DMZ's {@code SetStoryDifficultyC2S}, which is the packet the difficulty CHOICE sends, and
 * deliberately nowhere else. Two other paths reach the same setter and neither should be gated: SU's own
 * {@code /sagadifficulty} is an operator command whose whole purpose is to override the latch, and DMZ's
 * {@code ChangeDifficultyWish} is a wish the player already paid a dragon for. Gating the shared setter would
 * have caught all three and made the admin command unable to do the one thing it exists for.
 *
 * <p>Injected into the packet's enqueued work rather than {@code handle} itself. {@code handle} is where Forge's
 * {@code setPacketHandled(true)} lives, so cancelling THAT would leave the packet formally unhandled and log a
 * warning on every rejected click; cancelling the work inside it drops only the difficulty change.
 *
 * <p>{@code remap = false} for DMZ's own names and {@code require = 0} throughout: if a future DMZ reshapes this
 * packet the gate degrades to "hard mode is ungated", which is the pre-existing behaviour, rather than failing
 * the mixin apply and taking the whole mod down with it.
 */
@Mixin(targets = "com.dragonminez.common.network.C2S.SetStoryDifficultyC2S", remap = false)
public abstract class MixinDmzHardModePrestigeGate
{
    /** Prestige a player needs before hard mode is selectable. */
    private static final int REQUIRED_PRESTIGE = 1;

    @Shadow(remap = false)
    private Difficulty difficulty;

    // The enqueued half of handle(Supplier). Selected by descriptor because a bare lambda name is the kind of
    // selector that quietly matches the wrong member; this one takes the Forge context, which is where the
    // sending player comes from.
    @Inject(
            method = "lambda$handle$1(Lnet/minecraftforge/network/NetworkEvent$Context;)V",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void su$gateHardMode(NetworkEvent.Context ctx, CallbackInfo ci)
    {
        try
        {
            if (!Features.enabled(Features.PRESTIGE))
                return;
            if (difficulty != Difficulty.HARD)
                return;
            ServerPlayer player = ctx == null ? null : ctx.getSender();
            if (player == null)
                return;
            int level = PrestigeManager.level(player);
            if (level >= REQUIRED_PRESTIGE)
                return;

            player.sendSystemMessage(Component.literal("Hard mode unlocks at prestige " + REQUIRED_PRESTIGE
                    + " (you are prestige " + level + ").").withStyle(ChatFormatting.RED));
            ci.cancel();
        }
        catch (Throwable ignored)
        {
            // Never let the gate break DMZ's difficulty handling; on any error the choice goes through.
        }
    }
}
