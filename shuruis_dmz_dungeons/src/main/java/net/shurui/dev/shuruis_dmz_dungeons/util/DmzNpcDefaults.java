package net.shurui.dev.shuruis_dmz_dungeons.util;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.EntitiesConfig;
import com.dragonminez.common.init.EntityAttributes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraftforge.registries.ForgeRegistries;

// server-side lookup of an NPC's DMZ default stats for an entity id, so the spawner editor can auto-fill the
// stat fields with the real values DMZ would give that entity. DMZ is a hard dep so com.dragonminez.* is
// referenced directly (no ModList guard). two-tier: (1) ConfigManager.getEntityStats (DMZ's data-driven table,
// where most saga NPCs live), (2) fall back to the EntityType's registered AttributeSupplier.
public final class DmzNpcDefaults {

    // DMZ's per-NPC default AI tier, 1-based (1 = SIMPLE)
    public static final int DEFAULT_AI_TIER = 1;

    private DmzNpcDefaults() {
    }

    // aiTier1Based is DMZ 1-based (1/2/3); fromConfig = came from ConfigManager vs the attribute supplier
    public record Defaults(double health, double melee, double ki, int aiTier1Based, boolean fromConfig) {
    }

    // resolve DMZ defaults for idString (e.g. "dragonminez:frieza"), or null if unknown / no resolvable stats
    public static Defaults forEntityId(String idString) {
        if (idString == null || idString.isBlank()) {
            return null;
        }

        // tier 1: DMZ's data-driven stat table
        EntitiesConfig.EntityStats stats = ConfigManager.getEntityStats(idString);
        if (stats != null) {
            double health = stats.getHealth() == null ? 0.0 : stats.getHealth();
            double melee = stats.getMeleeDamage() == null ? 0.0 : stats.getMeleeDamage();
            double ki = stats.getKiDamage() == null ? 0.0 : stats.getKiDamage();
            return new Defaults(health, melee, ki, DEFAULT_AI_TIER, true);
        }

        // tier 2: the entity type's registered default attributes
        ResourceLocation id = ResourceLocation.tryParse(idString);
        if (id == null) {
            return null;
        }
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (type == null || !DefaultAttributes.hasSupplier(type)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        EntityType<? extends LivingEntity> living = (EntityType<? extends LivingEntity>) type;
        AttributeSupplier supplier = DefaultAttributes.getSupplier(living);

        double health = readAttr(supplier, Attributes.MAX_HEALTH);
        double melee = readAttr(supplier, Attributes.ATTACK_DAMAGE);
        Attribute kiAttr = EntityAttributes.KI_BLAST_DAMAGE.get();
        double ki = readAttr(supplier, kiAttr);
        return new Defaults(health, melee, ki, DEFAULT_AI_TIER, false);
    }

    // guards hasAttribute so absent attributes return 0
    private static double readAttr(AttributeSupplier supplier, Attribute attr) {
        if (attr == null || !supplier.hasAttribute(attr)) {
            return 0.0;
        }
        return supplier.getValue(attr);
    }
}
