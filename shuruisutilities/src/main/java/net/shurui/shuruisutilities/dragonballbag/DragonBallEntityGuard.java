package net.shurui.shuruisutilities.dragonballbag;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The entity half of the dragon ball whitelist. Only two kinds of entity may hold a dragon ball or the dragon ball bag:
 * a PLAYER (their inventory, bag and Curios slot, governed by the container hooks) and a dropped ITEM ENTITY lying on
 * the ground. Every other entity, vanilla or modded, is refused, including ones nobody has listed:
 *
 * <ul>
 *   <li>Up front: a right click on an item frame, armor stand or allay while holding a ball or the bag is cancelled,
 *       so nothing moves at all.</li>
 *   <li>After any other entity interaction made while holding one: next tick the target is checked, and anything
 *       confined it now holds (hands, armor, item frame, chest minecart / boat, a modded entity's item handler) goes
 *       straight back to the player.</li>
 *   <li>On load: any non-player entity that joins a level holding one (chunk load, spawn, dimension change) has it
 *       taken out and dropped beside it, where a player can pick it up.</li>
 *   <li>Mobs cannot pick a dropped ball up ({@code MixinMobPickupConfined}); a dropped ball or bag is tagged
 *       {@code PreventRemoteMovement}, the convention magnets and item vacuums honour.</li>
 * </ul>
 * The item rule itself is {@link DragonBallConfine}.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class DragonBallEntityGuard
{
    private DragonBallEntityGuard()
    {
    }

    /** The whitelist: the only entities allowed to hold a ball or the bag. */
    private static boolean mayHoldConfined(Entity e)
    {
        return e instanceof Player || e instanceof ItemEntity;
    }

    // Entities that take an item straight from the hand on a right click: refused before anything moves.
    private static boolean takesFromHand(Entity e)
    {
        return e instanceof ItemFrame || e instanceof ArmorStand || e instanceof Allay;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteract(PlayerInteractEvent.EntityInteract event)
    {
        guard(event, event.getTarget());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteractAt(PlayerInteractEvent.EntityInteractSpecific event)
    {
        guard(event, event.getTarget());
    }

    private static void guard(PlayerInteractEvent event, Entity target)
    {
        if (target == null || mayHoldConfined(target) || !DragonBallConfine.isConfined(event.getItemStack()))
            return;
        if (takesFromHand(target))
        {
            event.setCanceled(true);
            if (event instanceof PlayerInteractEvent.EntityInteract ei)
                ei.setCancellationResult(InteractionResult.FAIL);
            if (event instanceof PlayerInteractEvent.EntityInteractSpecific es)
                es.setCancellationResult(InteractionResult.FAIL);
            return;
        }
        // Any other entity: let the interaction run (riding, talking to an NPC), then take back anything it kept.
        if (event.getEntity() instanceof ServerPlayer player && target.level() instanceof ServerLevel level)
            level.getServer().execute(() -> returnConfined(level, target, player));
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event)
    {
        Entity entity = event.getEntity();
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        if (entity instanceof ItemEntity item)
        {
            if (DragonBallConfine.isConfined(item.getItem()))
                item.getPersistentData().putBoolean("PreventRemoteMovement", true);
            return;
        }
        if (mayHoldConfined(entity) || !holdsAny(entity))
            return;
        // Defer one tick: spawning the dropped item while the entity is still being added to the level is not safe.
        level.getServer().execute(() -> returnConfined(level, entity, null));
    }

    /** Back-compat entry used elsewhere: empty the entity of balls and bags, dropping them beside it. */
    public static int popConfined(ServerLevel level, Entity entity)
    {
        return returnConfined(level, entity, null);
    }

    // Cheap read-only test, run synchronously on join so the deferred task is only queued when something is found.
    private static boolean holdsAny(Entity e)
    {
        try
        {
            if (e instanceof ItemFrame frame)
                return DragonBallConfine.isConfined(frame.getItem());
            if (e instanceof LivingEntity living)
                for (EquipmentSlot slot : EquipmentSlot.values())
                    if (DragonBallConfine.isConfined(living.getItemBySlot(slot)))
                        return true;
            if (e instanceof ContainerEntity ce && ce.getLootTable() == null && e instanceof Container c)
                for (int i = 0; i < c.getContainerSize(); i++)
                    if (DragonBallConfine.isConfined(c.getItem(i)))
                        return true;
            IItemHandler h = e.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
            if (h != null)
                for (int i = 0; i < h.getSlots(); i++)
                    if (DragonBallConfine.isConfined(h.getStackInSlot(i)))
                        return true;
        }
        catch (Throwable ignored)
        {
        }
        return false;
    }

    /**
     * Take every ball / bag out of a non-whitelisted entity. Given to {@code owner} when set (dropped at their feet if
     * full), otherwise dropped beside the entity. Returns how many stacks moved. A move, never a copy.
     */
    private static int returnConfined(ServerLevel level, Entity entity, ServerPlayer owner)
    {
        int moved = 0;
        try
        {
            if (entity.isRemoved() || mayHoldConfined(entity))
                return 0;
            if (entity instanceof ItemFrame frame && DragonBallConfine.isConfined(frame.getItem()))
            {
                ItemStack held = frame.getItem().copy();
                frame.setItem(ItemStack.EMPTY);
                hand(entity, owner, held);
                moved++;
            }
            if (entity instanceof LivingEntity living)
            {
                for (EquipmentSlot slot : EquipmentSlot.values())
                {
                    ItemStack held = living.getItemBySlot(slot);
                    if (DragonBallConfine.isConfined(held))
                    {
                        living.setItemSlot(slot, ItemStack.EMPTY);
                        hand(entity, owner, held.copy());
                        moved++;
                    }
                }
            }
            if (entity instanceof ContainerEntity ce && ce.getLootTable() == null && entity instanceof Container c)
            {
                for (int i = 0; i < c.getContainerSize(); i++)
                {
                    if (DragonBallConfine.isConfined(c.getItem(i)))
                    {
                        hand(entity, owner, c.removeItemNoUpdate(i));
                        moved++;
                    }
                }
            }
            IItemHandler h = entity.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
            if (h != null)
            {
                for (int i = 0; i < h.getSlots(); i++)
                {
                    ItemStack in = h.getStackInSlot(i);
                    if (!DragonBallConfine.isConfined(in))
                        continue;
                    ItemStack out = h.extractItem(i, in.getCount(), false);
                    if (!out.isEmpty())
                    {
                        hand(entity, owner, out);
                        moved++;
                    }
                }
            }
            if (moved > 0)
                LoggingHandler.sulog.info("[dragonball] Took {} dragon ball / bag stack(s) out of a {} at {} {}",
                        moved, entity.getType().toShortString(), level.dimension().location(),
                        entity.blockPosition().toShortString());
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[dragonball] Could not empty a {} of dragon balls: {}",
                    entity.getType().toShortString(), t.toString());
        }
        return moved;
    }

    private static void hand(Entity from, ServerPlayer owner, ItemStack stack)
    {
        if (stack.isEmpty())
            return;
        if (owner != null && !owner.isRemoved())
        {
            if (!owner.getInventory().add(stack))
                owner.drop(stack, false);
            return;
        }
        from.spawnAtLocation(stack);
    }
}
