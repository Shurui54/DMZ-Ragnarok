package net.shurui.shuruisutilities.ragnarok;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.MissingMappingsEvent;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Registry + mod-event-bus attribute creation for the single data-driven {@link RgNpcEntity} display NPC. The
 * DeferredRegister is registered onto the mod bus from {@link ShuruisUtilities}'s constructor (matching SU's
 * other REGISTER blocks); attribute creation is handled here on the MOD bus.
 *
 * <p>The pre-rename id remap lives in the nested {@link Remap} subscriber. NOTE: {@link MissingMappingsEvent}
 * is fired on the FORGE bus (see its Forge javadoc: "Fired on the forge bus"), not the mod bus, so the remap
 * handler is registered there. Putting it on the mod bus would silently never fire and every already-spawned
 * NPC would be dropped on world load.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RgNpcEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ShuruisUtilities.MODID);

    /** One entity type for all models; model/texture/scale are per-entity synced data on {@link RgNpcEntity}. */
    public static final RegistryObject<EntityType<RgNpcEntity>> RGNPC = ENTITY_TYPES.register("rgnpc",
            () -> EntityType.Builder.of(RgNpcEntity::new, MobCategory.MISC)
                    .sized(RgNpcEntity.HITBOX_WIDTH, RgNpcEntity.HITBOX_HEIGHT)
                    .clientTrackingRange(16)
                    .build("rgnpc"));

    // Old entity type id, remapped to RGNPC so already-spawned NPCs survive the July 2026 rename instead of
    // vanishing on world load. The namespace is pinned to the LITERAL "shuruisutilities" (not ShuruisUtilities.MODID)
    // because the stored id in a pre-rename save is shuruisutilities:ninjin_npc; once the dmz_ragnarok merge flips
    // MODID this constant must keep naming the old saved id, not the new one.
    private static final ResourceLocation LEGACY_ENTITY_ID =
            ResourceLocation.fromNamespaceAndPath("shuruisutilities", "ninjin_npc");

    // The five pre-rename music-disc ids (record_dbcN) used to be remapped to their record_rgN replacements here.
    // Both families were removed in the 2.0 debloat (the record items did nothing), so there is nothing to remap
    // any more: NamespaceRemap.RETIRED_ITEMS ignores record_dbcN and record_rgN alike so old saves still load clean.

    private RgNpcEntities() {
    }

    @SubscribeEvent
    public static void createAttributes(EntityAttributeCreationEvent event) {
        event.put(RGNPC.get(), RgNpcEntity.createAttributes().build());
    }

    /**
     * FORGE-bus subscriber for the pre-rename registry id remap. Remaps the pre-rename entity type id onto its
     * current registry object, so worlds saved before the July 2026 rename keep their spawned NPCs. Missing-mappings
     * is the correct hook: it fires only for ids present in the save but absent from the current registry, and
     * {@code remap()} rewrites them in place. Only our own namespace is ever touched. The music-disc half of this
     * remap is gone: the record items were removed in the 2.0 debloat and are retired by NamespaceRemap instead.
     *
     * <p>Kept on the FORGE bus because {@link MissingMappingsEvent} is a forge-bus event, unlike the mod-bus
     * {@link EntityAttributeCreationEvent} above.
     */
    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class Remap {

        private Remap() {
        }

        @SubscribeEvent
        public static void onMissingMappings(MissingMappingsEvent event) {
            for (MissingMappingsEvent.Mapping<EntityType<?>> mapping
                    : event.getAllMappings(ForgeRegistries.Keys.ENTITY_TYPES)) {
                if (mapping.getKey().equals(LEGACY_ENTITY_ID)) {
                    mapping.remap(RGNPC.get());
                }
            }
            // The record_dbcN -> record_rgN disc remap that used to live here is gone: both disc families were
            // removed in the 2.0 debloat and are retired (ignored) by NamespaceRemap.RETIRED_ITEMS instead.
        }
    }
}
