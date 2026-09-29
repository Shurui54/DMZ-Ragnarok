package net.shurui.shuruisutilities.corrupted;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

// self-contained geckolib prop for the corrupted dragonball cinematic. pure spectacle: no ai, invulnerable, not
// collidable/pushable/pickable, no interaction (never opens a gui) and no wish logic of any kind. the cinematic
// in CorruptedEventManager owns its whole lifetime and explicitly removes it, so it never despawns on its own and
// nothing here ever touches the weather. deliberately not a DMZ entity, so none of the old reflective keep-alive
// or KILLED-removal workarounds are needed.
public final class ShadowShenronEntity extends Mob implements GeoEntity
{
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public ShadowShenronEntity(EntityType<? extends Mob> type, Level level)
    {
        super(type, level);
        // floating dragon: ignore gravity so it holds its spawn height above the altar without an ai/float goal
        this.noPhysics = true;
        this.setNoGravity(true);
        // the rendered model towers ~56 blocks tall but the culling box is only the 4x8 hitbox, so vanilla frustum
        // culling (EntityRenderer.shouldRender uses getBoundingBoxForCulling().inflate(0.5)) would pop the whole
        // dragon out of view whenever that small box leaves the frustum (e.g. looking up at its head from the base).
        // never cull it: it is a single always-visible cinematic prop, so skipping culling costs nothing.
        this.noCulling = true;
    }

    public static AttributeSupplier.Builder createAttributes()
    {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1000.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    // no ai goals at all: it is a static prop
    @Override
    protected void registerGoals()
    {
    }

    // never opens a gui, never grants a wish
    @Override
    public net.minecraft.world.InteractionResult mobInteract(Player player, net.minecraft.world.InteractionHand hand)
    {
        return net.minecraft.world.InteractionResult.PASS;
    }

    // immune to everything; the cinematic removes it explicitly
    @Override
    public boolean hurt(DamageSource source, float amount)
    {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source)
    {
        return true;
    }

    @Override
    public boolean isPickable()
    {
        return false;
    }

    @Override
    public boolean canBeCollidedWith()
    {
        return false;
    }

    @Override
    public boolean isPushable()
    {
        return false;
    }

    @Override
    public void push(Entity entity)
    {
    }

    @Override
    protected void doPush(Entity entity)
    {
    }

    // never despawn on its own; lifetime is driven entirely by the cinematic
    @Override
    public boolean removeWhenFarAway(double distance)
    {
        return false;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers)
    {
        controllers.add(new AnimationController<>(this, "controller", 0, this::predicate));
    }

    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state)
    {
        state.getController().setAnimation(RawAnimation.begin().then("idle", Animation.LoopType.LOOP));
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache()
    {
        return cache;
    }
}
