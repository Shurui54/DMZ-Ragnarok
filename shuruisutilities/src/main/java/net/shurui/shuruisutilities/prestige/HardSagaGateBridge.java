package net.shurui.shuruisutilities.prestige;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Installs the prestige check into sdu's {@link net.shurui.dev.sdu.saga.HardSagaGate} at setup, keeping the
 * "sdu imports nothing from shuruisutilities" invariant intact: the dependency arrow points SU to sdu (SU may
 * import sdu), never the reverse. sdu owns the neutral gate and defaults it to allow; SU fills it with the real
 * rule here.
 *
 * <p>{@link #mayUseHard(ServerPlayer)} is the single source of truth for the threshold. The server-side predicate
 * installed here and the per-player boolean sent to the client (see {@link PacketHardSagaGate}, dispatched from
 * {@link PrestigeManager#sendTpMult(ServerPlayer)}) both call it, so the offered option and the accepted option
 * can never diverge. Only the one-time difficulty SELECTION is gated: an already-running hard saga keeps its
 * stored difficulty untouched.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class HardSagaGateBridge
{
    private HardSagaGateBridge() {}

    /**
     * A player may pick HARD once they have prestiged at least once (active-slot prestige level >= 1).
     *
     * <p>Unless prestige is not running here, in which case there is no gate at all. Prestige is not in the
     * public set and is denied to the limited licence too, so on those tiers nobody can ever reach level 1:
     * gating hard difficulty on it there would not make hard difficulty rare, it would delete it, permanently
     * and with no way for the owner to grant it. The operator switchboard can turn prestige off on a full-key
     * server for the same reason. A requirement nobody can satisfy is not a requirement, so where the ladder
     * does not exist the door is simply open, which is exactly how the mod behaved before this gate.
     *
     * <p>"Prestige is running here" is {@code PrestigeHooks.available()} since S19b (the prestige actions live in the
     * Ragnarok Key), not a key check: without the key nobody can prestige, so the door stays open, as it always was
     * keyless.
     */
    public static boolean mayUseHard(ServerPlayer player)
    {
        if (!net.shurui.shuruisutilities.api.key.PrestigeHooks.available()
                || !net.shurui.shuruisutilities.core.config.Features.enabled(
                        net.shurui.shuruisutilities.core.config.Features.PRESTIGE))
        {
            return true;
        }
        return player != null && PrestigeManager.level(player) >= 1;
    }

    @SubscribeEvent
    public static void onSetup(FMLCommonSetupEvent event)
    {
        // enqueueWork so the install lands on the mod thread after both trees have class-loaded, same as other
        // cross-tree wiring in this suite.
        event.enqueueWork(() ->
                net.shurui.dev.sdu.saga.HardSagaGate.setServerEligibility(HardSagaGateBridge::mayUseHard));
    }
}
