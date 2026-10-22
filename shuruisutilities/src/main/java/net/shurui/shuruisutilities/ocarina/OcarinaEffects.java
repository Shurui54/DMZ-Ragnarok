package net.shurui.shuruisutilities.ocarina;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The ocarina's own status effects. Registered in core (a registry must match on both ends of a connection); only the
 * Ragnarok Key's songs apply them. The amplifier is the song's skill level minus one, so the HUD reads
 * "Ocarina Drain V" for a level 5 drain.
 *
 * <p>The vanilla parts (the drain's slow and damage cut, the seal's root) are attribute modifiers sized from
 * {@link OcarinaTuning} and work by themselves. The DragonMineZ parts (stat multipliers, ki regen, calmed mobs) are
 * driven by the key while the effect is on, so a keyless server that loads a saved effect only shows the pip until
 * it runs out.
 */
public final class OcarinaEffects
{
    private OcarinaEffects() {}

    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, ShuruisUtilities.MODID);

    // Fixed ids so a modifier can never stack with itself across a relog.
    private static final String DRAIN_SLOW = "5f0d6c2e-6a51-4c5f-9b0e-0a9e7c1d4b21";
    private static final String DRAIN_DAMAGE = "8a2b4e7f-1c3d-4e5a-8f6b-2d1c0e9f7a32";
    private static final String SEAL_ROOT = "3c9e1f5a-7b2d-4a6c-9e8f-1b0a2c3d4e43";

    public static final RegistryObject<MobEffect> BOOST = EFFECTS.register("ocarina_boost",
            () -> new OcarinaEffect(Kind.BOOST, MobEffectCategory.BENEFICIAL, 0xF0A93C));
    public static final RegistryObject<MobEffect> DRAIN = EFFECTS.register("ocarina_drain",
            () -> new OcarinaEffect(Kind.DRAIN, MobEffectCategory.HARMFUL, 0x7A3FB0)
                    .addAttributeModifier(Attributes.MOVEMENT_SPEED, DRAIN_SLOW, -0.1D,
                            AttributeModifier.Operation.MULTIPLY_TOTAL)
                    .addAttributeModifier(Attributes.ATTACK_DAMAGE, DRAIN_DAMAGE, -0.025D,
                            AttributeModifier.Operation.MULTIPLY_TOTAL));
    public static final RegistryObject<MobEffect> SEAL = EFFECTS.register("ocarina_seal",
            () -> new OcarinaEffect(Kind.SEAL, MobEffectCategory.HARMFUL, 0xC9B26B)
                    .addAttributeModifier(Attributes.MOVEMENT_SPEED, SEAL_ROOT, -1.0D,
                            AttributeModifier.Operation.MULTIPLY_TOTAL));
    public static final RegistryObject<MobEffect> CALM = EFFECTS.register("ocarina_calm",
            () -> new OcarinaEffect(Kind.CALM, MobEffectCategory.NEUTRAL, 0x7FB8E6));
    public static final RegistryObject<MobEffect> RESONANCE = EFFECTS.register("ocarina_resonance",
            () -> new OcarinaEffect(Kind.RESONANCE, MobEffectCategory.BENEFICIAL, 0x4FD6C9));

    public enum Kind { BOOST, DRAIN, SEAL, CALM, RESONANCE }

    /** One effect. Never cured by milk: a song's effect ends when its time is up. */
    public static final class OcarinaEffect extends MobEffect
    {
        private final Kind kind;

        OcarinaEffect(Kind kind, MobEffectCategory category, int color)
        {
            super(category, color);
            this.kind = kind;
        }

        public Kind kind()
        {
            return kind;
        }

        /** The drain's slow and damage cut follow the song's level, not vanilla's "x per amplifier" rule. */
        @Override
        public double getAttributeModifierValue(int amplifier, AttributeModifier modifier)
        {
            if (kind == Kind.DRAIN)
            {
                OcarinaTuning.Song s = OcarinaTuning.song(OcarinaSong.DISCORD);
                int level = amplifier + 1;
                if (modifier.getId().toString().equals(DRAIN_SLOW))
                    return -Math.max(0.0D, Math.min(95.0D, s.secondary(level))) / 100.0D;
                return -Math.max(0.0D, Math.min(95.0D, s.power(level))) / 100.0D;
            }
            if (kind == Kind.SEAL)
                return -1.0D;
            return super.getAttributeModifierValue(amplifier, modifier);
        }

        @Override
        public boolean isDurationEffectTick(int duration, int amplifier)
        {
            return false; // the key drives the DragonMineZ side once a second; nothing per tick here
        }

        @Override
        public void applyEffectTick(LivingEntity entity, int amplifier)
        {
        }

        @Override
        public java.util.List<net.minecraft.world.item.ItemStack> getCurativeItems()
        {
            return java.util.List.of();
        }
    }

    /**
     * A sealed creature cannot jump out of the seal. Runs on both sides because a player's jump is predicted on their
     * own client (the effect is synced to its owner).
     */
    @Mod.EventBusSubscriber(modid = "dmz_ragnarok")
    public static final class Handlers
    {
        private Handlers() {}

        @SubscribeEvent
        public static void onJump(LivingEvent.LivingJumpEvent event)
        {
            LivingEntity e = event.getEntity();
            if (e.hasEffect(SEAL.get()))
            {
                Vec3 v = e.getDeltaMovement();
                e.setDeltaMovement(0.0D, Math.min(0.0D, v.y), 0.0D);
            }
        }
    }
}
