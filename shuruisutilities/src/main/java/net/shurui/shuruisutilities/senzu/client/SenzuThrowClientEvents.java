package net.shurui.shuruisutilities.senzu.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.senzu.PacketSenzuThrow;
import net.shurui.shuruisutilities.senzu.SenzuThrow;

/**
 * The client half of throwing a senzu: spot the sneak-right-click on a bean while a player is locked on, and tell
 * the server who is being aimed at.
 *
 * <p>The lock-on is the reason this cannot live on the server. DragonMineZ tracks it in
 * {@code LockOnEvent.lockedTarget}, a client-side static that is never synced, so the aim has to be sent.</p>
 *
 * <p>Cancelling here is deliberately NOT what stops the bean being eaten. In 1.20.1
 * {@code MultiPlayerGameMode#useItem} still sends the use packet when this event is cancelled, so the server would
 * eat the bean regardless; the cancel only suppresses the client's own local prediction (and gives the arm swing,
 * via the SUCCESS result). {@link SenzuThrow} cancels the same event on the SERVER, which is the half that counts.</p>
 *
 * <p>Client-only, and the whole class is {@link Dist#CLIENT} so a dedicated server never classloads it or the
 * DragonMineZ client class it reaches for.</p>
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class SenzuThrowClientEvents
{
    private SenzuThrowClientEvents() {}

    // HIGH, like the server half: DragonMineZ's own right click handler starts using (eating) its senzu at NORMAL
    // priority and ignores cancellation, so the client prediction has to be stopped before it runs.
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGH)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event)
    {
        if (!event.getLevel().isClientSide)
            return;
        Player player = event.getEntity();
        if (player != Minecraft.getInstance().player || !player.isShiftKeyDown())
            return;
        if (!SenzuThrow.isThrowable(event.getItemStack()))
            return;

        LivingEntity locked = lockedTarget();
        if (!(locked instanceof Player target) || !target.isAlive() || target == player)
            return;

        NetworkUtils.sendToServer(new PacketSenzuThrow(target.getId()));
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /**
     * DragonMineZ's current lock-on target, or null.
     *
     * <p>DMZ is a mandatory dependency, so this is a direct call rather than reflection, but it is still wrapped:
     * the reference resolves lazily on first call, so a DMZ build that renamed or moved {@code LockOnEvent} throws
     * {@link NoClassDefFoundError} here rather than at class load. Catching {@link Throwable} degrades that to "no
     * lock-on", which costs a player one thrown bean instead of crashing their client mid-fight. Same contract as
     * every other DMZ touch in this addon.</p>
     */
    private static LivingEntity lockedTarget()
    {
        try
        {
            return com.dragonminez.client.events.LockOnEvent.getLockedTarget();
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }
}
