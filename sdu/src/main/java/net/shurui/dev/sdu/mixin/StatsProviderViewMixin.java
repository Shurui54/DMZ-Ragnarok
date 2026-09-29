package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.stats.StatsData;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.shurui.dev.sdu.client.PlayerInfoView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Read-only /dmzinfo viewer: while the staff member is viewing another player inside DMZ's V menu, make DMZ's
// menu screens read the DETACHED viewed StatsData instead of the local player's. Every V-menu screen funnels
// its data read through this one static accessor (StatsProvider.get -> Entity.getCapability), including the
// ones that read inline without caching (MinigamesScreen, QuestTreeScreen), so this single point covers them
// all at once.
//
// Narrowly gated: registered ONLY in the CLIENT mixin array (server-side StatsProvider.get is never touched),
// fires only while PlayerInfoView.active(), and only for the LOCAL player's lookup, so an other-player lookup
// (e.g. PartyMenuScreen resolving a party member) and every non-menu read fall through to DMZ untouched.
// Viewing ends the moment the staff member leaves DMZ's character screens (PlayerInfoViewEvents), so normal
// behaviour is restored on close and across tab switches.
//
// require = 0 + remap = false (DMZ's own class): a DMZ reshape of get() makes this a no-op and the viewer just
// shows the staff's own data rather than crashing.
@Mixin(value = com.dragonminez.common.stats.StatsProvider.class, remap = false)
public abstract class StatsProviderViewMixin {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "get", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void sdu$viewedStats(Capability cap, Entity entity,
                                        CallbackInfoReturnable<LazyOptional> cir) {
        try {
            if (!PlayerInfoView.active()) {
                return;
            }
            if (PlayerInfoView.syncing()) {
                // DMZ is applying one of its own stat syncs and is about to call StatsData.load on whatever this
                // returns. Hand back the REAL capability: redirected, that write landed in the detached viewed
                // copy and replaced the target's sheet with the staff member's own. See ClientStatsSyncGuardMixin.
                return;
            }
            StatsData viewed = PlayerInfoView.viewed();
            if (viewed == null) {
                return;
            }
            if (entity != Minecraft.getInstance().player) {
                return; // only the local-player lookup is redirected; other players stay real
            }
            cir.setReturnValue(LazyOptional.of(() -> viewed));
        } catch (Throwable ignored) {
            // fall through to DMZ's real capability on any failure
        }
    }
}
