package net.shurui.dev.sdu.mixin.cnpc;

import com.goodbird.cnpcgeckoaddon.entity.EntityCustomModel;
import net.shurui.dev.sdu.compat.cnpc.SduHairModelHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

// Gives the Gecko addon's EntityCustomModel fields to carry a DMZ hair code + colour, populated by
// ModelDataHairMixin from the NPC's display. SduHairLayer renders from these, dodging the null owner problem.
// remap=false (addon class).
@Mixin(value = EntityCustomModel.class, remap = false)
public class EntityCustomModelHairMixin implements SduHairModelHolder {

    @Unique
    private String sdu$modelHairCode = "";

    @Unique
    private String sdu$modelHairColor = "";

    @Override
    @Unique
    public String sdu$getModelHairCode() {
        return sdu$modelHairCode;
    }

    @Override
    @Unique
    public void sdu$setModelHairCode(String code) {
        sdu$modelHairCode = code == null ? "" : code;
    }

    @Override
    @Unique
    public String sdu$getModelHairColor() {
        return sdu$modelHairColor;
    }

    @Override
    @Unique
    public void sdu$setModelHairColor(String hex) {
        sdu$modelHairColor = hex == null ? "" : hex;
    }
}
