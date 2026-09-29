package net.shurui.dev.sdu.compat.cnpc;

import com.goodbird.cnpcgeckoaddon.data.CustomModelData;
import com.goodbird.cnpcgeckoaddon.mixin.IDataDisplay;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.shurui.dev.sdu.DmzNpc;
import noppes.npcs.entity.EntityCustomNpc;

import java.util.List;

/**
 * Feeds DMZ's GeckoLib assets onto a Custom NPC (features 4.2 &amp; 4.6). No renderer of our own: the
 * CNPC-Gecko-Addon already supplies a GeckoLib model type on the NPC. We just flip the NPC onto that type and
 * point its {@link CustomModelData} at DMZ's {@code .geo.json}/{@code .animation.json} by resource location.
 * GeckoLib bakes those at resource load, so we reference in place, no copying (keeps 4.6 update-safe).
 *
 * <p>References {@code noppes.npcs.*} and {@code com.goodbird.cnpcgeckoaddon.*} directly, so classload only
 * when {@link DmzCnpcCompat#geckoAddonAvailable()} is true; callers gate on that. Every public method is
 * try/caught so a CNPC/addon change degrades to "nothing applied".
 */
public final class CnpcGeckoBridge {

    /** the addon's GeckoLib render-proxy entity id (its registry.EntityRegistry) */
    private static final ResourceLocation CUSTOM_MODEL_ENTITY = new ResourceLocation("cnpcgeckoaddon", "custommodelentity");

    private CnpcGeckoBridge() {
    }

    /**
     * Apply a DMZ model + animation set to the nearest Custom NPC within {@code radius} of {@code pos}.
     * Returns 1 if one was updated, else 0. Clip names may be empty. Used by {@code /rg npc dmznpc model}.
     */
    public static int applyToNearest(Level level, Vec3 pos, double radius,
                                     String geoLoc, String animLoc,
                                     String idle, String walk, String attack, String hurt) {
        try {
            AABB box = new AABB(pos, pos).inflate(radius);
            List<EntityCustomNpc> npcs = level.getEntitiesOfClass(EntityCustomNpc.class, box);
            EntityCustomNpc nearest = null;
            double best = Double.MAX_VALUE;
            for (EntityCustomNpc npc : npcs) {
                double d = npc.distanceToSqr(pos);
                if (d < best) {
                    best = d;
                    nearest = npc;
                }
            }
            if (nearest == null) {
                return 0;
            }
            applyDmzModel(nearest, geoLoc, animLoc, idle, walk, attack, hurt, null);
            nearest.updateClient();
            return 1;
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] applyToNearest failed: {}", DmzNpc.MODID, t.toString());
            return 0;
        }
    }

    /**
     * Switch {@code npc} onto the addon's GeckoLib model and point it at the DMZ geo/animation locations (and
     * optional texture). {@code geoLoc}/{@code animLoc} are full resource-location strings; empty clip names
     * leave the addon's defaults. Nullable {@code textureLoc} is applied as a "texture" skin so DMZ models wear
     * their own skin instead of a Steve default.
     *
     * <p>Doesn't call {@code updateClient()}; the caller decides propagation.</p>
     */
    public static void applyDmzModel(EntityCustomNpc npc, String geoLoc, String animLoc,
                                     String idle, String walk, String attack, String hurt, String textureLoc) {
        ensureCustomModelEntity(npc);

        CustomModelData model = ((IDataDisplay) npc.display).getCustomModelData();
        model.setModel(geoLoc);
        model.setAnimFile(animLoc);
        model.setIdleAnim(nz(idle));
        model.setWalkAnim(nz(walk));
        model.setAttackAnim(nz(attack));
        model.setHurtAnim(nz(hurt));

        if (textureLoc != null && !textureLoc.isBlank()) {
            applyTextureSkin(npc, textureLoc);
        }
        DmzNpc.LOGGER.debug("[{}] Applied DMZ model '{}' (anim '{}', texture '{}') to Custom NPC {}.",
                DmzNpc.MODID, geoLoc, animLoc, textureLoc, ((Entity) npc).getId());
    }

    /**
     * Put {@code npc} on the addon's GeckoLib render proxy if not already, so ModelData rebuilds its entity as
     * an EntityCustomModel on the next {@code getEntity()} and the addon reads CustomModelData from then on.
     *
     * <p><b>Every write to CustomModelData must be preceded by this, or the write is silently thrown away.</b>
     * The addon's {@code MixinDataDisplay.writeToNBT} writes the CustomModelData block only when
     * {@code hasCustomModel()} is true, which is exactly
     * {@code npc.modelData.getEntity(npc) instanceof EntityCustomModel}. An NPC whose model was set while it
     * still pointed elsewhere keeps the value in memory, renders with it all session, then loses it the moment
     * the editor's save round-trip serialises it, coming back as a plain humanoid that {@code CnpcCitizenModels}
     * dresses in {@code sdu_wide}: a Steve. That is the whole "my DMZ model reverted" report.
     *
     * <p>Guarded not unconditional because {@code setEntity} also clears ModelData's {@code extra} tag, so
     * re-flipping an NPC already on the proxy would discard whatever the addon stored there.
     */
    public static void ensureCustomModelEntity(EntityCustomNpc npc) {
        if (npc == null || npc.modelData == null) {
            return;
        }
        if (CUSTOM_MODEL_ENTITY.equals(npc.modelData.getEntityName())) {
            return;
        }
        npc.modelData.setEntity(CUSTOM_MODEL_ENTITY);
    }

    /**
     * Point the NPC's skin at a raw resource-location texture ({@code skinType 0}). The Gecko addon reads the
     * model texture from the NPC's skin, so this is how a DMZ model wears a specific texture (feature 4.5) or
     * a saga preset gets its own skin. Resets cached {@code textureLocation} so it recomputes.
     */
    public static void applyTextureSkin(EntityCustomNpc npc, String textureLoc) {
        npc.display.skinType = 0;
        npc.display.setSkinTexture(textureLoc);
        npc.textureLocation = null;
    }

    /** Feature 4.5: apply a Minecraft player-name skin ({@code skinType 1}). */
    public static void applyPlayerSkin(EntityCustomNpc npc, String playerName) {
        npc.display.skinType = 1;
        npc.display.setSkinPlayer(playerName);
        npc.display.loadProfile();
        npc.textureLocation = null;
    }

    /** Feature 4.5: apply a URL skin ({@code skinType 2}). */
    public static void applyUrlSkin(EntityCustomNpc npc, String url) {
        npc.display.skinType = 2;
        npc.display.setSkinUrl(url);
        npc.textureLocation = null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
