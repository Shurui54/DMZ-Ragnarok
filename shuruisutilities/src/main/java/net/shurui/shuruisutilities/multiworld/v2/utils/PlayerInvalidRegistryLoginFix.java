package net.shurui.shuruisutilities.multiworld.v2.utils;

import net.shurui.shuruisutilities.commons.selections.WarpPoint;
import net.shurui.shuruisutilities.core.misc.TeleportHelper;
import net.shurui.shuruisutilities.multiworld.v2.MultiworldEngine;
import net.shurui.shuruisutilities.shard.ShardSync;
import net.shurui.shuruisutilities.util.PlayerInfo;
import net.shurui.shuruisutilities.util.events.ServerEventHandler;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Parks a player who quits inside a dynamic dimension whose dimensionType is not one of the vanilla ones, then puts
 * them back where they were on their next login.
 *
 * <h2>What this is for</h2>
 * A dynamic dimension registered with a non-vanilla dimensionType is written into the player's save as something the
 * client cannot resolve, and the client then believes it is in {@code minecraft:overworld}. Parking the player at a
 * known good overworld position on the way out, and restoring the real spot on the way in, keeps the saved state
 * valid. The real position is held separately in {@link PlayerInfo#getActualLogOutPoint()}.
 *
 * <h2>Why a shard handoff must be exempt, and it is not a small detail</h2>
 * On this network a server swap is a disconnect and a reconnect. Before this guard existed the logout half fired on
 * every hop out of ANY {@code dmz_ragnarok:} dimension, which {@link MultiworldEngine#isMultiWorld} treats as
 * multiworld, so kaiow, namekow, smp, space, every planet surface and every dungeon floor. It runs at HIGH and
 * {@code ShardSync.onLogout} does not, so it always won the race, and {@code ShardPayload.capturePosition} then wrote
 * {@code minecraft:overworld 0,1000,0} into the vault as the player's position. Measured on 2026-09-21: 64 firings on
 * main and 23 on OW1 in a single log window, 21 logins at exactly (0, 1000, 0) on OW1 and 48 PositionSanitizer
 * rescues. The player symptom is "I timed out in kaiow and came back at spawn", or arriving a thousand blocks up.
 *
 * <p>A hop needs none of this. The destination sends a fresh join with a dimension it hosts, so there is no broken
 * vanilla save to work around, and the vault already carries the real position. Skipping the park on a handoff is
 * therefore correct AND makes this handler order independent: it no longer matters whether it runs before or after
 * the vault capture, because on a hop it does nothing at all.
 *
 * <h2>Why the login half needs a server stamp</h2>
 * {@link PlayerInfo} travels in the vault payload, so a point parked on one shard arrives on the next shard the
 * player logs into and used to be replayed there, teleporting them to coordinates belonging to a different server.
 * {@code RespawnHandler} documents that replay as the "spawned on Vegeta" bug. The point is now stamped with the
 * server that parked it and only replayed by that server.
 */
public class PlayerInvalidRegistryLoginFix extends ServerEventHandler {
	@SubscribeEvent(priority = EventPriority.HIGH)
	public void playerLogin(PlayerLoggedInEvent event) {
		PlayerInfo player = PlayerInfo.get(event.getEntity().getUUID());
		// Only replay a point THIS server parked. A point that arrived in the vault from another shard describes
		// coordinates in that shard's world and must be dropped rather than honoured, or the player is teleported
		// somewhere they have never been. Off a shard network both sides are null and this reads as it always did.
		if (player.getActualLogOutPoint() != null && !player.actualLogOutPointIsOurs()) {
			player.setActualLogOutPoint(null);
			return;
		}
		if (player.getActualLogOutPoint() != null) {
			TeleportHelper.doTeleport(event.getEntity(), player.getActualLogOutPoint());
			player.setActualLogOutPoint(null);
			ChatOutputHandler.chatWarning(event.getEntity(), "You logged into a dynamic dimension using a non-vanilla dimensionType, your game will think you are in minecraft:overworld!"
					+ " This could cause issues with client mods thinking you are in the overworld, but according the server you are not. Please refrain from using multiworlds in the configuration!");
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGH)
	public void playerLoggedOut(PlayerLoggedOutEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player))
			return;
		// A shard handoff is a disconnect, and parking the player would be captured into the vault as their real
		// position. See the class note: this is the whole bug. Checked first and cheaply, and a no-op when the shard
		// layer is off, so a single server behaves exactly as before.
		if (ShardSync.isHandingOff(player.getUUID()))
			return;
		if (!MultiworldEngine.isMultiWorld(player.serverLevel()))
			return;
		if (MultiworldEngine.manager().getProviderHandler().getVanillaDimensionTypes()
				.containsValue(player.serverLevel().dimensionType()))
			return;
		PlayerInfo info = PlayerInfo.get(player.getUUID());
		info.setActualLogOutPoint(new WarpPoint(player));
		player.teleportTo(ServerLifecycleHooks.getCurrentServer().getLevel(Level.OVERWORLD), 0, 1000, 0, 0, 0);
	}
}
