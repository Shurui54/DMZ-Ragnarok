package net.shurui.shuruisutilities.god;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.energy.EnergyKind;
import net.shurui.shuruisutilities.energy.EnergyManager;

/**
 * The Angel's staff as a SUMMONED KI WEAPON rather than an item you carry.
 *
 * <h2>What changed and why it is simpler</h2>
 * It was originally a soulbound item that lived in the inventory, which meant guarding every way an item can leave a
 * player: dropping, dying, and being stashed in a container. As a ki weapon none of that is a storage problem, it is
 * a LIFETIME problem: the staff exists only while the Angel is holding it out, and is unmade the moment they put it
 * away, die, log out, or stop being the Angel. Nothing to lose, nothing to lock down, nothing to find in a chest.
 *
 * <p>It is still backed by an ItemStack, deliberately: that is what lets it render as the 3D model, swing with DMZ's
 * staff animations and act as a weapon at all. The difference is that no stack is ever issued unasked and none is
 * allowed to persist, so it behaves like ki rather than like loot.
 *
 * <p>Core keeps the HYGIENE only (the sweep, drops, tosses, respawn, logout and the container rule), so a staff never
 * persists with or without the Ragnarok Key. Summoning and putting it away are the key's (feature {@code roles},
 * {@code AngelStaffSummon}); keyless nobody holds the Angel title's access, so the sweep unmakes any stray staff.
 *
 * <p>Registered by hand on the Forge bus from the mod's main class.
 */
public final class AngelStaffGuard
{
    /** How often the sweep checks that no stray staff exists, in ticks. */
    private static final int SWEEP_INTERVAL_TICKS = 20;

    private int tickCounter;

    /** Remove every staff stack from this player. @return true when anything was removed. */
    public static boolean stripStaff(ServerPlayer player)
    {
        boolean removed = false;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++)
            if (AngelStaffItem.isStaff(inv.getItem(i)))
            {
                inv.setItem(i, ItemStack.EMPTY);
                removed = true;
            }
        return removed;
    }

    /**
     * Unmakes any staff held by someone who is no longer the Angel.
     *
     * <p>Polled rather than hooked to a title-change event because a title can also lapse through a config change,
     * an admin edit or a reset, none of which are events. Deliberately does NOT hand the staff out: summoning is
     * always a deliberate act, so an Angel who has not summoned it simply does not have one.
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        if (++tickCounter < SWEEP_INTERVAL_TICKS)
            return;
        tickCounter = 0;

        MinecraftServer server = event.getServer();
        if (server == null)
            return;
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (!EnergyManager.hasAccess(player, EnergyKind.ANGELIC))
                stripStaff(player);
    }

    /** Dies with its wielder: a ki weapon leaves no corpse to loot. */
    @SubscribeEvent
    public void onDrops(LivingDropsEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer))
            return;
        event.getDrops().removeIf(entity -> AngelStaffItem.isStaff(entity.getItem()));
    }

    /** Cannot be thrown on the ground; the throw unmakes it instead. */
    @SubscribeEvent
    public void onToss(ItemTossEvent event)
    {
        ItemEntity entity = event.getEntity();
        if (entity == null || !AngelStaffItem.isStaff(entity.getItem()))
            return;
        event.setCanceled(true);
        entity.discard();
        if (event.getPlayer() instanceof ServerPlayer player)
            player.displayClientMessage(Component.literal("The staff fades."), true);
    }

    /** Never carried through a respawn: it has to be summoned again. */
    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
            stripStaff(player);
    }

    /** Gone on logout, so it is never sitting in a saved inventory. */
    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
            stripStaff(player);
    }

    /**
     * The staff may not be put anywhere except the wielder's own inventory.
     *
     * <p>Kept from the item version, and now a stronger statement: a summoned ki weapon should never end up in ANY
     * world container, so the check is simply "is this slot the player's own". Called from the container mixin.
     */
    public static boolean mayPlaceIn(ItemStack stack, Slot slot, ServerPlayer player)
    {
        if (!AngelStaffItem.isStaff(stack))
            return true;
        if (slot == null || player == null)
            return false;
        return slot.container == player.getInventory();
    }
}
