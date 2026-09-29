package net.shurui.shuruisutilities.core.mixin.stats;

import java.util.Locale;
import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dragonminez.common.network.C2S.CreateCharacterC2S;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.core.config.Features;
import net.shurui.shuruisutilities.corrupted.RaceUnlocks;
import net.shurui.shuruisutilities.prestige.PrestigeManager;
import net.shurui.shuruisutilities.prestige.PrestigeSettings;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

/**
 * Server-side commit gate for character creation. This closes a real hole and adds the sub-race axis.
 *
 * <p><b>The hole.</b> {@link net.shurui.shuruisutilities.core.mixin.stats.MixinDmzRacePrestigeGate} only guards
 * {@code StatsSyncC2S}, which is DMZ's PREVIEW sync (sent on every arrow-key press in the race carousel). The actual
 * character COMMIT is {@code com.dragonminez.common.network.C2S.CreateCharacterC2S.handle}, which contains zero
 * {@code isRaceLoaded} references and calls {@code StatsData.initializeWithRaceAndClass} directly. No SU or sdu mixin
 * touched it, so a tampered client could commit a prestige-locked (or sub-race-locked) race by simply never sending the
 * preview packet. Verified with {@code javap -p} against {@code dragonminez-2.1.3.jar}. This mixin intercepts the HEAD
 * of that commit and cancels it when the race is not available to the sender.
 *
 * <p><b>Two axes, mirroring the preview gate:</b>
 * <ol>
 *   <li><b>Unlock axis</b> (always enforced, independent of the wish-tracking master switch): a gated race stays locked
 *       until the player owns the matching per-UUID unlock. This covers the base {@code shadow_dragon} race AND the
 *       shadow dragon sub-races. {@code half_saiyan} is a sub-race but is free, so it passes (see
 *       {@link RaceUnlocks#hasRaceAccess}). A hard block that even max prestige cannot open; checked first so turning
 *       the Prestige feature off cannot slip a locked race through. The race files are installed on every DMZ server
 *       regardless of the switch, so this lock is unconditional and fail-closed; wish-tracking only governs whether the
 *       cinematic path can EARN the unlock, never whether the race is gated.</li>
 *   <li><b>Operator axis</b> (always enforced, like the unlock axis): a race marked operator only in the prestige
 *       admin screen's Race tab is refused for anyone without permission level 2. Independent of prestige, so it
 *       cannot be reached by playing, and independent of the Prestige feature toggle.</li>
 *   <li><b>Prestige axis</b> (only when the Prestige feature is on): locked below the required prestige level.</li>
 * </ol>
 *
 * <p>{@code UpdateCharacterC2S} is deliberately NOT gated: {@code javap -p} shows it has no {@code raceName} field, so a
 * character update cannot change race and there is nothing to block.
 *
 * <p><b>Mixin conventions</b> (all three match {@link MixinDmzRacePrestigeGate}): {@code remap = false} because
 * {@code handle} and the target resolve against DMZ's own (non-Mojmap) names; {@code require = 0} so a DMZ rename
 * degrades this to a no-op instead of hard-failing the mixin apply; and the whole body is wrapped in
 * {@code try/catch(Throwable)} so any fault falls through to DMZ rather than breaking packet handling. The
 * {@code @Inject} selector pins the FULL method descriptor
 * {@code (Lcom/dragonminez/common/network/C2S/CreateCharacterC2S;Ljava/util/function/Supplier;)V} (verified with
 * {@code javap -p -s}) rather than a bare name, so it cannot bind an unintended overload.
 */
@Mixin(targets = "com.dragonminez.common.network.C2S.CreateCharacterC2S", remap = false)
public abstract class MixinDmzCreateCharacterRaceGate
{
    @Inject(
            method = "handle(Lcom/dragonminez/common/network/C2S/CreateCharacterC2S;Ljava/util/function/Supplier;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private static void su$rejectLockedRaceOnCommit(CreateCharacterC2S packet,
            Supplier<NetworkEvent.Context> ctxSupplier, CallbackInfo ci)
    {
        try
        {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ServerPlayer player = ctx.getSender();
            if (player == null || player.getServer() == null)
                return;
            String race = ((AccessorCreateCharacterC2S) (Object) packet).su$getRaceName();
            if (race == null || race.isBlank())
                return;
            String lower = race.toLowerCase(Locale.ROOT);

            // Unlock axis: covers the base shadow dragon race and the shadow dragon sub-races; half_saiyan is free.
            // ALWAYS enforced, independent of both the wish-tracking master switch and the Prestige toggle, and checked
            // first. The race FILES are now installed on every DMZ server regardless of the switch (RaceBundleCompat no
            // longer gates extraction on it), so the old "switch off implies the folder is absent so DMZ rejects the
            // unknown race" invariant is GONE. If this check were still keyed on wish-tracking, a tampered client could
            // commit shadow_dragon with the switch off and DMZ would happily apply it. So the lock is unconditional and
            // fail-closed here; wish-tracking only governs whether the cinematic path can EARN the unlock, never whether
            // the race is gated. hasRaceAccess returns true for any non-gated race, so ordinary DMZ races are unaffected.
            if (!RaceUnlocks.hasRaceAccess(player, lower))
            {
                ctx.setPacketHandled(true);
                ci.cancel();
                // The base shadow dragon race gets its own themed refusal so the carousel tooltip and the commit
                // rejection read the same. Every other locked race (sub-races, anything future) keeps the generic
                // line so no other secret leaks its own flavour text. Both go through the translatable chat path
                // (no colour codes), so the client resolves them in the player's language.
                if (RaceUnlocks.SHADOW_DRAGON_RACE.equals(lower))
                    ChatOutputHandler.chatError(player, "Defile the dragonballs to awaken this form of PURE MALICE!");
                else
                    ChatOutputHandler.chatError(player, "That race is not available to you yet.");
                return;
            }

            // Operator axis: a staff-only race is refused for anyone who is not an operator, whatever their prestige
            // and whether or not the Prestige feature is on. Checked before the unlock-gated early return below, so
            // marking an unlock-gated race operator only is still honoured. Not keyed on the SU permission system on
            // purpose: an UNSET SU node reads as ALLOW, which would hand a staff race to everybody the moment the
            // node went missing, and this gate must fail closed.
            if (PrestigeSettings.get(player.getServer()).isOpOnly(lower) && !player.hasPermissions(2))
            {
                ctx.setPacketHandled(true);
                ci.cancel();
                ChatOutputHandler.chatError(player, "That race is reserved for the staff team.");
                return;
            }

            // Unlock-gated races (e.g. the ritual-gated shadow dragon) are gated by the unlock axis ALONE. hasRaceAccess
            // above already refused them when unowned; an owned one has passed the only gate that applies, so skip the
            // prestige axis entirely. This makes the intent explicit and survives an admin having set, or later setting,
            // a stray prestige requirement on such a race: the ritual stays the only gate regardless.
            if (RaceUnlocks.UNLOCK_GATED_RACES.contains(lower))
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

            // Locked: cancel the commit so DMZ never applies the race, and mark handled so Forge does not warn.
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
