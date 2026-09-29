package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * Lets PrivateListingRefresh drop a tab's cached icon when the synced key answer changes. A tab computes its icon once
 * and keeps it, so a tab whose icon is a private item (the dragon balls and blocks tabs) would otherwise keep whichever
 * icon the first server it was drawn on asked for. Null makes the next draw ask the icon supplier again.
 */
@Mixin(CreativeModeTab.class)
public interface AccessorCreativeModeTabIcon
{
    @Accessor("iconItemStack")
    void su$setIconItemStack(ItemStack stack);
}
