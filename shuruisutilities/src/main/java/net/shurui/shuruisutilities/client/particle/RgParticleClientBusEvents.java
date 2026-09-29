package net.shurui.shuruisutilities.client.particle;

import net.minecraft.client.particle.ParticleRenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.particle.RgParticles;

/**
 * Client-only, mod-bus registration of the providers for the two tinted particle types.
 *
 * <p>The TYPES are common and register through {@code RgParticles.REGISTER} on both sides; only the thing that
 * turns options into a drawn particle is client side, and this is the only event Forge offers for it.
 *
 * <p>{@code registerSpriteSet} is the right call of the three: it binds a {@link net.minecraft.client.particle.SpriteSet}
 * from the particle description JSON, which is what puts the sprite on the particle atlas. It also means the
 * description file is NOT optional. Without {@code assets/dmz_ragnarok/particles/tinted_dust.json} the sprite set
 * for that id is never rebound during a resource reload and the first particle spawned dereferences a null sprite
 * list. The failure is a client side NPE out of the particle engine, not a missing texture, so the JSON is load
 * bearing.
 *
 * <p>The annotation is deliberately BARE: no {@code modid}. Everything in this tree now ships inside the merged
 * {@code dmz_ragnarok} container, so Forge's default (the owning container's id) is the only correct value, and a
 * hand written one that no longer matches is skipped in silence.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RgParticleClientBusEvents
{
    private RgParticleClientBusEvents()
    {
    }

    @SubscribeEvent
    public static void registerProviders(RegisterParticleProvidersEvent event)
    {
        event.registerSpriteSet(RgParticles.TINTED_DUST.get(),
                sprites -> new TintedParticle.Provider(sprites, ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT, false));
        event.registerSpriteSet(RgParticles.TINTED_GLOW.get(),
                sprites -> new TintedParticle.Provider(sprites, RgParticleRenderTypes.ADDITIVE, true));
    }
}
