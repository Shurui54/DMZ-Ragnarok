package net.shurui.dev.sdu.quest;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.quest.PartyManager;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzForms;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps every party within DMZ's configured level gap ({@code partyMaxLevelGap} in general-server.json), between ALL
 * members rather than only against the leader.
 *
 * <p>DMZ's own check has two holes. {@code acceptInvite} resolves the "leader" as the invitee themselves when the
 * invitee is not yet in a party, which is always the case when accepting, so the accept-time check compares the
 * player with themselves and always passes. And {@code requestInvite} only compares the invitee with the leader, so a
 * party can span twice the gap, and nothing ever re-checks a party whose members level apart after joining, or one
 * rebuilt on another shard. {@code PartyLevelGapMixin} closes the two join paths through {@link #violates}; the sweep
 * below removes members from parties that already break the rule.
 *
 * <p>Only players online on this server are compared, which is also all DMZ's party features ever act on
 * ({@code getAllPartyMembers} returns local players only). Fused players are left alone: fusion builds its own
 * temporary party and undoes it when the fusion ends. A gap of -1 disables all of this, as it does in DMZ.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class PartyLevelGap {

    /** Sweep cadence in ticks (10 s). Cheap: one capability read per partied player. */
    private static final int SWEEP_TICKS = 200;

    private static int tick;

    private PartyLevelGap() {
    }

    /** DMZ's configured gap, or -1 when the rule is off (or the config cannot be read). */
    public static int maxGap() {
        try {
            Integer gap = ConfigManager.getServerConfig().getGameplay().getPartyMaxLevelGap();
            return gap == null ? -1 : gap;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** True when adding {@code joiner} would put them more than the gap away from any of {@code members}. */
    public static boolean violates(ServerPlayer joiner, List<ServerPlayer> members) {
        int gap = maxGap();
        if (gap < 0 || joiner == null) {
            return false;
        }
        StatsData joinerStats = DmzForms.stats(joiner);
        if (joinerStats == null) {
            return false; // cannot tell: leave it to DMZ's own checks
        }
        int level = joinerStats.getLevel();
        for (ServerPlayer member : members) {
            if (member == null || member.getUUID().equals(joiner.getUUID())) {
                continue;
            }
            StatsData stats = DmzForms.stats(member);
            if (stats != null && Math.abs(stats.getLevel() - level) > gap) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++tick % SWEEP_TICKS != 0) {
            return;
        }
        int gap = maxGap();
        MinecraftServer server = event.getServer();
        if (gap < 0 || server == null) {
            return;
        }
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            if (seen.contains(player.getUUID())) {
                continue;
            }
            try {
                if (!PartyManager.isInParty(player)) {
                    continue;
                }
                List<ServerPlayer> members = PartyManager.getAllPartyMembers(player);
                for (ServerPlayer m : members) {
                    seen.add(m.getUUID());
                }
                seen.add(player.getUUID());
                enforce(server, player, members, gap);
            } catch (Throwable t) {
                DmzNpc.LOGGER.debug("[{}] party level gap sweep failed for {}: {}", DmzNpc.MODID,
                        player.getGameProfile().getName(), t.toString());
            }
        }
    }

    /**
     * Removes members until everyone left is within the gap of everyone else. The anchor is the leader when they are
     * here (it is their party), otherwise the first member found. Members too far from the anchor go first; if the
     * rest still span more than the gap, the one farthest from the anchor goes next, until they fit.
     */
    private static void enforce(MinecraftServer server, ServerPlayer any, List<ServerPlayer> members, int gap) {
        List<ServerPlayer> online = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        for (ServerPlayer m : members) {
            StatsData stats = DmzForms.stats(m);
            if (stats == null || stats.getStatus().isFused()) {
                continue;
            }
            online.add(m);
            levels.add(stats.getLevel());
        }
        if (online.size() < 2) {
            return;
        }
        ServerPlayer leader = PartyManager.getPartyLeader(any);
        int anchorIndex = leader == null ? -1 : online.indexOf(leader);
        if (anchorIndex < 0) {
            anchorIndex = 0;
        }
        int anchorLevel = levels.get(anchorIndex);

        List<ServerPlayer> removed = new ArrayList<>();
        for (int i = 0; i < online.size(); i++) {
            if (i != anchorIndex && Math.abs(levels.get(i) - anchorLevel) > gap) {
                removed.add(online.get(i));
            }
        }
        while (true) {
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, farthest = -1, farthestDist = -1;
            for (int i = 0; i < online.size(); i++) {
                if (removed.contains(online.get(i))) {
                    continue;
                }
                int lv = levels.get(i);
                min = Math.min(min, lv);
                max = Math.max(max, lv);
                int dist = Math.abs(lv - anchorLevel);
                if (i != anchorIndex && dist > farthestDist) {
                    farthestDist = dist;
                    farthest = i;
                }
            }
            if (max - min <= gap || farthest < 0) {
                break;
            }
            removed.add(online.get(farthest));
        }

        ServerPlayer anchor = online.get(anchorIndex);
        for (ServerPlayer m : removed) {
            PartyManager.leaveParty(m);
            m.sendSystemMessage(Component.literal("You were removed from your party: party members must be within "
                    + gap + " levels of each other.").withStyle(ChatFormatting.RED));
            DmzNpc.LOGGER.info("[{}] Removed {} from {}'s party: level gap over {}.", DmzNpc.MODID,
                    m.getGameProfile().getName(), anchor.getGameProfile().getName(), gap);
        }
    }
}
