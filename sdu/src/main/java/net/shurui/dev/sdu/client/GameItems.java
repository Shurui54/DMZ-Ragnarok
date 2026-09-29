package net.shurui.dev.sdu.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Client-side cached list of every item id in the game (vanilla, DMZ, and other mods). */
public final class GameItems {

    private static List<ResourceLocation> itemIds;

    private GameItems() {
    }

    public static List<ResourceLocation> itemIds() {
        if (itemIds == null) {
            List<ResourceLocation> list = new ArrayList<>(ForgeRegistries.ITEMS.getKeys());
            list.sort(Comparator.comparing(ResourceLocation::toString));
            itemIds = list;
        }
        return itemIds;
    }
}
