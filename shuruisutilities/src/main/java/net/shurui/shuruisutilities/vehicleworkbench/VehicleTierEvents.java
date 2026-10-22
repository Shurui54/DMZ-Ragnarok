package net.shurui.shuruisutilities.vehicleworkbench;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.dragonminez.common.init.entities.vehicle.VehicleEntity;
import com.dragonminez.common.init.item.entities.VehicleItem;

import net.shurui.shuruisutilities.hoverbike.HoverbikeEntity;

/**
 * Carries a DMZ vehicle's tier across DMZ's own item to entity round trip, which builds fresh objects both ways.
 *
 * <ul>
 *   <li><b>Placing</b>: {@code VehicleItem.useOn} spawns the vehicle inside the right-click. The right-click event
 *       remembers the held tier; the vehicle's join event, fired synchronously by that same spawn, takes it.</li>
 *   <li><b>Packing up</b>: a player hit makes {@code VehicleEntity.hurt} drop a NEW item and then remove the vehicle.
 *       The attack event remembers which vehicle was hit; the drop's join event (before the removal) stamps its
 *       tier onto the item.</li>
 * </ul>
 * Both memories live for one server tick at most, so a refused placement or a missed drop cannot leak a tier onto a
 * later, unrelated spawn. Everything is Throwable guarded: a DMZ change must lose the tier, never crash a spawn.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class VehicleTierEvents
{
    private VehicleTierEvents() {}

    private static int pendingPlaceTier;
    private static VehicleEntity pendingPackUp;

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event)
    {
        if (event.getLevel().isClientSide() || event.isCanceled())
            return;
        try
        {
            ItemStack held = event.getItemStack();
            if (held.getItem() instanceof VehicleItem && VehicleTiers.isTiered(held))
                pendingPlaceTier = VehicleTiers.tier(held);
        }
        catch (Throwable ignored)
        {
        }
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event)
    {
        if (event.getEntity().level().isClientSide())
            return;
        try
        {
            if (event.getTarget() instanceof VehicleEntity v && VehicleTiers.entityTier(v) > 1)
                pendingPackUp = v;
        }
        catch (Throwable ignored)
        {
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onJoin(EntityJoinLevelEvent event)
    {
        if (!(event.getLevel() instanceof ServerLevel) || event.isCanceled())
            return;
        try
        {
            Entity e = event.getEntity();
            if (e instanceof VehicleEntity v)
            {
                if (!event.loadedFromDisk() && pendingPlaceTier > 1)
                {
                    int tier = Math.min(pendingPlaceTier, VehicleTiers.maxTier());
                    pendingPlaceTier = 0;
                    VehicleTiers.applyToEntity(v, tier);
                    v.setCustomName(v.getPickResult().getHoverName().copy().append(" ").append(VehicleTiers.label(tier)));
                }
                else if (event.loadedFromDisk() && VehicleTiers.entityTier(v) > 1)
                {
                    // re-derive from the current config, so an operator's retune reaches vehicles already out
                    VehicleTiers.applyToEntity(v, VehicleTiers.entityTier(v));
                }
            }
            else if (e instanceof ItemEntity drop && pendingPackUp != null)
            {
                VehicleEntity v = pendingPackUp;
                ItemStack stack = drop.getItem();
                if (v.isAlive() && drop.distanceToSqr(v) < 16.0D && stack.getItem() == v.getPickResult().getItem())
                {
                    pendingPackUp = null;
                    ItemStack tiered = VehicleTiers.withTier(stack.copy(), VehicleTiers.entityTier(v));
                    drop.setItem(tiered);
                }
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase == TickEvent.Phase.END)
        {
            pendingPlaceTier = 0;
            pendingPackUp = null;
        }
    }

    /** Tell the rider which tier they just boarded: the tier is not visible on the models themselves. */
    @SubscribeEvent
    public static void onMount(EntityMountEvent event)
    {
        if (!event.isMounting() || !(event.getEntityMounting() instanceof ServerPlayer sp))
            return;
        try
        {
            Entity vehicle = event.getEntityBeingMounted();
            int tier = 1;
            if (vehicle instanceof HoverbikeEntity bike)
                tier = bike.getTier();
            else if (vehicle instanceof VehicleEntity v)
                tier = VehicleTiers.entityTier(v);
            if (tier > 1)
                sp.displayClientMessage(Component.empty().append(vehicle.getPickResult().getHoverName()).append(" ")
                        .append(VehicleTiers.label(tier)), true);
        }
        catch (Throwable ignored)
        {
        }
    }

}
