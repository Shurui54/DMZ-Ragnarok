package net.shurui.shuruisutilities.audit;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The two hooks that answer "who got into someone else's things".
 *
 * <p>Both are deliberately narrow. Logging EVERY container open would bury the answer under every furnace and
 * chest on the server, which is the same mistake as logging nothing: what is wanted is the handful of actions
 * that can take another player's items.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class AuditEvents
{
    private AuditEvents() {}

    /**
     * Portable storage opened.
     *
     * <p>Matched on the menu's class NAME, never by importing the class. Sophisticated Backpacks is not a
     * declared dependency, and the compat rule is that an optional mod's classes are never loaded outside a
     * {@code ModList} guard; reading the name off an object we were already handed loads nothing.
     *
     * <p>Fires because {@code NetworkHooks.openScreen} posts this event, which is how every modded menu is
     * opened, so this catches a backpack whoever opened it and however they got to it.
     */
    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        AbstractContainerMenu menu = event.getContainer();
        if (menu == null)
            return;

        String type = menu.getClass().getName();
        String lower = type.toLowerCase(Locale.ROOT);
        if (!lower.contains("sophisticatedbackpacks") && !lower.contains("sophisticatedcore"))
            return;

        AuditLog.log("{} opened portable storage ({}) at {}", AuditLog.name(player),
                type.substring(type.lastIndexOf('.') + 1), AuditLog.where(player));
    }

    /**
     * One player right-clicking another.
     *
     * <p>This is the theft vector worth naming: a worn backpack is opened by interacting with the player wearing
     * it, so this line names BOTH sides, which the container open above cannot. It logs the attempt rather than
     * the outcome on purpose, because an attempt that was refused is exactly as interesting to a moderator.
     */
    @SubscribeEvent
    public static void onInteractPlayer(PlayerInteractEvent.EntityInteract event)
    {
        if (event.getLevel().isClientSide())
            return;
        if (!(event.getTarget() instanceof Player target))
            return;
        Player actor = event.getEntity();
        if (actor == null || actor == target)
            return;
        if (!recent(actor, target))
            return;

        AuditLog.log("{} right-clicked {} holding {} at {}", AuditLog.name(actor), AuditLog.name(target),
                AuditLog.describe(actor.getItemInHand(event.getHand())), AuditLog.where(target));
    }

    /**
     * One line per pair per {@value #REPEAT_MS} ms.
     *
     * <p>Right-clicking is held down, mis-clicked and repeated, and an audit trail nobody can read is no better
     * than none. There is a grave-marker warning on the live server that repeats every five seconds all night;
     * this is that mistake, not made again.
     */
    private static final long REPEAT_MS = 10_000L;

    private static final Map<String, Long> LAST = new HashMap<>();

    private static boolean recent(Player actor, Player target)
    {
        String key = actor.getUUID() + ">" + target.getUUID();
        long now = System.currentTimeMillis();
        Long last = LAST.get(key);
        if (last != null && now - last < REPEAT_MS)
            return false;
        // the map only ever holds pairs who interacted, and is dropped with the server, so it cannot grow
        // without bound in any way that matters; still, forget entries nobody has touched in a while.
        if (LAST.size() > 512)
            LAST.entrySet().removeIf(e -> now - e.getValue() > REPEAT_MS * 6);
        LAST.put(key, now);
        return true;
    }
}
