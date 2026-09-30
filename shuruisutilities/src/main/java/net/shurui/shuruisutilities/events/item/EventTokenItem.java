package net.shurui.shuruisutilities.events.item;

import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * The single reserve event-collectible item, {@code dmz_ragnarok:event_token}. One registered item serves every
 * event: its LOOK is driven per event by NBT ({@code Variant} plus vanilla {@code CustomModelData}), so a themed
 * token (Halloween candy, and future themes) needs no new registry entry and therefore no client update. The item
 * always exists in core on every key tier (registration must not vary by key); whether it is granted, shown or
 * used is gated elsewhere (ContentGate server-side, ClientGate.feature("events") in the creative tab).
 *
 * <p>The {@code Variant} tag picks the hover name, the model (through the {@code dmz_ragnarok:variant} item
 * property, registered client side, so no CustomModelData is needed; a legacy CMD override is kept), the tooltip
 * description line, and per-variant BEHAVIOUR. Reading NBT keeps this data-safe: a token with no {@code Variant} is
 * a valid inedible generic token, and an unknown variant falls back to the base name.
 *
 * <p>Behaviour, all core (public on every server; hidden keyless by {@code PrivateItems}):
 * <ul>
 *   <li>The Halloween candy variants ({@code candy}, {@code candy_corn}, {@code chocolate_dabura}) are EDIBLE. Their
 *       food is DragonMineZ's cooked dino tail exactly: nutrition 12, saturation modifier 9.6, meat, always edible,
 *       normal 32-tick eat animation, no effects. Eating spends one, which is intended (the candy IS the currency).
 *       Food is applied per stack (not the base {@link Item#isEdible()} field), so a generic token stays inedible.</li>
 *   <li>{@code chocolate_dabura_wrapped} is NOT edible: a right click UNWRAPS it into one {@code chocolate_dabura}
 *       (a rustle sound, one wrapped consumed), and the unwrapped one is edible.</li>
 * </ul>
 */
public class EventTokenItem extends Item
{
    /** NBT string tag naming the event theme this token belongs to (drives the hover name, model, tooltip, behaviour). */
    public static final String VARIANT_TAG = "Variant";

    // ---- the shipped variants ----------------------------------------------------------------------------
    // The Halloween currency is the token whose event variant is "halloween" (the 2026 event stamps that, with
    // displayName "Candy"); "candy" is accepted as an alias so a config that names candy directly also works. Both
    // read and eat as Candy, so existing candy stacks (Variant "halloween") keep working and now render candy.
    public static final String HALLOWEEN = "halloween";
    public static final String CANDY = "candy";
    public static final String CANDY_CORN = "candy_corn";
    public static final String CHOCOLATE_DABURA = "chocolate_dabura";
    public static final String CHOCOLATE_DABURA_WRAPPED = "chocolate_dabura_wrapped";

    /**
     * DragonMineZ's cooked dino tail food, copied verbatim from {@code MainItems.DINO_TAIL_COOKED} = {@code new
     * FoodItem(12, 9.6f, 64)}, whose {@code FoodItem} builds {@code nutrition(12).saturationMod(9.6f).meat()
     * .alwaysEat()}. No effects, no fast flag, so the normal 32-tick eat applies.
     */
    private static final FoodProperties CANDY_FOOD = new FoodProperties.Builder()
            .nutrition(12)
            .saturationMod(9.6F)
            .meat()
            .alwaysEat()
            .build();

    public EventTokenItem(Properties properties)
    {
        super(properties);
    }

    /** The variant string on a stack, or empty if none. */
    public static String variantOf(ItemStack stack)
    {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(VARIANT_TAG) ? tag.getString(VARIANT_TAG) : "";
    }

    /**
     * The float the {@code dmz_ragnarok:variant} client item property returns, so the model {@code overrides} can
     * pick a texture without CustomModelData. 0 = generic base, 1 = candy, 2 = candy corn, 3 = chocolate dabura,
     * 4 = chocolate dabura (wrapped). An unknown variant reads 0 (the base model), never a missing model.
     */
    public static int variantIndex(ItemStack stack)
    {
        return switch (variantOf(stack))
        {
            case HALLOWEEN, CANDY -> 1;
            case CANDY_CORN -> 2;
            case CHOCOLATE_DABURA -> 3;
            case CHOCOLATE_DABURA_WRAPPED -> 4;
            default -> 0;
        };
    }

    /** Whether this variant is one of the edible candies (NOT the wrapped chocolate, NOT a generic token). */
    private static boolean edibleVariant(String variant)
    {
        return HALLOWEEN.equals(variant) || CANDY.equals(variant)
                || CANDY_CORN.equals(variant) || CHOCOLATE_DABURA.equals(variant);
    }

    @Override
    public Component getName(ItemStack stack)
    {
        String variant = variantOf(stack);
        if (!variant.isEmpty())
        {
            // A per-variant lang key when one exists (item.dmz_ragnarok.event_token.<variant>); otherwise the base
            // name, so an unknown variant never renders a raw translation key.
            String key = getDescriptionId() + "." + variant;
            Component named = Component.translatable(key);
            if (!named.getString().equals(key))
                return named;
        }
        return super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag)
    {
        String variant = variantOf(stack);
        if (!variant.isEmpty())
        {
            // The description line ships as item.dmz_ragnarok.event_token.<variant>.desc; a variant with no desc
            // key adds nothing rather than showing a raw key.
            String key = getDescriptionId() + "." + variant + ".desc";
            Component desc = Component.translatable(key);
            if (!desc.getString().equals(key))
                tooltip.add(desc);
        }
        super.appendHoverText(stack, level, tooltip, flag);
    }

    // ---- per-stack food behaviour ------------------------------------------------------------------------

    @Override
    public FoodProperties getFoodProperties(ItemStack stack, LivingEntity entity)
    {
        return edibleVariant(variantOf(stack)) ? CANDY_FOOD : null;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack)
    {
        return edibleVariant(variantOf(stack)) ? UseAnim.EAT : UseAnim.NONE;
    }

    @Override
    public int getUseDuration(ItemStack stack)
    {
        // The normal (non-fast) eat time, matching the dino tail; a non-edible variant is never used for a duration.
        return edibleVariant(variantOf(stack)) ? 32 : 0;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand)
    {
        ItemStack stack = player.getItemInHand(hand);
        String variant = variantOf(stack);

        if (CHOCOLATE_DABURA_WRAPPED.equals(variant))
        {
            // Unwrap: one wrapped becomes one plain chocolate dabura, with a rustle. No eating here.
            if (!level.isClientSide)
            {
                ItemStack unwrapped = new ItemStack(this);
                unwrapped.getOrCreateTag().putString(VARIANT_TAG, CHOCOLATE_DABURA);
                stack.shrink(1);
                if (!player.getInventory().add(unwrapped))
                    player.drop(unwrapped, false);
            }
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BUNDLE_REMOVE_ONE, SoundSource.PLAYERS, 0.8F,
                    0.9F + level.getRandom().nextFloat() * 0.2F);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        if (edibleVariant(variant))
        {
            // Always-eat food (dino tail sets alwaysEat), so canEat(true) is true even at full hunger.
            if (player.canEat(true))
            {
                player.startUsingItem(hand);
                return InteractionResultHolder.consume(stack);
            }
            return InteractionResultHolder.fail(stack);
        }

        return super.use(level, player, hand);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity)
    {
        String variant = variantOf(stack);
        if (!edibleVariant(variant) || !(entity instanceof Player player))
            return super.finishUsingItem(stack, level, entity);

        // Apply the per-stack food directly (the base isEdible()/eat chain reads the item's null food field, which
        // is deliberately unset so a generic token stays inedible). Mirrors what LivingEntity.eat + FoodData.eat do.
        FoodProperties food = CANDY_FOOD;
        player.getFoodData().eat(food.getNutrition(), food.getSaturationModifier());
        if (!level.isClientSide)
        {
            for (var pair : food.getEffects())
            {
                if (pair.getFirst() != null && level.getRandom().nextFloat() < pair.getSecond())
                    player.addEffect(new MobEffectInstance(pair.getFirst()));
            }
        }
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_BURP, SoundSource.PLAYERS, 0.5F,
                level.getRandom().nextFloat() * 0.1F + 0.9F);
        if (!player.getAbilities().instabuild)
            stack.shrink(1);
        player.gameEvent(GameEvent.EAT);
        player.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(this));
        return stack;
    }
}
