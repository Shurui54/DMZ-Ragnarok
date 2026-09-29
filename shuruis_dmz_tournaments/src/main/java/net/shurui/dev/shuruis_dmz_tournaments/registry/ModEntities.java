package net.shurui.dev.shuruis_dmz_tournaments.registry;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.dev.shuruis_dmz_tournaments.Shuruis_dmz_tournaments;
import net.shurui.dev.shuruis_dmz_tournaments.entity.TournamentNpc;

public final class ModEntities {
    private ModEntities() {}

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Shuruis_dmz_tournaments.MODID);

    public static final RegistryObject<EntityType<TournamentNpc>> TOURNAMENT_NPC = ENTITIES.register(
            "tournament_npc",
            () -> EntityType.Builder.of(TournamentNpc::new, MobCategory.MISC)
                    .sized(0.6f, 1.95f)
                    .clientTrackingRange(10)
                    .build("tournament_npc"));
}
