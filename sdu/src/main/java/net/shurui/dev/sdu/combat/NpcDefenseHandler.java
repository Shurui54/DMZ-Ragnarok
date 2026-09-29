package net.shurui.dev.sdu.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// the one Forge-bus handler applying DMZ's resistance-mitigation curve to MOB victims (DMZ's own curve is
// player-only, so curve-managed fighters would take raw damage). read + mitigation live ONLY here; writers
// elsewhere just set NpcDefense.KEY. LOWEST so we mitigate the FINAL amount, after DMZ's own LOWEST
// player-mitigation handler (which early-returns on non-players, leaving the amount as the DMZ raw on our
// ARMOR=0 fighters, one mitigation stage).
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class NpcDefenseHandler {

    private NpcDefenseHandler() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onMobDamage(LivingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        // cheap early-outs in cost order, no allocation before the key check.
        if (victim instanceof Player || victim.level().isClientSide()) {
            return;
        }
        if (!victim.getPersistentData().contains(NpcDefense.KEY)) {
            return;
        }
        double defense = victim.getPersistentData().getDouble(NpcDefense.KEY);
        if (!(defense > 0.0)) {
            return;
        }

        // for an ARMOR=0 curve mob, getAmount() at LOWEST IS the DMZ raw damage: DMZ sets amount=raw, its LOWEST
        // player-mitigation early-returns on non-players, and the mob branch already folded penetration in, so we
        // pass armorPenetration=0 (don't re-apply).
        double raw = event.getAmount();
        double mitigated = NpcDefenseCurve.postMitigation(raw, defense);
        event.setAmount((float) Math.max(0.0, mitigated));
    }
}
