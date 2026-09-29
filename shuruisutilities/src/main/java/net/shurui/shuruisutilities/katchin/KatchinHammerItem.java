package net.shurui.shuruisutilities.katchin;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.Tier;

/**
 * A hammer: a pickaxe-family tool that breaks a 3x3 area one block deep in the plane perpendicular to the mined
 * face. It extends {@link PickaxeItem} so it already carries the {@code minecraft:mineable/pickaxe} block set and
 * the tier-based drop gating; the area break itself lives in {@link HammerBreakHandler} (a Forge-bus
 * {@code BlockEvent.BreakEvent} listener), which keys off {@code instanceof KatchinHammerItem} so both the katchin
 * and katchi katchin hammers share one implementation.
 */
public class KatchinHammerItem extends PickaxeItem
{
    public KatchinHammerItem(Tier tier, int attackDamage, float attackSpeed, Item.Properties properties)
    {
        super(tier, attackDamage, attackSpeed, properties);
    }
}
