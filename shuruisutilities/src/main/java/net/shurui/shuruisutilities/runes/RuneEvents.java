package net.shurui.shuruisutilities.runes;

import java.util.List;
import java.util.Random;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.event.entity.player.PlayerDestroyItemEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The points where rune data attaches to armour and comes back off it.
 *
 * <p>Rolling happens on PICKUP and on EQUIP rather than only at crafting. Armour arrives from loot, trades, spawner
 * drops and other mods as much as from a bench, and the artist's note asks for a tier "when its first picked up", so
 * hooking only the crafting table would leave most armour in the game blank. {@link ArmorRunes#ensureRolled} is a
 * no-op on a piece that already has data, so a piece rolls exactly once however it arrives.
 *
 * <p>A CREATIVE player is the one exception: staff stock shops, crates and loot in creative, so a piece rolled the
 * moment they touch it could never be placed unrolled. Armour handled in creative stays blank and rolls normally for
 * the survival player who eventually receives it.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class RuneEvents
{
    private RuneEvents() {}

    private static final Random RNG = new Random();

    private static boolean isArmor(ItemStack stack)
    {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof ArmorItem;
    }

    @SubscribeEvent
    public static void onPickup(PlayerEvent.ItemPickupEvent event)
    {
        Player player = event.getEntity();
        ItemStack stack = event.getStack();
        // Skip a creative pickup: a staff member stocking shops and crates needs the piece to stay unrolled.
        if (player != null && !player.level().isClientSide && !player.isCreative() && isArmor(stack))
            ArmorRunes.ensureRolled(stack, RNG);
    }

    /** Belt and braces for armour that was never picked up as an item, e.g. given straight into a slot. */
    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event)
    {
        if (event.getEntity() == null || event.getEntity().level().isClientSide)
            return;
        ItemStack stack = event.getTo();
        // Only a creative PLAYER is spared the roll, for the same shop stocking reason as pickup. A non-player
        // wearer (an armour stand, a mob handed armour) has no creative flag and keeps rolling exactly as before,
        // so mob armour that rolled yesterday still rolls today.
        boolean creativeHandler = event.getEntity() instanceof Player p && p.isCreative();
        if (isArmor(stack) && !creativeHandler)
            ArmorRunes.ensureRolled(stack, RNG);
        // Recompute on ANY equipment change, including taking a piece off: the bonus is the sum of what is worn,
        // so removing armour has to shrink it just as putting armour on grows it.
        if (event.getEntity() instanceof ServerPlayer player
                && event.getSlot() != null && event.getSlot().getType() == net.minecraft.world.entity.EquipmentSlot.Type.ARMOR)
            RuneStatApplier.refresh(player);
    }

    /** Re-apply on join: BonusStats persist, but the worn set is the authority, so it is recomputed from scratch. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
            RuneStatApplier.refresh(player);
    }

    /**
     * When a piece breaks, hand back one DORMANT rune per rune that was socketed in it.
     *
     * <p>Dormant rather than the runes themselves: the socketed rune's roll is spent shaping that piece, so returning
     * the exact rune would make breaking armour a way to duplicate a good roll. The material comes back, the roll
     * does not.
     */
    @SubscribeEvent
    public static void onDestroyed(PlayerDestroyItemEvent event)
    {
        ItemStack broken = event.getOriginal();
        if (!(event.getEntity() instanceof ServerPlayer player) || !isArmor(broken))
            return;
        List<RuneStat> socketed = ArmorRunes.socketStats(broken);
        if (socketed.isEmpty())
            return;
        ItemStack refund = new ItemStack(RuneItems.DORMANT.get(), socketed.size());
        giveOrDrop(player, refund);
    }

    private static void giveOrDrop(Player player, ItemStack stack)
    {
        player.getInventory().add(stack); // mutates stack down to whatever did not fit
        if (!stack.isEmpty())
            player.drop(stack, false);
    }

    /** Dropping armour must not strip its runes; nothing to do, but the hook documents the intent. */
    @SubscribeEvent
    public static void onToss(ItemTossEvent event)
    {
        // no-op: rune data lives on the stack's NBT, so it travels with the item automatically
    }
}
