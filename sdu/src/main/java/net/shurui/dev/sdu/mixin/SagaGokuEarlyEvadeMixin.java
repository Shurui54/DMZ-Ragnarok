package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops the Saiyan-Saga training Goku fleeing (bug 699). DMZ's {@code SagaGokuEarlyEntity} constructor turns on
 * evade for the {@code saga_goku_early_noweights} variant ({@code setEvade(true, 100)}), which is the exact NPC the
 * "Training: Goku!" quest spawns. Evade makes the entity dash away every time it is hurt, so on its slow ground
 * speed it just runs from the player instead of sparring. The weighted {@code saga_goku_early} never sets evade.
 *
 * <p>We clear evade at the end of the constructor, so every per-player spawn of this training Goku fights in place.
 * Nothing else uses {@code SagaGokuEarlyEntity}, and the weighted variant already has evade off, so this only ever
 * turns off the one flag that caused the flee.
 *
 * <p>{@code remap = false}: the target is a DMZ class with official names in the prod jar. {@code require = 0} so a
 * DMZ reshape no-ops instead of crashing; a green build does not prove the binding, so launch-test it.
 */
@Mixin(targets = "com.dragonminez.common.init.entities.sagas.SagaGokuEntity$SagaGokuEarlyEntity", remap = false)
public abstract class SagaGokuEarlyEvadeMixin {

    @Inject(method = "<init>", at = @At("TAIL"), require = 0, remap = false)
    private void sdu$disableTrainingGokuEvade(CallbackInfo ci) {
        try {
            ((DBSagasEntity) (Object) this).setEvade(false, 0);
        } catch (Throwable ignored) {
            // if DMZ reshapes the entity, leave its own behaviour in place
        }
    }
}
