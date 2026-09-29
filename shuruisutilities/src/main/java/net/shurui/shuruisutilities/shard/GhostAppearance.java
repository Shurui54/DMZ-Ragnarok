package net.shurui.shuruisutilities.shard;

import com.dragonminez.common.stats.character.Character;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * A cross-server ghost's LOOK, packed into two short strings.
 *
 * <p>The ghost row is upserted at ten hertz per player, so a column per field would mean seventeen new columns,
 * placeholders and a migration each. Packing into one delimited string keeps it at two columns and two packet
 * fields, under a hundred bytes on the wire. {@code |} is the delimiter because nothing here can contain one:
 * colours are {@code #rrggbb}, item ids are {@code namespace:path}, the rest numbers and booleans.
 *
 * <p>Deliberately NOT here: the four {@code CustomHair} objects (strand editor output) and full item NBT, both
 * unbounded blobs on a ten hertz path. A PRESET hair ({@code hairId}) is covered; a custom strand hair falls back
 * to the preset. Armour travels as the item id alone, so it renders its own model and texture but loses dye, trim
 * and enchantment glint.
 *
 * <p>{@link #apply} reads only as many fields as given, so an older peer sending fewer leaves the rest at defaults.
 * Fields must only ever be APPENDED, never reordered or removed.
 */
public final class GhostAppearance
{
    private GhostAppearance() {}

    private static final String SEP = "\\|";
    private static final char JOIN = '|';

    /** Read a character's look. Server thread: it touches live capability data. */
    public static String encode(Character ch)
    {
        if (ch == null)
            return "";
        StringBuilder b = new StringBuilder(96);
        b.append(ch.getHairId()).append(JOIN);
        b.append(ch.isRenderHairBase() ? 1 : 0).append(JOIN);
        b.append(safe(ch.getHairColor())).append(JOIN);
        b.append(safe(ch.getBodyColor())).append(JOIN);
        b.append(safe(ch.getBodyColor2())).append(JOIN);
        b.append(safe(ch.getBodyColor3())).append(JOIN);
        b.append(safe(ch.getEye1Color())).append(JOIN);
        b.append(safe(ch.getEye2Color())).append(JOIN);
        b.append(ch.getEyesType()).append(JOIN);
        b.append(ch.getNoseType()).append(JOIN);
        b.append(ch.getMouthType()).append(JOIN);
        b.append(ch.getTattooType()).append(JOIN);
        b.append(ch.isHasSaiyanTail() ? 1 : 0).append(JOIN);
        b.append(safe(ch.getActiveHeadBone()));
        return b.toString();
    }

    /**
     * Write a look onto a ghost's character. Every field is guarded individually: a wrong eye colour is a cosmetic
     * miss, an exception here runs during render setup and is not.
     */
    public static void apply(Character ch, String encoded)
    {
        if (ch == null || encoded == null || encoded.isEmpty())
            return;
        String[] f = encoded.split(SEP, -1);
        setInt(f, 0, ch::setHairId);
        setBool(f, 1, ch::setRenderHairBase);
        setStr(f, 2, ch::setHairColor);
        setStr(f, 3, ch::setBodyColor);
        setStr(f, 4, ch::setBodyColor2);
        setStr(f, 5, ch::setBodyColor3);
        setStr(f, 6, ch::setEye1Color);
        setStr(f, 7, ch::setEye2Color);
        setInt(f, 8, ch::setEyesType);
        setInt(f, 9, ch::setNoseType);
        setInt(f, 10, ch::setMouthType);
        setInt(f, 11, ch::setTattooType);
        setBool(f, 12, ch::setHasSaiyanTail);
        setStr(f, 13, ch::setActiveHeadBone);
    }

    /** The four worn pieces as item ids, head first. Empty slots are empty strings. */
    public static String encodeArmor(LivingEntity entity)
    {
        if (entity == null)
            return "";
        StringBuilder b = new StringBuilder(64);
        EquipmentSlot[] slots = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
        for (int i = 0; i < slots.length; i++)
        {
            if (i > 0)
                b.append(JOIN);
            try
            {
                ItemStack st = entity.getItemBySlot(slots[i]);
                if (st != null && !st.isEmpty())
                {
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
                    if (id != null)
                        b.append(id);
                }
            }
            catch (Throwable ignored)
            {
                // leave the slot empty rather than lose the other three
            }
        }
        return b.toString();
    }

    /**
     * Put armour onto a ghost via {@code setItemSlot}, what the armour render layer reads. An item this client does
     * not have (a mod the viewer is missing) resolves to null and the slot is left empty, drawing nothing.
     */
    public static void applyArmor(LivingEntity ghost, String encoded)
    {
        if (ghost == null || encoded == null)
            return;
        EquipmentSlot[] slots = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
        String[] f = encoded.isEmpty() ? new String[0] : encoded.split(SEP, -1);
        for (int i = 0; i < slots.length; i++)
        {
            ItemStack want = ItemStack.EMPTY;
            if (i < f.length && !f[i].isEmpty())
            {
                try
                {
                    ResourceLocation id = ResourceLocation.tryParse(f[i]);
                    Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
                    if (item != null)
                        want = new ItemStack(item);
                }
                catch (Throwable ignored)
                {
                    // unknown item: draw nothing for that slot
                }
            }
            try
            {
                // only when it actually changed: setItemSlot on every packet rebuilds the stack ten times a second
                // for a ghost standing there in the same armour
                ItemStack have = ghost.getItemBySlot(slots[i]);
                if (have == null || !ItemStack.isSameItem(have, want))
                    ghost.setItemSlot(slots[i], want);
            }
            catch (Throwable ignored)
            {
            }
        }
    }

    private static String safe(String s)
    {
        if (s == null)
            return "";
        // A stray delimiter would shift every field after it, so it is dropped rather than trusted.
        return s.indexOf(JOIN) >= 0 ? s.replace(String.valueOf(JOIN), "") : s;
    }

    private interface IntSetter { void set(int v); }
    private interface BoolSetter { void set(boolean v); }
    private interface StrSetter { void set(String v); }

    private static void setInt(String[] f, int i, IntSetter setter)
    {
        if (i >= f.length || f[i].isEmpty())
            return;
        try
        {
            setter.set(Integer.parseInt(f[i]));
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void setBool(String[] f, int i, BoolSetter setter)
    {
        if (i >= f.length || f[i].isEmpty())
            return;
        try
        {
            setter.set("1".equals(f[i]));
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void setStr(String[] f, int i, StrSetter setter)
    {
        if (i >= f.length || f[i].isEmpty())
            return;
        try
        {
            setter.set(f[i]);
        }
        catch (Throwable ignored)
        {
        }
    }
}
