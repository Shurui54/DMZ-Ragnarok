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
import net.shurui.dev.sdu.form.FormAlignmentGate;
import net.shurui.dev.sdu.form.FormAlignmentGateConfig;

/**
 * The watertight backbone of the per-form ALIGNMENT gate, the twin of {@link FormLevelGateEnforcer}: a server-tick
 * safety net that DROPS a player out of any form whose configured USE window excludes their current DMZ alignment.
 * The transform-request mixins ({@code FormModeHandlerMixin} / {@code StackFormModeHandlerMixin}) stop a NEW transform
 * with a clear message; this catches everything else:
 *
 * <ul>
 *   <li>a player already in a form when their alignment SHIFTS out of the window (the point of a "stay in" gate);</li>
 *   <li>an operator narrowing a form's window while players are in it;</li>
 *   <li>any grant path that sets an active form directly (admin tools, the SSG ritual, an addon).</li>
 * </ul>
 *
 * <p>Uses DMZ's own detransform path ({@code TransformationsHelper.revertToBaseForm} for base forms,
 * {@code Character.clearActiveStackForm} for stack forms) exactly as the level enforcer does, then refreshes
 * dimensions and re-syncs stats + appearance, so the player is left cleanly in their base form, never stranded.
 *
 * <p>Cheap by construction: it short-circuits instantly when no alignment gates are configured, runs only on the
 * server END phase, and only every {@link #INTERVAL_TICKS} ticks. Alignment changes rarely (a command or a quest
 * reward), so a one-second net is ample and there is no per-change hook to wire.
 */
@Mod.EventBusSubscriber(modid = DmzNpc.MODID)
public final class FormAlignmentGateEnforcer {

    /** How often to re-check, in ticks. One second: a transform is already blocked at request time, this is a net. */
    private static final int INTERVAL_TICKS = 20;

    private static int timer;

    private FormAlignmentGateEnforcer() {
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
        if (FormAlignmentGateConfig.all().isEmpty()) {
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
                DmzNpc.LOGGER.debug("[{}] Form-alignment enforce failed for {}: {}",
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
            if (FormAlignmentGate.blocksUse(data, group, form)) {
                TransformationsHelper.revertToBaseForm(player, data);
                FormAlignmentGate.notifyUseBlocked(data, group, form, false);
                changed = true;
            }
        }

        // Stack form (kaioken / ultimate).
        if (c.hasActiveStackForm()) {
            String group = c.getActiveStackFormGroup();
            String form = c.getActiveStackForm();
            if (FormAlignmentGate.blocksUse(data, group, form)) {
                c.clearActiveStackForm(player);
                FormAlignmentGate.notifyUseBlocked(data, group, form, true);
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
