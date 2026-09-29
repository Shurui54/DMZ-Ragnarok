package net.shurui.dev.sdu.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Cached list of every "mob" entity id (all registered types not in the MISC category, i.e. not
// projectiles/items). Feeds the saga editor's KILL-objective entity dropdown so any mob (vanilla, DMZ, or
// other mods) can be chosen.
public final class GameEntities {

    private static List<ResourceLocation> mobIds;

    private GameEntities() {
    }

    public static List<ResourceLocation> mobIds() {
        if (mobIds == null) {
            List<ResourceLocation> list = new ArrayList<>();
            for (var entry : ForgeRegistries.ENTITY_TYPES.getEntries()) {
                EntityType<?> type = entry.getValue();
                if (type.getCategory() != MobCategory.MISC) {
                    list.add(entry.getKey().location());
                }
            }
            list.sort(Comparator.comparing(ResourceLocation::toString));
            mobIds = list;
        }
        return mobIds;
    }
}
