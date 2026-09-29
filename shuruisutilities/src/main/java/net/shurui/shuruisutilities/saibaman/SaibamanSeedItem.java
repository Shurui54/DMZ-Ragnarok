package net.shurui.shuruisutilities.saibaman;

import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraft.world.level.block.Block;

/**
 * The saibaman seed: plants the {@link SaibamanCropBlock}. Extends {@link ItemNameBlockItem} so its display name comes
 * from the item's own translation key ({@code item.shuruisutilities.saibaman_seed}) rather than the crop block's, the
 * same wiring vanilla uses for wheat seeds. Placement is auto-gated to {@code dragonminez:rocky_dirt} by the crop
 * block's {@code canSurvive}, so no soil check is duplicated here.
 */
public class SaibamanSeedItem extends ItemNameBlockItem
{
    public SaibamanSeedItem(Block crop, Properties properties)
    {
        super(crop, properties);
    }
}
