package net.shurui.shuruisutilities.character;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Re-asserts each character's above-cap /dmzstats overrides after DMZ re-clamps. DMZ re-clamps at three points:
 * <ul>
 *   <li>PlayerLoggedInEvent: StorageManager.loadPlayer clamps on login. Deferred to end of tick (server.execute)
 *       so it runs after every login handler (DMZ's included), regardless of ordering.</li>
 *   <li>Clone: respawn / return-from-End copies via copyFrom (clamps). Re-assert on the fresh player.</li>
 *   <li>PlayerChangedDimensionEvent: DMZ resyncs (may re-clamp) on dim change.</li>
 * </ul>
 * Slot switch re-asserts inline in CharacterSlots.switchTo, so it's not handled here. Server-side only.
 */
public class StatCapReassertHandler
{
    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer sp && sp.server != null)
            sp.server.execute(() -> StatCapOverrides.reassert(sp));
    }

    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event)
    {
        // only the finished clone matters; the wound-down original is irrelevant
        if (event.getEntity() instanceof ServerPlayer sp && sp.server != null)
            sp.server.execute(() -> StatCapOverrides.reassert(sp));
    }

    @SubscribeEvent
    public void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer sp && sp.server != null)
            sp.server.execute(() -> StatCapOverrides.reassert(sp));
    }
}
