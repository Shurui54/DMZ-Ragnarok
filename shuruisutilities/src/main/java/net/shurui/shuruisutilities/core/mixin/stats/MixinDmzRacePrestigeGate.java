package net.shurui.shuruisutilities.core.mixin.stats;

import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dragonminez.common.network.C2S.StatsSyncC2S;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.core.config.Features;
import net.shurui.shuruisutilities.prestige.PrestigeManager;
import net.shurui.shuruisutilities.prestige.PrestigeSettings;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

/**
 * Server-side defense-in-depth for prestige-gated races. DMZ's race-select screen sends
 * {@code com.dragonminez.common.network.C2S.StatsSyncC2S}, whose {@code handle(packet, ctx)} applies the
 * chosen race to the character (only during initial creation, when {@code Status.isHasCreatedCharacter()} is
 * false, per the DMZ handler). We intercept at the {@code HEAD} of {@code handle}: if the requested race is
 * gated to a prestige level above the sender's active-slot prestige (per {@link PrestigeSettings}), we cancel
 * the whole handler so DMZ never applies the locked race, then notify the player. The sdu client mixin already
 * greys the entry, so this only fires against a tampered/desynced client.
 *
 * <p>{@code remap = false}: {@code handle} and the target class resolve against DMZ's own (non-Mojmap) names,
 * matching SU's other DMZ mixins. {@code require = 0}: degrade to a no-op (rely on the client lock) if DMZ
 * renames the handler in a future version, rather than hard-failing the mixin apply.</p>
 */
@Mixin(targets = "com.dragonminez.common.network.C2S.StatsSyncC2S", remap = false)
public abstract class MixinDmzRacePrestigeGate
{
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, require = 0)
    private static void su$rejectLockedRace(StatsSyncC2S packet, Supplier<NetworkEvent.Context> ctxSupplier, CallbackInfo ci)
    {
        try
        {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ServerPlayer player = ctx.getSender();
            if (player == null || player.getServer() == null)
                return;
            String race = ((AccessorStatsSyncC2S) (Object) packet).su$getRaceName();
            if (race == null || race.isBlank())
                return;

            // Unlock axis (ALWAYS enforced, independent of both the wish-tracking master switch and the Prestige
            // toggle and of prestige level): the shadow dragon races stay locked until the player has earned the
            // matching per-UUID unlock. This is a hard block; even max prestige cannot open it. Checked first so a
            // tampered client can't slip a locked race in by turning the Prestige feature off. The race files are now
            // installed on every DMZ server regardless of the switch (RaceBundleCompat no longer gates extraction on
            // it), so the old "switch off implies the race is never extracted so DMZ rejects it" invariant is GONE. If
            // this check were still keyed on wish-tracking, a tampered client could preview-commit a shadow race with
            // the switch off. So the lock is unconditional and fail-closed here; wish-tracking only governs whether the
            // cinematic path can EARN the unlock, never whether the race is gated.
            String lower = race.toLowerCase(java.util.Locale.ROOT);
            if (net.shurui.shuruisutilities.corrupted.RaceUnlocks.UNLOCK_GATED_RACES.contains(lower)
                    && !net.shurui.shuruisutilities.corrupted.RaceUnlocks.has(player, lower))
            {
                ctx.setPacketHandled(true);
                ci.cancel();
                ChatOutputHandler.chatError(player, "That race is not available to you yet.");
                return;
            }

            // Operator axis: a staff-only race is refused for anyone who is not an operator, at any prestige and
            // whether or not the Prestige feature is on. Before the unlock-gated early return below, so an
            // unlock-gated race can also be marked operator only. Permission level 2 rather than an SU node: an
            // unset SU node reads as ALLOW, and this gate has to fail closed.
            if (PrestigeSettings.get(player.getServer()).isOpOnly(lower) && !player.hasPermissions(2))
            {
                ctx.setPacketHandled(true);
                ci.cancel();
                ChatOutputHandler.chatError(player, "That race is reserved for the staff team.");
                return;
            }

            // Unlock-gated races (e.g. the ritual-gated shadow dragon) are gated by the unlock axis ALONE. The block
            // above already refused them when unowned; an owned one has passed the only gate that applies, so skip the
            // prestige axis entirely. This makes the intent explicit and survives an admin having set, or later
            // setting, a stray prestige requirement on such a race: the ritual stays the only gate regardless.
            if (net.shurui.shuruisutilities.corrupted.RaceUnlocks.UNLOCK_GATED_RACES.contains(lower))
                return;

            // Prestige axis (only when the Prestige feature is on): locked when below the required level.
            if (!Features.enabled(Features.PRESTIGE))
                return;
            MinecraftServer server = player.getServer();
            int required = PrestigeSettings.get(server).getRaceRequired(race);
            if (required <= 0)
                return; // unlocked
            if (PrestigeManager.level(player) >= required)
                return; // player qualifies

            // Locked: cancel the handler so DMZ never applies the race, and mark handled so Forge doesn't warn.
            ctx.setPacketHandled(true);
            ci.cancel();
            ChatOutputHandler.chatError(player, "That race requires prestige " + required
                    + " (you are prestige " + PrestigeManager.level(player) + ").");
        }
        catch (Throwable ignored)
        {
            // Never let the gate check break DMZ's packet handling; on any error, fall through (no cancel).
        }
    }
}
