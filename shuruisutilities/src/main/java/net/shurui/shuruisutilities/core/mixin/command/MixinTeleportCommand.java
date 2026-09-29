package net.shurui.shuruisutilities.core.mixin.command;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.TeleportCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import net.shurui.dev.sdu.api.SpaceHook;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.misc.SafeSpotResolver;
import net.shurui.shuruisutilities.world.space.SpaceKeys;
import net.shurui.shuruisutilities.api.key.TeleportHooks;

/**
 * Routes the VANILLA /tp command through SU safe landing, because nothing else in the suite does. Xaero's World Map
 * teleport is configured (in the live client's xaeroworldmap.txt) to emit the plain vanilla teleport command for all
 * four of its forms:
 * <pre>
 *   /tp @s {x} {y} {z}                              (map click, same dimension)
 *   /execute as @s in {d} run tp {x} {y} {z}        (map click, different dimension)
 *   /tp @s {name}                                   (teleport to a player)
 *   /tp @s {x} {y} {z}                              (waypoint)
 * </pre>
 * with partial_y_teleport on, so Xaero GUESSES the Y from map data and regularly buries the player in terrain. SU's
 * own /tp, /home, /warp already relocate to a safe spot; the vanilla command did not, which is the "teleporting people
 * into the ground" bug.
 *
 * <h3>Why a mixin and not the Forge event</h3>
 * All four forms reach {@link TeleportCommand} performTeleport, which fires {@code EntityTeleportEvent.TeleportCommand}
 * (cancellable, coordinates settable). That event would cover the FORMS, but it is insufficient here: it exposes only
 * the teleported entity and the target coordinates, never the DESTINATION {@link ServerLevel} that
 * {@link SafeSpotResolver} must read blocks from, and never the command SOURCE needed to exempt command blocks /
 * functions / the console. For the two cross-dimension forms the teleported entity's own level is still the SOURCE
 * dimension at event time, so resolving against it would read the wrong world. performTeleport, by contrast, hands us
 * the destination level and the source directly, and injecting at its TAIL means the player has already been moved
 * (same or cross dimension), so we read the real landing level off the player itself and only nudge them if they
 * ended up somewhere unsafe. That is a strict post-correction: vanilla does the whole teleport, we tidy the result.
 *
 * <p>require = 0: this targets a vanilla Minecraft class, so a mapping/refactor upstream must DEGRADE (no safe
 * landing) rather than crash the game, per the suite's Minecraft-mixin rule.
 */
@Mixin(TeleportCommand.class)
public class MixinTeleportCommand
{
    // Injected at the natural end of performTeleport (the player has already been teleported by this point).
    //
    // The parameter list must mirror the target's EXACTLY, then CallbackInfo. Mixin permits only two shapes for an
    // @Inject handler: every target argument, or none of them. A leading SUBSET is not allowed and fails at APPLY with
    // "Invalid descriptor", which is a malformed-injector error and therefore fatal even under require = 0 (that flag
    // governs a target that cannot be FOUND, not a handler that cannot be BOUND). An earlier version of this mixin
    // declared only the six arguments it uses and crashed every client on startup.
    //
    // The trailing LookAt is package-private in net.minecraft.server.commands, so it cannot be named from here.
    // @Coerce lets it be declared as Object: the descriptor still matches because Mixin rewrites the handler's
    // signature to the target's real type. The Set is declared with a wildcard because generics erase to
    // Ljava/util/Set; anyway, which keeps RelativeMovement out of the imports.
    @Inject(method = "performTeleport", at = @At("TAIL"), require = 0)
    private static void su$safeLandVanillaTp(CommandSourceStack source, Entity entity, ServerLevel destLevel,
            double x, double y, double z, Set<?> relativeMovements, float yRot, float xRot,
            @Coerce Object lookAt, CallbackInfo ci)
    {
        // Only rescue players. A non-player /tp target (a spawner or command block placing a mob) is deliberate and
        // must keep its exact spot, and a non-player entity teleported cross-dimension is RECREATED, so this captured
        // instance would be the stale removed one anyway.
        if (!(entity instanceof ServerPlayer player))
        {
            return;
        }
        // Only act when the teleport actually landed the player in the intended destination level. This guards the
        // cancelled / teleportTo==false paths (the player never moved), so we never re-resolve against the wrong spot.
        if (player.serverLevel() != destLevel)
        {
            return;
        }

        // A player dropped INTO space by a vanilla /tp (chiefly Xaero's "teleport to player") must not be bounced
        // straight home: the space descend-out-of-space check would fire next tick because the player arrives below
        // the return altitude. Stamp the same arrival grace an ordinary space entry uses. Done regardless of the
        // safe-landing pass or the exemptions below: arriving in space and being instantly yanked is never intended,
        // and this is the "cannot teleport to people in space" half of the reported bug.
        if (SpaceKeys.isSpace(destLevel))
        {
            // through the core SpaceHook so this core mixin never names the Space module; a no-op when Space is absent
            // (there is then no space dimension to arrive in anyway).
            SpaceHook.grantArrivalGrace(player);
        }

        // A player who can fly does not want to be put on the ground. Teleporting to a friend hovering over
        // a city should land you beside THEM, not on a roof far below, so a flier keeps the exact requested
        // position and everyone else still gets the safe-landing pass below.
        if (net.shurui.shuruisutilities.compat.dmz.DmzFlight.canFly(player))
        {
            return;
        }

        // Config master switch (default on). Off = leave the vanilla /tp command completely alone (exact coordinates).
        // The switch lives in the Teleport module (Ragnarok Key); keyless the hook answers its default, on.
        if (!TeleportHooks.get().safeLandVanillaTp())
        {
            return;
        }

        // Exempt automated / structural senders: command blocks, functions and the console teleport deliberately and
        // must hit the exact spot. The command SOURCE, not the teleported entity, carries the intent, so we read the
        // sender here (getPlayer() is null for any non-player source).
        ServerPlayer sender = source.getPlayer();
        if (sender == null)
        {
            return;
        }

        // Exempt anyone holding the bypass permission (default granted to nobody, so the fix applies to ops too until
        // they opt out). Keyed on the sender because they are the one issuing the command. The node is the Teleport
        // module's (Ragnarok Key); keyless nobody holds it, which is what its NONE default answered there.
        if (TeleportHooks.get().bypassesVanillaTpSafeLanding(sender))
        {
            return;
        }

        // Resolve the requested landing to the nearest safe standing spot in the DESTINATION level, force-loading the
        // chunk first (same helper, same "relocate, never refuse" contract SU's own teleports use). In space this
        // returns the requested spot unchanged (SafeSpotResolver is space-aware and never yanks a space arrival to
        // world spawn), so the grace stamped above is what keeps a space arrival in place, not a relocation.
        SafeSpotResolver.Result safe = SafeSpotResolver.resolve(destLevel, x, y, z);
        if (!safe.moved)
        {
            return;
        }

        // Same-dimension reposition (the player is already in destLevel), keeping their current facing.
        player.connection.teleport(safe.x, safe.y, safe.z, player.getYRot(), player.getXRot());
        // Action-bar notice, resolved in the player's own language client-side.
        player.displayClientMessage(
                Component.translatable("message.dmz_ragnarok.core.vanillatp_relocated"), true);
    }
}
