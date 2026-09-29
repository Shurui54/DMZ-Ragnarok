package net.shurui.shuruisutilities.core.mixin.dmz;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.shurui.shuruisutilities.compat.dmz.FormScale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes melee reach scale with a form's size, on BOTH sides of the swing.
 *
 * <p>DMZ decides whether a melee attack connects in {@code TargetHelper.isLookingAt}, which is, in effect:</p>
 *
 * <pre>
 *   AABB box = target.getBoundingBox().inflate(0.15);
 *   return box.clip(eye, eye.add(look.scale(range))).isPresent();
 * </pre>
 *
 * <p>So reach is measured from the ATTACKER'S EYE to the TARGET'S BOUNDING BOX. DMZ sizes a form with
 * {@code modelScaling}, which grows the model and leaves the box alone, and those two facts together are the
 * bug: against a giant you had to reach a normal-sized box buried at their centre, so you had to stand inside
 * their model to land anything, while their own eye sat high and far forward and reached you from outside
 * yours. One side got longer reach, the other got harder to hit, from a single unchanged hitbox.</p>
 *
 * <p>Both redirects apply the SAME per-entity bonus from {@link FormScale#reachBonus}, which is why this is
 * symmetric rather than a buff: a form that reaches two blocks further can also be hit from two blocks
 * further out.</p>
 *
 * <ul>
 *   <li>the target's box is inflated by the TARGET's bonus, so a big body is hittable across its whole size;</li>
 *   <li>the ray is lengthened by the ATTACKER's bonus, so a big attacker's reach matches their frame.</li>
 * </ul>
 *
 * <p>Both handlers take the enclosing method's parameters after the redirected call's own, which is how they
 * know which entity is which. {@code require = 0}: if DMZ reshapes this method the injectors no-op and combat
 * reverts to DMZ's stock behaviour rather than crashing. The DMZ method name is literal ({@code remap = false});
 * the vanilla {@code AABB}/{@code Vec3} targets carry {@code remap = true} so they refmap in production.</p>
 */
@Mixin(targets = "com.dragonminez.common.combat.logic.player.TargetHelper", remap = false)
public abstract class MixinDmzSizedReach
{
    @Redirect(
            method = "isLookingAt",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/AABB;inflate(D)Lnet/minecraft/world/phys/AABB;",
                    remap = true),
            require = 0,
            remap = false
    )
    private static AABB su$growTargetBox(AABB box, double amount, Player attacker, Entity target, double range)
    {
        return box.inflate(amount + FormScale.reachBonus(target));
    }

    @Redirect(
            method = "isLookingAt",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;scale(D)Lnet/minecraft/world/phys/Vec3;",
                    remap = true),
            require = 0,
            remap = false
    )
    private static Vec3 su$lengthenReach(Vec3 look, double length, Player attacker, Entity target, double range)
    {
        return look.scale(length + FormScale.reachBonus(attacker));
    }
}
