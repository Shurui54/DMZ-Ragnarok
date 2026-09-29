package net.shurui.dev.sdu.mixin;

import net.minecraft.nbt.CompoundTag;
import net.shurui.dev.sdu.client.PlayerInfoView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Read-only /dmzinfo viewer, half two: keep DragonMineZ's own sync path away from the detached viewed data.
//
// StatsProviderViewMixin redirects StatsProvider.get for the LOCAL player so DMZ's V-menu screens read the viewed
// player's sheet. DMZ's ClientPacketHandler.handleStatsSyncPacket resolves through that SAME static method and then
// calls StatsData.load(nbt) on the result, so redirected it wrote the STAFF MEMBER's nbt into the viewed copy, and
// the viewer turned into a mirror of the viewer's own sheet. All four S2C syncs (ResourceSyncS2C, StatsSyncS2C,
// ProgressionSyncS2C, AppearanceSyncS2C) funnel into this one method, and ResourceSyncS2C fires constantly, which
// is why the target's sheet was visible for roughly one frame.
//
// This brackets that method with a flag instead of cancelling it: the local player's real capability still receives
// every sync, so the staff member's own data is never stale, and only the redirect is suspended. Forge's
// enqueueWork puts this on the client thread, the same thread the screens read on.
//
// Parameters are the target's exactly (int playerId, CompoundTag nbt) plus the callback; remap = false because
// handleStatsSyncPacket is DMZ's own method, require = 0 so a DMZ reshape degrades to the old behaviour rather
// than an apply-phase crash.
@Mixin(value = com.dragonminez.common.network.ClientPacketHandler.class, remap = false)
public abstract class ClientStatsSyncGuardMixin {

    @Inject(method = "handleStatsSyncPacket(ILnet/minecraft/nbt/CompoundTag;)V",
            at = @At("HEAD"), require = 0, remap = false)
    private static void sdu$syncBegin(int playerId, CompoundTag nbt, CallbackInfo ci) {
        PlayerInfoView.syncing(true);
    }

    @Inject(method = "handleStatsSyncPacket(ILnet/minecraft/nbt/CompoundTag;)V",
            at = @At("RETURN"), require = 0, remap = false)
    private static void sdu$syncEnd(int playerId, CompoundTag nbt, CallbackInfo ci) {
        PlayerInfoView.syncing(false);
    }
}
