package net.shurui.shuruisutilities.compat;

import java.lang.reflect.Method;

import net.minecraft.world.item.Item;

/** Reads {@code AEItemKey.getItem()} reflectively; null for fluid keys, other key types, or any failure. */
public final class Ae2KeyItem
{
    private Ae2KeyItem()
    {
    }

    private static Class<?> keyClass;
    private static Method getItem;
    private static boolean failed;

    public static Item itemOf(Object key)
    {
        if (key == null || failed)
        {
            return null;
        }
        try
        {
            if (getItem == null)
            {
                keyClass = Class.forName("appeng.api.stacks.AEItemKey", false, key.getClass().getClassLoader());
                getItem = keyClass.getMethod("getItem");
            }
            return keyClass.isInstance(key) ? (Item) getItem.invoke(key) : null;
        }
        catch (Throwable t)
        {
            failed = true;
            return null;
        }
    }
}
