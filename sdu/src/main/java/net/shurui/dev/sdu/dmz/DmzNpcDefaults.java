package net.shurui.dev.sdu.dmz;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Server-side lookup of a DragonMine Z NPC's default combat stats for the KILL objective auto-fill in the
 * saga/quest editor. Given an entity-id string it returns DMZ's configured stats when present
 * ({@code ConfigManager.getEntityStats}), otherwise the entity type's vanilla attribute defaults.
 *
 * <p>sdu compiles against DMZ directly (DMZ is a {@code mandatory} dependency of every addon), so referencing
 * DMZ classes here is safe and needs no {@code ModList} gate. DMZ config has no per-entity AI tier field, and
 * DMZ's own quests never write {@code AITier}, so the auto-fill reports the AI tier as {@code -1} (Auto: DMZ
 * scales the NPC's tier with the server difficulty). Reporting a fixed {@code 1} instead would make the editor
 * pin every auto-filled objective to tier 1 and drop that scaling; the admin can still pick a fixed tier.</p>
 */
public final class DmzNpcDefaults {

    /**
     * Immutable stat bundle for one entity id. {@code aiTier} matches DMZ's {@code AITier} sentinel: {@code -1}
     * = Auto (scale with difficulty), {@code 1}..{@code 3} = a fixed DMZ tier. DMZ exposes no per-entity tier,
     * so this is always {@code -1}.
     */
    public record Defaults(double health, double melee, double ki, int aiTier, boolean fromConfig) {
    }

    private DmzNpcDefaults() {
    }

    /**
     * Resolve default stats for {@code entityId} (e.g. {@code "dmz_ragnarok:dmz_fighter"} or a DMZ NPC id). Returns
     * {@code null} if the id is unparseable or has no registered entity type and no DMZ config entry.
     */
    public static Defaults lookup(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            return null;
        }

        // 1) DMZ config-driven stats (EntitiesConfig.EntityStats), when DMZ defines this entity.
        try {
            com.dragonminez.common.config.EntitiesConfig.EntityStats stats =
                    com.dragonminez.common.config.ConfigManager.getEntityStats(entityId);
            if (stats != null) {
                double health = stats.getHealth() != null ? stats.getHealth() : 0.0;
                double melee = stats.getMeleeDamage() != null ? stats.getMeleeDamage() : 0.0;
                double ki = stats.getKiDamage() != null ? stats.getKiDamage() : 0.0;
                if (health > 0 || melee > 0 || ki > 0) {
                    return new Defaults(health, melee, ki, -1, true);
                }
            }
        } catch (Throwable t) {
            net.shurui.dev.sdu.DmzNpc.LOGGER.debug("[sdu] DMZ getEntityStats('{}') failed: {}", entityId, t.toString());
        }

        // 2) Fall back to the entity type's default AttributeSupplier.
        ResourceLocation id = ResourceLocation.tryParse(entityId);
        if (id == null) {
            return null;
        }
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (type == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            EntityType<? extends LivingEntity> living = (EntityType<? extends LivingEntity>) type;
            if (!DefaultAttributes.hasSupplier(living)) {
                return null;
            }
            AttributeSupplier supplier = DefaultAttributes.getSupplier(living);
            double health = supplier.hasAttribute(Attributes.MAX_HEALTH)
                    ? supplier.getValue(Attributes.MAX_HEALTH) : 0.0;
            double melee = supplier.hasAttribute(Attributes.ATTACK_DAMAGE)
                    ? supplier.getValue(Attributes.ATTACK_DAMAGE) : 0.0;
            double ki = 0.0;
            var kiAttr = com.dragonminez.common.init.EntityAttributes.KI_BLAST_DAMAGE.get();
            if (kiAttr != null && supplier.hasAttribute(kiAttr)) {
                ki = supplier.getValue(kiAttr);
            }
            return new Defaults(health, melee, ki, -1, false);
        } catch (Throwable t) {
            net.shurui.dev.sdu.DmzNpc.LOGGER.debug("[sdu] AttributeSupplier defaults for '{}' failed: {}",
                    entityId, t.toString());
            return null;
        }
    }
}
