package net.shurui.shuruisutilities.senzu;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * A single-click consumable bean. Each instance carries the effect it applies on use; the effect is driven by
 * DragonMineZ's stat pools through {@link SenzuEffects}, so the item itself stays a thin wrapper. Golden beans are NOT
 * BeanItems: they are passive death-totems (see {@link SenzuModule}) and are registered as plain items.
 *
 * <p>Beans consume on a SINGLE right click, INSTANTLY, the way DragonMineZ's own senzu bean does. There is no
 * hold-to-eat: it was tried and it is wrong for this item, because a senzu is swallowed in the middle of a fight and a
 * bean you have to stand still for is a bean you cannot use when you need it. So the item carries NO food properties,
 * no use duration and no eat animation, and it never touches the hunger bar.
 *
 * <p>DMZ's own senzu sound plays on the click. It has to be played by hand: with no use duration nothing in vanilla
 * ever reaches for an eating sound, since {@code triggerItemUseEffects} is the only thing that would.
 *
 * <p>The stat work runs server-side in {@link #use}, the stack shrinks there, and the reuse gate is vanilla
 * {@link net.minecraft.world.item.ItemCooldowns}, which both blocks a too-soon re-use and draws the hotbar sweep
 * overlay for free.
 */
public class BeanItem extends Item
{
    // what using this bean does. Fraction meaning depends on the kind: for the heal kinds it is the share of the
    // maximum the pool is raised UP to; for DRAIN it is the share the pool is forced DOWN to; POISON ignores it.
    public enum Kind
    {
        FULL,
        HEALTH,
        ENERGY,
        STAMINA,
        POISON,
        DRAIN
    }

    private final Kind kind;
    private final float fraction;
    // only meaningful for FULL: whether to also clear DMZ combat locks (true for the senzu bean, false for the cracked).
    private final boolean clearLocks;

    public BeanItem(Kind kind, float fraction, boolean clearLocks)
    {
        // no food properties on purpose: a bean is not edible and must have no eat animation and no hunger interaction.
        // A default stack of 64 is fine (nothing else here needs a custom Properties knob).
        super(new Properties());
        this.kind = kind;
        this.fraction = fraction;
        this.clearLocks = clearLocks;
    }

    /** DragonMineZ's own senzu sound, {@code dragonminez:senzu}. */
    private static final ResourceLocation DMZ_SENZU_SOUND = new ResourceLocation("dragonminez", "senzu");

    /**
     * DMZ's senzu sound, resolved from the registry rather than imported, so this file holds no DragonMineZ reference
     * and a pack without that sound falls back to the vanilla eat instead of throwing.
     *
     * <p>Played by hand from {@link #use}, because nothing plays it for us: a bean has no use duration and no eat
     * animation, and {@code triggerItemUseEffects} is the only thing that would otherwise reach for it.
     */
    private static SoundEvent senzuSound()
    {
        SoundEvent dmz = ForgeRegistries.SOUND_EVENTS.getValue(DMZ_SENZU_SOUND);
        return dmz != null ? dmz : SoundEvents.GENERIC_EAT;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand)
    {
        ItemStack stack = player.getItemInHand(hand);

        // belt and braces: vanilla already refuses to call use() for an item on cooldown (both ServerPlayerGameMode
        // #useItem and the client MultiPlayerGameMode#useItem bail on isOnCooldown first), but we re-check so a future
        // caller that invokes use() directly still cannot consume a bean mid-cooldown. fail() means "nothing happened",
        // so no stack is spent.
        if (player.getCooldowns().isOnCooldown(stack.getItem()))
        {
            return InteractionResultHolder.fail(stack);
        }

        // The sound, and nothing else that would delay it. Broadcast rather than sent to the eater, so people nearby
        // hear a bean go down the way they hear DMZ's own.
        if (!level.isClientSide)
        {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), senzuSound(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
        }

        // apply the stat effect server-side only: the switch mutates DMZ pools and vanilla health, which must never run
        // on the client.
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer)
        {
            switch (kind)
            {
                case FULL -> SenzuEffects.healFull(serverPlayer, fraction, clearLocks);
                case HEALTH -> SenzuEffects.healHealth(serverPlayer, fraction);
                case ENERGY -> SenzuEffects.healEnergy(serverPlayer, fraction);
                case STAMINA -> SenzuEffects.healStamina(serverPlayer, fraction);
                case DRAIN -> SenzuEffects.drain(serverPlayer, fraction);
                case POISON -> SenzuEffects.applyBurnt(serverPlayer);
            }

            // shrink after the effect ran; creative players keep the bean. Server-side only: the client never owns the
            // authoritative stack count (its copy is reconciled by the server after the click).
            if (!player.getAbilities().instabuild)
            {
                stack.shrink(1);
            }
        }

        // stamp the SHARED cooldown across every bean, on BOTH sides. ItemCooldowns is a client-and-server structure:
        // the client copy is what blocks the next use() and paints the hotbar sweep overlay, the server copy is the
        // authoritative gate, so vanilla items (ender pearl, chorus fruit) always addCooldown on both. It is shared
        // across all beans on purpose so a player cannot chain one of every bean type back to back; SenzuRegistry stamps
        // every registered BeanItem so the overlay shows on all of them, not just the one clicked.
        SenzuRegistry.applyBeanCooldown(player, SenzuModule.cooldownTicks());

        // sidedSuccess gives the arm-swing feedback on the client (SUCCESS there, CONSUME on the server), exactly like
        // the ender pearl.
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
