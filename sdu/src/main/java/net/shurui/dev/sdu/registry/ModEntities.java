package net.shurui.dev.sdu.registry;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.entity.SduDmzFighter;

public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, DmzNpc.MODID);

    /** DMZ saga fighter that renders as a custom SDU NPC (true DMZ saga AI via {@link SduDmzFighter}). */
    public static final RegistryObject<EntityType<SduDmzFighter>> DMZ_FIGHTER = ENTITY_TYPES.register("dmz_fighter",
            () -> EntityType.Builder.of(SduDmzFighter::new, MobCategory.MONSTER)
                    .sized(0.7f, 2.0f)
                    .clientTrackingRange(10)
                    .build("dmz_fighter"));

    /** The fully custom, non-interactable Shenron display entity summoned by the Shenron-shrine feature. */
    public static final RegistryObject<EntityType<net.shurui.dev.sdu.entity.ShenronDisplayEntity>> SHENRON =
            ENTITY_TYPES.register("shenron", () -> EntityType.Builder
                    .of(net.shurui.dev.sdu.entity.ShenronDisplayEntity::new, MobCategory.MISC)
                    .sized(2.0f, 3.0f)
                    .clientTrackingRange(16)
                    .build("shenron"));

    /**
     * Duke Snipperjack: the Halloween rift boss (converted from the PixelBarrel MythicMobs pack). Registered
     * unconditionally so its geo always bakes and its saved instances always resolve; whether he may be SPAWNED
     * is decided by the rift/raid system that names {@code dmz_ragnarok:duke_snipperjack} as its boss entity.
     */
    public static final RegistryObject<EntityType<net.shurui.dev.sdu.entity.DukeSnipperjackEntity>> DUKE_SNIPPERJACK =
            ENTITY_TYPES.register("duke_snipperjack", () -> EntityType.Builder
                    .of(net.shurui.dev.sdu.entity.DukeSnipperjackEntity::new, MobCategory.MONSTER)
                    .sized(0.9f, 2.6f)
                    .clientTrackingRange(20)
                    .fireImmune()
                    .build("duke_snipperjack"));

    /** Duke Snipperjack's pumpkin puppet minion. */
    public static final RegistryObject<EntityType<net.shurui.dev.sdu.entity.PumpkinPuppetEntity>> PUMPKIN_PUPPET =
            ENTITY_TYPES.register("pumpkin_puppet", () -> EntityType.Builder
                    .of(net.shurui.dev.sdu.entity.PumpkinPuppetEntity::new, MobCategory.MONSTER)
                    .sized(0.7f, 1.6f)
                    .clientTrackingRange(16)
                    .build("pumpkin_puppet"));

    private ModEntities() {
    }
}
