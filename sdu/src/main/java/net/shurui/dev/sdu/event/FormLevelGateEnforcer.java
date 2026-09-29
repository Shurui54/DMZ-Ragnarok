package net.shurui.dev.sdu.event;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.AppearanceSyncS2C;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Character;
import com.dragonminez.common.util.TransformationsHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzForms;
import net.shurui.dev.sdu.form.FormLevelGate;
import net.shurui.dev.sdu.form.FormLevelGateConfig;

/**
 * The watertight backbone of the per-form minimum-level gate: a server-tick safety net that DROPS a player out
 * of any form whose configured minimum level is above their character level. The transform-request mixins
 * ({@code FormModeHandlerMixin} / {@code StackFormModeHandlerMixin}) stop a NEW transform with a clear message;
 * this catches everything else, whichever path put them in the form:
 *
 * <ul>
 *   <li>a player already in a form when an operator RAISES its minimum (the safer choice: drop them, per the owner);</li>
 *   <li>any grant path that sets an active form directly (admin tools, the SSG ritual, an addon);</li>
 *   <li>a form entered on a shard with a lower minimum, then carried to one with a higher minimum.</li>
 * </ul>
 *
 * <p>The SSG charge ritual RESPECTS the gate rather than bypassing it: its temporary grant sets the real
 * {@code godforms.supersaiyangod} active form, so if an operator gates god forms above a participant's level
 * this drops them too. That is consistent with the owner's rule ("cannot transform below the level"), and it
 * interoperates cleanly, because {@code SsgRitualManager} sees the form gone and finalizes its own teardown on
 * the next tick, so nothing is stranded. (sdu also cannot import the ritual, which lives in shuruisutilities;
 * respecting the gate needs no such reference.) To make the ritual override the gate instead, an operator sets
 * that form's minimum to 0.
 *
 * <p>Cheap by construction: it short-circuits instantly when no minimums are configured, runs only on the
 * server END phase, and only every {@link #INTERVAL_TICKS} ticks.
 */
@Mod.EventBusSubscriber(modid = DmzNpc.MODID)
public final class FormLevelGateEnforcer {

    /** How often to re-check, in ticks. One second: a transform is already blocked at request time, this is a net. */
    private static final int INTERVAL_TICKS = 20;

    private static int timer;

    private FormLevelGateEnforcer() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++timer < INTERVAL_TICKS) {
            return;
        }
        timer = 0;
        // Nothing configured: do no work at all (the common case on a fresh or ungated server).
        if (FormLevelGateConfig.all().isEmpty()) {
            return;
        }
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                enforce(player);
            } catch (Throwable t) {
                DmzNpc.LOGGER.debug("[{}] Form-level enforce failed for {}: {}",
                        DmzNpc.MODID, player.getGameProfile().getName(), t.toString());
            }
        }
    }

    private static void enforce(ServerPlayer player) {
        StatsData data = DmzForms.stats(player);
        if (data == null) {
            return;
        }
        Character c = data.getCharacter();
        if (c == null) {
            return;
        }
        boolean changed = false;

        // Base form.
        if (c.hasActiveForm()) {
            String group = c.getActiveFormGroup();
            String form = c.getActiveForm();
            if (FormLevelGate.blocks(data, group, form)) {
                TransformationsHelper.revertToBaseForm(player, data);
                FormLevelGate.notifyBlocked(data, group, form, false);
                changed = true;
            }
        }

        // Stack form (kaioken / ultimate).
        if (c.hasActiveStackForm()) {
            String group = c.getActiveStackFormGroup();
            String form = c.getActiveStackForm();
            if (FormLevelGate.blocks(data, group, form)) {
                c.clearActiveStackForm(player);
                FormLevelGate.notifyBlocked(data, group, form, true);
                changed = true;
            }
        }

        if (changed) {
            player.refreshDimensions();
            NetworkHandler.sendToTrackingEntityAndSelf(new StatsSyncS2C(player), player);
            NetworkHandler.sendToTrackingEntityAndSelf(new AppearanceSyncS2C(player), player);
        }
    }
}
