package net.shurui.dev.sdu.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.dev.sdu.diagnostics.EntityChurnWatch;

/**
 * Counts every server-side {@link Entity} construction, to find what is churning entities on the open-world shards.
 *
 * <h2>Why a constructor injection and not an event</h2>
 * There is no Forge event on the {@code new Entity(...)} path that fires for EVERY construction: {@code EntityJoinLevelEvent}
 * fires only for the ones that actually enter a level, so an entity that is built and thrown away (the shape a churn
 * bug takes: 10k-57k {@code Entity} objects a second while few of them ever join) never shows up there. The base
 * {@code Entity} constructor is the one common point every entity, joined or discarded, passes through exactly once, so
 * that is where the count has to be taken. This mirrors {@code Entity.ENTITY_COUNTER}, which is bumped in that same
 * constructor, so the numbers here are directly comparable to the counter the report is phrased against.
 *
 * <h2>Cost discipline</h2>
 * The whole body is behind a single {@code static volatile boolean} read ({@link EntityChurnWatch#ENABLED}). When the
 * instrument is off, or has disabled itself after a failure, this is one {@code getstatic} and a return: nothing is
 * allocated and no map is touched. All of the real work (the class tally, the throttled stack sample) lives in
 * {@link EntityChurnWatch#record}, off this class, so the injected bytecode stays tiny.
 *
 * <p>Official mappings, so the constructor is targeted by its Mojang descriptor and {@code remap} stays default-true,
 * exactly as {@code RangedAttributeMixin} targets {@code sanitizeValue}. The handler takes the constructor's parameters
 * verbatim plus a {@link CallbackInfo}: all of them or none, never a subset.
 */
@Mixin(Entity.class)
public class EntityChurnMixin {

    @Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V",
            at = @At("RETURN"))
    private void sdu$countConstruction(EntityType<?> entityType, Level level, CallbackInfo ci) {
        // Single static boolean on the hot path: off means one getstatic and out, no allocation, no map access.
        if (!EntityChurnWatch.ENABLED)
            return;
        // Server side only. A null level happens for a handful of synthetic entities during bootstrap; skip those too
        // rather than risk an NPE on the render-free path.
        if (level == null || level.isClientSide())
            return;
        EntityChurnWatch.record((Entity) (Object) this);
    }
}
