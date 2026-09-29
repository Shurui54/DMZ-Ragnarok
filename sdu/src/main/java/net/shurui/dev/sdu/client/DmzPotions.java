package net.shurui.dev.sdu.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * All registered vanilla/mod potion effects ({@code MobEffect}) as {@code namespace:path} ids, for
 * the racial-skill editor's searchable potion dropdown. Sourced live from the registry so any mod's
 * effects appear too.
 */
public final class DmzPotions {

    private DmzPotions() {
    }

    public static List<String> effectIds() {
        List<String> ids = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.MOB_EFFECT.keySet()) {
            ids.add(id.toString());
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }
}
