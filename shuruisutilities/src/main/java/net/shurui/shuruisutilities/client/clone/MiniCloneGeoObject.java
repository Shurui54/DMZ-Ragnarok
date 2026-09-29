package net.shurui.shuruisutilities.client.clone;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import net.shurui.shuruisutilities.clone.MiniCloneEntity;

/**
 * A client-only {@link GeoAnimatable} render proxy that lets {@link MiniCloneGeoRenderer} draw a
 * {@link MiniCloneEntity} through GeckoLib without the entity itself implementing {@code GeoAnimatable}. That is the
 * whole point of using {@link software.bernie.geckolib.renderer.GeoReplacedEntityRenderer}: the server entity stays a
 * plain {@code TamableAnimal} (unchanged), and this stand-in supplies the animatable contract GeckoLib needs.
 *
 * <p>It registers ZERO animation controllers on purpose. GeckoLib's {@code GeoModel.handleAnimations} only dereferences
 * the animation resource when a controller actually plays an animation, so with no controllers the clone is a static
 * posed model (positioned and rotated by the replaced-entity renderer from the live entity) and no animation json is
 * ever loaded. DragonMineZ ships the buufat / cell_jr geo but no matching animation file, so this avoids a
 * missing-resource load.
 *
 * <p>One shared instance is reused for every clone. {@link software.bernie.geckolib.renderer.GeoReplacedEntityRenderer}
 * keys each clone's animation manager by the entity's network id, so a shared animatable is the intended pattern (the
 * same way item animatables are shared). {@link #current} is set to the entity being drawn immediately before each
 * render so the model and the tint colour can be resolved from its synced fields; it is read only on the client render
 * thread, which is single threaded.
 */
@OnlyIn(Dist.CLIENT)
public class MiniCloneGeoObject implements GeoAnimatable
{
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /** The entity currently being drawn, set by {@link MiniCloneGeoRenderer} before each render pass. */
    private MiniCloneEntity current;

    void setCurrent(MiniCloneEntity entity)
    {
        this.current = entity;
    }

    MiniCloneEntity getCurrent()
    {
        return this.current;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers)
    {
        // No controllers: the clone is a static posed model. See the class note on why this avoids a missing
        // animation-resource load for DragonMineZ's animation-less buufat / cell_jr geo.
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache()
    {
        return this.cache;
    }

    @Override
    public double getTick(Object object)
    {
        // Only used to advance animations, of which there are none; return the entity age so any Molang time query is
        // still sane rather than frozen at zero.
        if (object instanceof Entity entity)
        {
            return entity.tickCount;
        }
        return 0.0D;
    }
}
