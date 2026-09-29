package net.shurui.shuruisutilities.deletionwand;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.compat.dmz.DeletionWandMasterGuard;
import net.shurui.shuruisutilities.compat.dmz.DeletionWandMasterGuard.MasterInfo;
import net.shurui.shuruisutilities.hologram.Hologram;
import net.shurui.shuruisutilities.hologram.HologramManager;
import net.shurui.shuruisutilities.util.ServerUtil;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Forge-bus handler for {@link DeletionWandItem}. Server-side, gated behind su.admin.deletionwand.
 * <ul>
 *   <li>AttackEntityEvent (HIGHEST): left-click deletes the hit entity via discard(), never a {@link Player}.
 *       DMZ masters are reported (and warned if DMZ respawns them) through the compat guard. Keys purely off
 *       the held item and does NOT early-return when SU Protection already cancelled the event (both fire at
 *       HIGHEST; registration order not relied upon).</li>
 *   <li>RightClickItem: ray-cast the look and durable-delete the nearest SU hologram in sight.</li>
 * </ul>
 */
public final class DeletionWandHandler
{
    public static final String PERM = "su.admin.deletionwand";

    private static final double HOLOGRAM_LOOK_RANGE = 6.0D; // right-click look range, blocks
    // max perpendicular distance from the look ray for a hologram anchor to count as "in sight"
    private static final double HOLOGRAM_RAY_TOLERANCE = 1.5D;

    private static boolean isWand(ItemStack stack)
    {
        return !stack.isEmpty() && stack.getItem() == DeletionWandItems.DELETION_WAND.get();
    }

    private static boolean checkPerm(ServerPlayer player)
    {
        return APIRegistry.perms.checkPermission(player, PERM);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAttackEntity(AttackEntityEvent event)
    {
        // NOTE: deliberately do NOT check event.isCanceled() here: SU Protection also runs at HIGHEST and may
        // have cancelled this. Key entirely off the held item and drive our own delete regardless.
        Player attacker = event.getEntity();
        if (attacker == null || attacker.level().isClientSide() || !(attacker instanceof ServerPlayer player))
            return;
        if (!isWand(player.getMainHandItem()))
            return;

        Entity target = event.getTarget();
        if (target == null)
            return;

        // Permission gate: on failure, deny and swallow the hit so nothing gets damaged.
        if (!checkPerm(player))
        {
            ChatOutputHandler.chatError(player, "You do not have permission to use the Deletion Wand.");
            event.setCanceled(true);
            return;
        }

        // Never delete a player.
        if (target instanceof Player)
        {
            ChatOutputHandler.chatError(player, "The Deletion Wand cannot delete players.");
            event.setCanceled(true);
            return;
        }

        MasterInfo master = DeletionWandMasterGuard.inspect(target);
        String label = describe(target, master);

        // discard() bypasses DMZ master damage-immunity and cleanly removes any other entity.
        target.discard();

        if (master.isMaster())
        {
            if (master.respawning())
                player.sendSystemMessage(Component.literal("Deleted DMZ master: " + label)
                        .withStyle(ChatFormatting.GREEN)
                        .append(Component.literal(" (DMZ will respawn it on server restart/reload)")
                                .withStyle(ChatFormatting.YELLOW)));
            else
                ChatOutputHandler.chatConfirmation(player, "Deleted DMZ master: " + label);
        }
        else
        {
            ChatOutputHandler.chatConfirmation(player, "Deleted entity: " + label);
        }

        event.setCanceled(true);
    }

    private static String describe(Entity target, MasterInfo master)
    {
        if (master.isMaster() && master.label() != null)
            return master.label();
        Component name = target.getCustomName();
        if (name != null)
            return name.getString();
        return target.getType().getDescription().getString();
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event)
    {
        if (event.getHand() != InteractionHand.MAIN_HAND)
            return;
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide())
            return;
        if (!isWand(event.getItemStack()))
            return;

        // Consume the interaction regardless of outcome so the wand never does a vanilla item use.
        event.setCanceled(true);

        if (!checkPerm(player))
        {
            ChatOutputHandler.chatError(player, "You do not have permission to use the Deletion Wand.");
            return;
        }

        // The holograms live in the Ragnarok Key (S14); without it there are none to delete.
        if (!net.shurui.shuruisutilities.api.key.HologramHooks.available())
        {
            ChatOutputHandler.chatError(player, "The Hologram module is not available.");
            return;
        }

        String hit = findHologramInSight(player);
        if (hit != null)
        {
            HologramManager.instance().delete(hit);
            ChatOutputHandler.chatConfirmation(player, "Deleted hologram: " + hit);
        }
        else
        {
            // Subtle actionbar note when nothing is targeted.
            player.displayClientMessage(
                    Component.literal("No hologram in sight").withStyle(ChatFormatting.GRAY), true);
        }
    }

    // cast the look ray (up to HOLOGRAM_LOOK_RANGE) and return the closest hologram in the player's dimension
    // whose anchor lies within HOLOGRAM_RAY_TOLERANCE of it; null if none qualify
    private static String findHologramInSight(ServerPlayer player)
    {
        HologramManager manager = HologramManager.instance();
        Vec3 eye = player.getEyePosition();
        Vec3 dir = player.getLookAngle().normalize();

        String best = null;
        double bestAlong = Double.MAX_VALUE;

        for (String name : manager.getNames())
        {
            Hologram holo = manager.get(name);
            if (holo == null)
                continue;
            // Same-dimension check: resolve the hologram's dimension level and compare to the player's.
            ServerLevel holoLevel = ServerUtil.getWorldFromString(holo.dim);
            if (holoLevel == null || holoLevel != player.level())
                continue;

            Vec3 anchor = new Vec3(holo.x, holo.y, holo.z);
            Vec3 toAnchor = anchor.subtract(eye);
            double along = toAnchor.dot(dir); // projection distance along the look ray
            if (along < 0.0D || along > HOLOGRAM_LOOK_RANGE)
                continue; // behind the player or out of range

            Vec3 closest = eye.add(dir.scale(along));
            double perp = anchor.distanceTo(closest); // point-to-line (segment) distance
            if (perp > HOLOGRAM_RAY_TOLERANCE)
                continue;

            if (along < bestAlong)
            {
                bestAlong = along;
                best = name;
            }
        }
        return best;
    }
}
