package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.quest.Quest;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.shurui.dev.sdu.client.ClientPreviewClones;
import net.shurui.dev.sdu.client.ClientQuestKeys;
import net.shurui.dev.sdu.compat.cnpc.CnpcPreviewConfig;
import net.shurui.dev.sdu.entity.SduDmzFighter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Makes the DMZ quest-tree enemy preview render an sdu:dmz_fighter objective as the actual saved Custom NPC
// it spawns as: real name + appearance (model/skin/hair), not a bare fighter.
//
// KillObjective stores only the entity id, no clone ref, so we correlate by quest: boundQuest maps to a
// "<sagaId>:<questId>" key, and currentIndex is the KILL-objective ordinal, matching the KILL-ordered config
// list synced into ClientPreviewClones. remap=false; require=0 so a DMZ signature change degrades to the old
// preview instead of crashing.
@Mixin(targets = "com.dragonminez.client.gui.quest.preview.QuestEnemyPreview", remap = false)
public abstract class QuestEnemyPreviewMixin {

    @Shadow
    private Quest boundQuest;

    @Shadow
    private int currentIndex;

    // dress our fighter to look like the bound saved clone once DMZ returns the preview entity
    @Inject(method = "getCurrentEntity", at = @At("RETURN"), require = 0, remap = false)
    private void sdu$configurePreview(CallbackInfoReturnable<LivingEntity> cir) {
        if (!(cir.getReturnValue() instanceof SduDmzFighter fighter)) {
            return;
        }
        CnpcPreviewConfig cfg = sdu$config();
        if (cfg == null) {
            return;
        }
        if (cfg.modelGeo() != null && !cfg.modelGeo().isBlank()) {
            fighter.setModelGeo(cfg.modelGeo());
        }
        fighter.setSkin(cfg.skinType(), cfg.skinValue());
        fighter.setHairCode(cfg.hairCode());
        fighter.setHairColor(cfg.hairColor());
        if (cfg.name() != null && !cfg.name().isBlank()) {
            fighter.setCustomName(Component.literal(cfg.name()));
            fighter.setCustomNameVisible(true);
        }
    }

    // real clone name in the preview label, not the fighter's entity-type key
    @Inject(method = "getTargetName", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void sdu$targetName(LivingEntity entity, CallbackInfoReturnable<Component> cir) {
        if (entity instanceof SduDmzFighter && entity.getCustomName() != null) {
            cir.setReturnValue(entity.getCustomName());
        }
    }

    // saved-clone config bound to boundQuest's current KILL target, or null
    private CnpcPreviewConfig sdu$config() {
        String key = ClientQuestKeys.keyFor(boundQuest);
        return key == null ? null : ClientPreviewClones.get(key, currentIndex);
    }
}
