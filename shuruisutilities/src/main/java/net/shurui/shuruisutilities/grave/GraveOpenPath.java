package net.shurui.shuruisutilities.grave;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.jail.JailData;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Opens a grave totem straight from the use-on-block packet, before anything that protects land can refuse the click.
 *
 * <h2>Why at the packet</h2>
 *
 * <p>A right click on a totem used to travel the whole vanilla path: {@code ServerLevel#mayInteract} (spawn
 * protection and the world border), then Forge's {@code RightClickBlock} event, where every protection handler sits
 * at HIGH or HIGHEST priority, and only then {@link GraveEventHandler#onRightClick} at NORMAL priority, which does not
 * receive a cancelled event. Our own protections (guild claims, regions and serverclaims, world zones, spawn
 * protection) each carry a grave exemption ({@link GraveAccess}), but anything else that cancels the click first
 * stops the totem from opening: a claims or protection mod the server runs that we do not ship, or Lootr's redirect
 * of the spawn-protection check. That is "a totem in a protected area cannot be opened". Deciding at the packet,
 * right after the server-thread hand-off and before all of that, makes the open independent of every protection,
 * ours or not.
 *
 * <h2>Who may open it this way</h2>
 *
 * <p>Exactly the people a totem is meant for, whatever protection it stands in ({@link GraveAccess#mayOpen}): its
 * owner, anybody when it holds a dragon ball (a ball totem is what the radar leads hunters to), and staff (permission
 * level 2). Anyone else gets the normal path, so another player's ordinary loot grave in protected land stays shut
 * exactly as before, and no other container is affected (only a position registered in {@link GraveStorage}, its
 * fence or the head on it, qualifies). A jailed or frozen player, a spectator, or a click from beyond reach is not
 * handled here either, so those rules still apply. Breaking the totem is untouched.
 */
public final class GraveOpenPath
{
    private GraveOpenPath() {}

    /** Vanilla's use-on-block reach check (squared distance to the block centre). */
    private static final double MAX_REACH_SQR = 64.0D;

    /** Persisted tag the moderation freeze stamps on a frozen player (see the key's FreezeManager). */
    private static final String FROZEN_TAG = "sufreeze";

    /**
     * Open the totem the player clicked, if this player may open it. Returns true when it opened (the caller then
     * skips the rest of the click). Never throws.
     */
    public static boolean tryOpen(ServerPlayer player, BlockHitResult hit)
    {
        try
        {
            if (player == null || hit == null || player.isSpectator())
                return false;
            ServerLevel level = player.serverLevel();
            BlockPos clicked = hit.getBlockPos();
            GraveStorage storage = GraveStorage.get(level);
            BlockPos gravePos = storage.has(clicked) ? clicked : (storage.has(clicked.below()) ? clicked.below() : null);
            if (gravePos == null)
                return false;
            if (player.distanceToSqr(Vec3.atCenterOf(clicked)) > MAX_REACH_SQR)
                return false;
            if (JailData.isJailed(player) || frozen(player))
                return false;
            if (!GraveAccess.mayOpen(player, level, gravePos))
                return false;
            GraveManager.open(player, level, gravePos);
            // The client may have predicted a block placement against the totem: put both spots back.
            player.connection.send(new ClientboundBlockUpdatePacket(level, clicked));
            player.connection.send(new ClientboundBlockUpdatePacket(level, clicked.relative(hit.getDirection())));
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[Grave] direct totem open failed, falling back to the normal click: {}",
                    t.toString());
            return false;
        }
    }

    private static boolean frozen(ServerPlayer player)
    {
        return player.getPersistentData().getCompound(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG)
                .getBoolean(FROZEN_TAG);
    }
}
