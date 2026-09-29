package net.shurui.shuruisutilities.cosmetics.wardrobe.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;
import software.bernie.geckolib.util.RenderUtils;

/**
 * A client-only, NON-ENTITY {@link GeoAnimatable} render proxy for the triggered cosmetic-animation rigs. This is the
 * "no entity at all" design: {@link CosmeticAnimGeoRenderer} (a {@code GeoObjectRenderer}) draws the rig at a bare
 * world position, and this stand-in supplies the animatable contract GeckoLib needs without any entity being spawned,
 * ticked, or added to the client level.
 *
 * <h2>One shared instance, many effects, one manager each</h2>
 * A single {@link #INSTANCE} is reused for every playing rig. GeckoLib keys each effect's animation state by the
 * INSTANCE ID, so {@link CosmeticAnimGeoRenderer} overrides {@code getInstanceId} to return {@link #instanceId()},
 * a ring-allocated id assigned per effect by {@link CosmeticAnimationClientStore}. That gives each concurrent rig its
 * own animation timeline from one shared animatable, the same pattern GeckoLib uses for item animatables. The ring
 * bounds the instance cache so it cannot grow without limit over a long session.
 *
 * <h2>Play once, from the moment it starts</h2>
 * The single controller registers {@code actived} as a TRIGGERABLE animation and otherwise stops. When an effect
 * first renders, the store triggers {@code actived} for that instance id, so it plays from frame zero right then and
 * holds the last frame; the store retires the effect at the clip's real length, so the hold is never seen. Because
 * the render thread reads {@link RenderUtils#getCurrentTick()} for a non-entity animatable, the clock is the global
 * game tick and each triggered clip advances from its own trigger moment.
 */
@OnlyIn(Dist.CLIENT)
public final class CosmeticAnimAnimatable implements GeoAnimatable
{
    public static final CosmeticAnimAnimatable INSTANCE = new CosmeticAnimAnimatable();

    /** Play once and hold the last frame; the store removes the effect at the clip's real length. */
    static final RawAnimation ACTIVED = RawAnimation.begin().thenPlayAndHold("actived");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private String rigKey = "";

    private boolean arriving = true;

    private long instanceId;

    private CosmeticAnimAnimatable()
    {
    }

    /** Bind the effect being drawn this pass. Read only on the render thread, which is single threaded. */
    void set(String rigKey, boolean arriving, long instanceId)
    {
        this.rigKey = rigKey == null ? "" : rigKey;
        this.arriving = arriving;
        this.instanceId = instanceId;
    }

    String rigKey()
    {
        return this.rigKey;
    }

    boolean arriving()
    {
        return this.arriving;
    }

    long instanceId()
    {
        return this.instanceId;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers)
    {
        // No transition (0), no default animation (STOP); actived is triggered per instance id when the effect starts.
        controllers.add(new AnimationController<>(this, "fx", 0, state -> PlayState.STOP)
                .triggerableAnim("actived", ACTIVED));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache()
    {
        return this.cache;
    }

    @Override
    public double getTick(Object object)
    {
        // Non-entity: the global game tick, so a triggered clip advances from its own trigger moment.
        return RenderUtils.getCurrentTick();
    }
}
