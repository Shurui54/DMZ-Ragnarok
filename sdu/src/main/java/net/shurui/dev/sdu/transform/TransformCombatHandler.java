package net.shurui.dev.sdu.transform;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.compat.DmzForms;

// runtime dodge + damage mitigation for transformed NPCs. NPC-side mirror of FormCombatHandler, but keyed off
// the transformed entity's sdu_tf_active NBT (the active TransformForm TransformEngine writes on each swap),
// not DMZ player stats. different data source + only fires for entities with the key, so it never touches the
// player-form buff source sdu_form_rage. onLivingAttack rolls per-category dodge at the start of a hit;
// onLivingHurt mitigates by the form % BEFORE the engine's LivingDamageEvent, so the reduced amount feeds the
// transform trigger check.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TransformCombatHandler {

    private static final String ACTIVE_KEY = "sdu_tf_active";

    private TransformCombatHandler() {
    }

    // auto dodge, rolled at the earliest point of a hit so a dodge deals no damage and no hurt reaction.
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLivingAttack(LivingAttackEvent event) {
        LivingEntity e = event.getEntity();
        if (e.level().isClientSide()) {
            return;
        }
        CompoundTag active = activeForm(e);
        if (active == null) {
            return;
        }
        TransformForm form = TransformForm.fromNbt(active);

        DmzForms.DamageCategory cat = DmzForms.classify(event.getSource());
        double dodge = switch (cat) {
            case PHYSICAL -> form.dodgePhysical;
            case MELEE_SKILL -> form.dodgeMeleeSkill;
            case ENERGY_SKILL -> form.dodgeEnergySkill;
            case OTHER -> 0.0;
        };
        if (dodge <= 0) {
            return;
        }
        RandomSource random = e.getRandom();
        if (random.nextDouble() * 100.0 < dodge) {
            // The victim (which may be an NPC with an active transform) rolled a dodge and the hit is voided.
            event.setCanceled(true);
        }
    }

    // NORMAL priority (before the engine's LivingDamageEvent) so the reduced amount is what the trigger sees.
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        LivingEntity e = event.getEntity();
        if (e.level().isClientSide()) {
            return;
        }
        CompoundTag active = activeForm(e);
        if (active == null) {
            return;
        }
        TransformForm form = TransformForm.fromNbt(active);
        double mitigation = clamp01(form.damageMitigation / 100.0);
        if (mitigation > 0) {
            event.setAmount(Math.max(0f, event.getAmount() * (float) (1.0 - mitigation)));
        }
    }

    // null if the entity carries no sdu_tf_active key.
    private static CompoundTag activeForm(LivingEntity e) {
        if (!e.getPersistentData().contains(ACTIVE_KEY)) {
            return null;
        }
        return e.getPersistentData().getCompound(ACTIVE_KEY);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
