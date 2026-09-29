package net.shurui.dev.sdu.mixin.cnpc;

import com.goodbird.cnpcgeckoaddon.entity.EntityCustomModel;
import net.minecraft.world.entity.LivingEntity;
import net.shurui.dev.sdu.compat.cnpc.SduHairHolder;
import net.shurui.dev.sdu.compat.cnpc.SduHairModelHolder;
import noppes.npcs.ModelData;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Copies the NPC's DMZ hair code + colour (from its SduHairHolder display) onto the Gecko addon's
// EntityCustomModel whenever ModelData#getEntity returns one, mirroring how the addon copies its
// model/animation data onto the same entity. Lets SduHairLayer read the hair off the model entity itself,
// side-stepping the null EntityCustomModel.owner in some render paths. remap=false (Custom NPCs method).
@Mixin(value = ModelData.class, remap = false)
public class ModelDataHairMixin {

    @Inject(method = "getEntity", at = @At("RETURN"))
    private void sdu$copyHairToModel(EntityNPCInterface npc, CallbackInfoReturnable<LivingEntity> cir) {
        LivingEntity entity = cir.getReturnValue();
        if (!(entity instanceof EntityCustomModel) || npc == null || !(npc.display instanceof SduHairHolder holder)) {
            return;
        }
        SduHairModelHolder model = (SduHairModelHolder) entity;
        model.sdu$setModelHairCode(holder.sdu$getHairCode());
        model.sdu$setModelHairColor(holder.sdu$getHairColor());
    }
}
