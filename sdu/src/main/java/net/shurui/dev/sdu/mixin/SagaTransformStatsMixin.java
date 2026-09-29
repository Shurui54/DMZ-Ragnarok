package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.scores.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.List;

// Carries suite-configured stats across a DMZ saga transform. finishTransformationSpawn builds the next form
// fresh from createAttributes() and hand-copies only a fixed set of keys/attributes, dropping our
// defense-curve key, movement speed, model scale, battle power, ki-skill pool and custom name on every
// transform. At TAIL both the discarded predecessor (this) and the freshly-added successor are still live, so
// we copy directly. No parent field or shared UUID, so this is a plain object-to-object copy.
//
// Two tiers:
//   1. dmz_npc_defense copied unconditionally. Safe under raid management: the raid re-applies an identical
//      value on adoption, so no conflict.
//   2. movement speed / scale / battle power / ki pool / name copied ONLY when the form is not managed by
//      another engine this tick. shuruis_raid_bosses adopts the transformed form inside addFreshEntity
//      (EntityJoinLevelEvent, BEFORE this TAIL) and re-applies AUTHORITATIVE stats; re-copying the
//      predecessor's values would clobber that. Detected with no cross-addon dependency (see below).
//
// HP / ATTACK_DAMAGE / KiBlastDamage are intentionally scaled ~1.5x by DMZ on transform and left untouched.
// require=0 so an unmatched target degrades to a silent no-op.
@Mixin(value = DBSagasEntity.class, remap = false)
public abstract class SagaTransformStatsMixin {

    private static final String NPC_DEFENSE_KEY = "dmz_npc_defense";
    // shuruis_raid_bosses puts every adopted boss on this scoreboard team via applyGlow; matched as a raw string.
    private static final String RAID_GLOW_TEAM = "srb_glow";

    // resolved lazily; if DMZ ever renames SCALE_VAL the scale copy degrades to a no-op, the rest still runs.
    private static Field sdu$scaleField;
    private static boolean sdu$scaleFieldResolved;

    @Inject(method = "finishTransformationSpawn", at = @At("TAIL"), require = 0)
    private void sdu$carryConfiguredStats(DBSagasEntity newEntity, boolean fullHealth, CallbackInfo ci) {
        DBSagasEntity self = (DBSagasEntity) (Object) this;
        if (newEntity == null || self.level().isClientSide) return;

        CompoundTag src = self.getPersistentData();
        CompoundTag dst = newEntity.getPersistentData();

        // tier 1: always carry the defense-curve key.
        if (src.contains(NPC_DEFENSE_KEY)) {
            dst.putDouble(NPC_DEFENSE_KEY, src.getDouble(NPC_DEFENSE_KEY));
        }

        // tier 2: no-op for forms another engine already re-statted this tick, or we clobber authoritative stats.
        if (sdu$isManagedElsewhere(newEntity)) {
            org.slf4j.LoggerFactory.getLogger("sdu").info(
                    "Saga transform: {} is managed by the raid/sdu engine; carried defense only.",
                    newEntity.getType().getDescriptionId());
            return;
        }

        // movement speed base (vanilla attribute).
        AttributeInstance srcSpd = self.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeInstance dstSpd = newEntity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (srcSpd != null && dstSpd != null) dstSpd.setBaseValue(srcSpd.getBaseValue());

        // model scale (no public getter; read the private SCALE_VAL synched data reflectively, degrade-safe).
        try {
            float scale = sdu$readScale(self);
            if (scale > 0f) newEntity.setScaleVal(scale);
        } catch (Throwable ignored) { }

        // battle power (scouter reading).
        try {
            int bp = self.getBattlePower();
            if (bp > 0) newEntity.setBattlePower(bp);
        } catch (Throwable ignored) { }

        // ki-skill pool: the successor built its own DMZ default pool, so replace it with the predecessor's
        // configured pool when there is one. the predecessor is discarded, so sharing the entries is safe.
        try {
            List<DBSagasEntity.KiSkill> srcPool = self.getSkillPool();
            List<DBSagasEntity.KiSkill> dstPool = newEntity.getSkillPool();
            if (srcPool != null && !srcPool.isEmpty() && dstPool != null) {
                dstPool.clear();
                dstPool.addAll(srcPool);
            }
        } catch (Throwable ignored) { }

        // custom name (DMZ carries the quest texture variant but not the display name).
        Component name = self.getCustomName();
        if (name != null && newEntity.getCustomName() == null) {
            newEntity.setCustomName(name);
            newEntity.setCustomNameVisible(self.isCustomNameVisible());
        }

        org.slf4j.LoggerFactory.getLogger("sdu").info(
                "Saga transform: carried configured stats onto {} (speed/scale/bp/skills/name).",
                newEntity.getType().getDescriptionId());
    }

    // a form the raid engine or the sdu form engine already owns this tick. the raid puts every adopted form
    // on its "srb_glow" scoreboard team inside addFreshEntity (before this TAIL) and stamps raw NBT markers,
    // neither of which DMZ copies across a transform, so their presence on the successor means tier 2 must
    // no-op. read as raw strings/team names only, never classloading a sibling addon.
    private static boolean sdu$isManagedElsewhere(Entity e) {
        try {
            Team team = e.getTeam();
            if (team != null && RAID_GLOW_TEAM.equals(team.getName())) return true;
        } catch (Throwable ignored) { }
        CompoundTag pd = e.getPersistentData();
        return pd.contains("srb_novanilla") || pd.contains("sdu_tf_active") || pd.contains("sdu_tf");
    }

    private static float sdu$readScale(DBSagasEntity entity) throws Exception {
        if (!sdu$scaleFieldResolved) {
            sdu$scaleFieldResolved = true;
            Field f = DBSagasEntity.class.getDeclaredField("SCALE_VAL");
            f.setAccessible(true);
            sdu$scaleField = f;
        }
        if (sdu$scaleField == null) return 0f;
        @SuppressWarnings("unchecked")
        EntityDataAccessor<Float> acc = (EntityDataAccessor<Float>) sdu$scaleField.get(null);
        return entity.getEntityData().get(acc);
    }
}
