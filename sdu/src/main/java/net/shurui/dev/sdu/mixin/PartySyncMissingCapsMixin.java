package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.ProgressionSyncS2C;
import com.dragonminez.common.quest.PartyManager;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.server.world.data.PartySavedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzForms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.UUID;

// Keeps a party sync from taking the whole server down when one member has no stats capability.
//
// DMZ's PartyManager.syncPartyToOnlineMembers calls getQuestData on every online member, and getQuestData THROWS an
// IllegalStateException when the capability is absent. The worst caller is onPlayerLogout -> transferLeadership: the
// leaving leader is still in the player list while PlayerLoggedOutEvent fires, so they are iterated too, and a player
// whose capability is already gone (it happened on OW1 on 2026-09-14 13:19 to a leader who timed out ten seconds after
// being killed and sent to the Otherworld) makes the event throw out of the network tick. That is "Exception in server
// tick loop": the shard crashes for everyone.
//
// This runs DMZ's loop itself, unchanged for every member that has the capability, and skips the one that does not,
// so leadership still transfers and every other member is still told about it. require = 0 so a DMZ change degrades
// to DMZ's own behaviour, and any unexpected failure here falls through to DMZ's method rather than hiding it.
@Mixin(value = PartyManager.class, remap = false)
public class PartySyncMissingCapsMixin {

    @Inject(method = "syncPartyToOnlineMembers(Lnet/minecraft/server/MinecraftServer;Lcom/dragonminez/server/world/data/PartySavedData$PartyInstance;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void sdu$skipMembersWithoutStats(MinecraftServer server, PartySavedData.PartyInstance party, CallbackInfo ci) {
        try {
            if (server == null || party == null) {
                return;
            }
            List<UUID> memberIds = party.getMembers();
            for (UUID id : memberIds) {
                ServerPlayer member = server.getPlayerList().getPlayer(id);
                if (member == null) {
                    continue;
                }
                StatsData data = DmzForms.stats(member);
                if (data == null) {
                    DmzNpc.LOGGER.warn("[{}] Skipped the party sync for {}: they have no DMZ stats (usually a player mid-logout).",
                            DmzNpc.MODID, member.getGameProfile().getName());
                    continue;
                }
                data.getPlayerQuestData().setPartyState(party.getPartyId(), party.getLeaderId(), memberIds, party.isPvpEnabled());
                NetworkHandler.sendToPlayer(new ProgressionSyncS2C(member), member);
            }
            ci.cancel();
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] party sync guard fell through to DMZ: {}", DmzNpc.MODID, t.toString());
        }
    }
}
