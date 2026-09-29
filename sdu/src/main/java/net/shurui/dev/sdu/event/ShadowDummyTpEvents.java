package net.shurui.dev.sdu.event;

import com.dragonminez.common.events.DMZEvent;
import com.dragonminez.common.quest.PartyManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.dummy.ShadowDummyLimits;

/**
 * RULE 3: a player who is in a party gets NO TP from killing a shadow / training dummy (anti
 * party-farm). Solo players keep DMZ's normal shadow TP.
 *
 * <p>Mechanism, mirroring sdu's existing ThreadLocal TP pattern (see {@code TokenBuffCarryOver} and the
 * {@code IncreaseStatC2SMixin} buyer ThreadLocal): DMZ awards the shadow-dummy TP inside its own
 * {@code TPGainEvents.onEntityDeath} (default NORMAL priority), which fires a {@link
 * DMZEvent.TPGainEvent} on the killer. We bracket that:
 * <ol>
 *   <li>{@link #onDeathSetFlag} at HIGHEST (before DMZ's NORMAL death handler) detects "party member
 *       killed a player shadow dummy" and arms {@link ShadowDummyLimits#SUPPRESS_SHADOW_TP}.</li>
 *   <li>{@link #onTpGainSuppress} on the fired TPGainEvent at LOWEST (after DMZ's HIGH onTPGain and
 *       SU/token modifiers) reads the flag and zeroes the gain.</li>
 *   <li>{@link #onDeathClearFlag} at LOWEST (after DMZ granted TP) clears the flag so it never leaks
 *       past this one kill.</li>
 * </ol>
 *
 * <p>We suppress the ENTIRE TP from the shadow kill for party members, not just DMZ's shadow bonus:
 * that is the anti party-farm intent. If only the bonus should be removed, this is the single place to
 * change. Everything is guarded so a failure falls through to DMZ default (normal TP).
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class ShadowDummyTpEvents {

    private ShadowDummyTpEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeathSetFlag(LivingDeathEvent event) {
        // Default to disarmed for this thread; we only arm below when all conditions hold.
        ShadowDummyLimits.SUPPRESS_SHADOW_TP.set(Boolean.FALSE);
        try {
            Entity dead = event.getEntity();
            if (dead == null || dead.level().isClientSide()) {
                return;
            }
            if (!ShadowDummyLimits.isPlayerShadowDummy(dead)) {
                return;
            }
            if (!(event.getSource().getEntity() instanceof ServerPlayer killer)) {
                return;
            }
            // Only party members lose the TP; solo players keep DMZ's normal shadow TP.
            if (PartyManager.isInParty(killer)) {
                ShadowDummyLimits.SUPPRESS_SHADOW_TP.set(Boolean.TRUE);
            }
        } catch (Throwable t) {
            // On any failure, leave the flag disarmed so DMZ awards TP as normal.
            ShadowDummyLimits.SUPPRESS_SHADOW_TP.set(Boolean.FALSE);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTpGainSuppress(DMZEvent.TPGainEvent event) {
        try {
            if (ShadowDummyLimits.SUPPRESS_SHADOW_TP.get()) {
                event.setTpGain(0);
            }
        } catch (Throwable t) {
            // Never break TP gain: leave the value as DMZ / earlier handlers set it.
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeathClearFlag(LivingDeathEvent event) {
        // Runs after DMZ's NORMAL death handler has fired the TPGainEvent, so the flag has done its
        // job. Clear it so it cannot leak into an unrelated later kill on this thread.
        ShadowDummyLimits.SUPPRESS_SHADOW_TP.set(Boolean.FALSE);
    }
}
