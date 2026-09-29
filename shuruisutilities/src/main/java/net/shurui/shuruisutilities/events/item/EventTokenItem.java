package net.shurui.shuruisutilities.events.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The single reserve event-collectible item, {@code dmz_ragnarok:event_token}. One registered item serves every
 * event: its LOOK is driven per event by NBT ({@code Variant} plus vanilla {@code CustomModelData}), so a themed
 * token (Halloween candy, and future themes) needs no new registry entry and therefore no client update. The item
 * always exists in core on every key tier (registration must not vary by key); whether it is granted, shown or
 * used is gated elsewhere (ContentGate server-side, ClientGate.feature("events") in the creative tab).
 *
 * <p>E0 ships only the base look and the Halloween ("candy") override. The {@code Variant} tag also picks the hover
 * name, so a Halloween token reads "Candy" rather than the generic "Event Token". Reading NBT keeps this data-safe:
 * a token with no {@code Variant} is a valid generic token, and an unknown variant falls back to the base name.
 */
public class EventTokenItem extends Item
{
    /** NBT string tag naming the event theme this token belongs to (drives the hover name and, with CustomModelData, the model). */
    public static final String VARIANT_TAG = "Variant";

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
}
