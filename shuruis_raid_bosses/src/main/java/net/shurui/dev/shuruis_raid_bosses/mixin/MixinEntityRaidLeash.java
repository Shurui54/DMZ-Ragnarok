package net.shurui.dev.shuruis_raid_bosses.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Entity;

import net.shurui.dev.shuruis_raid_bosses.raid.RaidManager;

/**
 * Keeps raid enemies inside their arena when they teleport themselves.
 *
 * <p>DragonMineZ's saga AI teleports to its target with {@code Entity.teleportTo(x, y, z)} and no bounds check (wild
 * sense, zanzoken, every combo step). When the target was a fighter just sent home by the raid's death save, the boss
 * followed them out of the arena: into a chunk nobody had loaded, where it stopped resolving and the raid failed a few
 * seconds later, or into a loaded spot where the per-tick confine dragged it back to the arena edge. Refusing the move
 * here, before it happens, is the only point that sees the destination; the confine pass runs after the fact and cannot
 * find an entity that has already left the loaded world.
 *
 * <p>Vanilla target, so it remaps normally. {@code ServerPlayer} overrides this method without calling it, so players
 * never pass through here; for everything that is not a tracked raid enemy the cost is one map lookup.
 */
@Mixin(Entity.class)
public abstract class MixinEntityRaidLeash {

    @Inject(method = "teleportTo(DDD)V", at = @At("HEAD"), cancellable = true)
    private void srb$leashRaidEnemy(double x, double y, double z, CallbackInfo ci) {
        if (RaidManager.refuseEnemyTeleport((Entity) (Object) this, x, y, z)) {
            ci.cancel();
        }
    }
}
