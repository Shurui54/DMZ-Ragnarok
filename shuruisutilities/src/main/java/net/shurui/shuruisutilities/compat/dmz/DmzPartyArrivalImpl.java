package net.shurui.shuruisutilities.compat.dmz;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;
import com.dragonminez.server.world.data.PartySavedData;
import com.dragonminez.server.world.data.PartySavedData.PartyInstance;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.shard.ShardSync;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Reconciles DragonMineZ's per-server party roster with the party POINTER a player already carries in their vaulted
 * state, on arrival.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The failure this fixes is a divergence, not a missing sync. A player's own {@code PlayerQuestData} (part of the
 * {@code StatsData} the shard vault carries, see {@code ShardPayload}) already records their {@code activePartyId},
 * {@code partyLeaderId}, member list and party PvP flag, so a hopping player ARRIVES asserting a party. What is
 * server-local and does NOT travel is the authoritative roster, {@code PartySavedData} ({@code parties} +
 * {@code playerPartyMap}). DMZ's own login reconcile reads {@code getPartyOf(uuid)}, finds null on
 * the destination, and sets up no scoreboard team, so shared quest control, reward claims, party PvP and leadership
 * all break while the client still shows a ghost party whose members do not resolve.
 *
 * <h2>Why rebuild-on-arrival, not a synced roster</h2>
 *
 * <p>We deliberately do NOT push {@code PartySavedData} through the state sync. The pointer is the source of truth and
 * it already travels with the player, so rebuilding the destination roster FROM that pointer cannot desync from the
 * player, needs no new synced entry, and sidesteps the byte-determinism and last-write-wins merge traps a shared
 * roster would bring. The one weakness, that a party only materialises on a server once a member arrives, does not
 * matter in practice because DMZ's {@code getAllPartyMembers} only ever returns players online on THIS server anyway.
 *
 * <h2>Ordering</h2>
 *
 * <p>This listens at {@link EventPriority#LOW}: after the vault restore ({@code ShardSync.onLogin} at HIGHEST, which
 * puts the carried {@code StatsData} on the player), and BEFORE DragonMineZ 2.2's own login reconcile
 * ({@code QuestEvents.onPlayerLogin} at LOWEST, {@code PartyManager.reconcile}). That order matters in 2.2: DMZ's
 * reconcile CLEARS a player's carried party state when the local roster has no party for them, so the roster must be
 * rebuilt first. DMZ's reconcile then sets up the team, friendly fire, leadership and the member sync itself.
 */
public final class DmzPartyArrivalImpl
{
    /** DMZ's team name prefix, read from PartyManager bytecode (TEAM_PREFIX = "dmzp_"). */
    private static final String TEAM_PREFIX = "dmzp_";

    private static final Field PARTIES_FIELD;
    private static final Field PLAYER_MAP_FIELD;

    static
    {
        // Resolve DMZ's two private maps, and NEVER throw out of here.
        //
        // This class is initialised from DmzPartyArrival.init() during MOD CONSTRUCTION. An
        // ExceptionInInitializerError at that point does not disable party reconciliation, it takes the whole mod
        // down before the server ever starts. A DragonMineZ update that renames either private field is a routine
        // event and must cost the party feature, not the server. So the failure is recorded and every caller checks
        // for it, which is the same "degrade, do not crash" rule the rest of the DMZ compat layer follows.
        Field parties = null;
        Field playerMap = null;
        try
        {
            parties = PartySavedData.class.getDeclaredField("parties");
            parties.setAccessible(true);
            playerMap = PartySavedData.class.getDeclaredField("playerPartyMap");
            playerMap.setAccessible(true);
        }
        catch (Throwable t)
        {
            parties = null;
            playerMap = null;
            LoggingHandler.sulog.error("[shard] Could not reach DragonMineZ's party storage ({}), so parties will "
                    + "NOT be rebuilt for players arriving from another server. Everything else is unaffected. This "
                    + "usually means a DragonMineZ update renamed a field in PartySavedData.", t.toString());
        }
        PARTIES_FIELD = parties;
        PLAYER_MAP_FIELD = playerMap;
    }

    /** False when DMZ's party storage could not be reached, in which case reconciling is skipped entirely. */
    private static boolean usable()
    {
        return PARTIES_FIELD != null && PLAYER_MAP_FIELD != null;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        // Scope to the shard network: off the network DMZ's own state is authoritative and there is nothing to
        // reconcile against. Guarded so a failure only costs this one player's party setup, never the login.
        if (!usable() || !ShardSync.active() || !(event.getEntity() instanceof ServerPlayer player))
        {
            return;
        }
        try
        {
            reconcile(player);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[shard] Party arrival reconcile failed for {}: {}",
                    player.getGameProfile().getName(), t.toString());
        }
    }

    private void reconcile(ServerPlayer player)
    {
        MinecraftServer server = player.getServer();
        if (server == null)
        {
            return;
        }
        StatsData stats = StatsProvider.get(StatsCapability.INSTANCE, player).resolve().orElse(null);
        if (stats == null)
        {
            return;
        }
        PlayerQuestData quest = stats.getPlayerQuestData();
        if (quest == null)
        {
            return;
        }

        UUID self = player.getUUID();
        PartySavedData data = PartySavedData.get(server);

        // A carried pending invite is a resurrection risk: it was captured on another server, its inviter context is
        // local and it is short lived. Drop it on arrival rather than let it come back to life here.
        if (quest.hasPendingPartyInvite())
        {
            quest.clearPendingPartyInvite();
        }

        UUID desiredParty = quest.getActivePartyId();

        // If this server already has the player in a DIFFERENT party than they now carry, detach them from it first.
        // DMZ's removePlayer drops an emptied party and hands ownership and leadership to a survivor.
        PartyInstance mapped = data.getPartyOf(self);
        if (mapped != null && !mapped.getPartyId().equals(desiredParty))
        {
            UUID stale = mapped.getPartyId();
            data.removePlayer(self);
            try
            {
                removeFromPartyTeam(server, player, stale);
            }
            catch (Throwable t)
            {
                LoggingHandler.sulog.warn("[shard] Detached {} from a stale party but could not clear its scoreboard"
                        + " team: {}", player.getGameProfile().getName(), t.toString());
            }
        }

        if (desiredParty == null)
        {
            // The player carries no party. DMZ's own login reconcile strips any party team they still sit in.
            return;
        }

        PartyInstance party = data.getParty(desiredParty);
        if (party == null)
        {
            // No member has built this party on this server yet: create it with the exact carried id, so its derived
            // scoreboard team name matches for every member of it that lands here. PvP is taken from the carried flag
            // only at creation; a later arriver never flips a pre-existing party's flag (the leader re-toggles it).
            // DMZ's public createParty always mints a new id, so the instance goes into its maps directly.
            List<UUID> members = new ArrayList<>();
            members.add(self);
            UUID owner = quest.getPartyOwnerId() != null ? quest.getPartyOwnerId() : quest.getPartyLeaderId();
            if (owner == null)
            {
                owner = self;
            }
            UUID leader = quest.getPartyLeaderId() != null ? quest.getPartyLeaderId() : owner;
            party = new PartyInstance(desiredParty, owner, leader, members, quest.isPartyPvpEnabled());
            parties(data).put(desiredParty, party);
            playerMap(data).put(self, desiredParty);
        }
        else
        {
            // Place the player authoritatively: their own pointer decides their own membership.
            data.addPlayerToParty(desiredParty, self);
        }

        // Opportunistically flesh out the roster from the carried snapshot, but NEVER steal a member another party on
        // this server already claims: that member's own arrival is authoritative for them and will correct any drift.
        for (UUID member : quest.getPartyMemberIds())
        {
            if (member != null && !member.equals(self) && data.getPartyOf(member) == null)
            {
                data.addPlayerToParty(desiredParty, member);
            }
        }
        data.setDirty();

        // The scoreboard team, friendly fire, leadership (owner first, else an online member) and the party sync to
        // every online member are DMZ 2.2's own login reconcile (PartyManager.reconcile), which runs after this at
        // LOWEST and now finds the roster in place. 2.1 needed us to do all of that here, and to protect each member's
        // difficulty from a leader-to-member quest merge that 2.2 no longer has.
    }

    /**
     * Take the player out of a party team. When {@code partyId} is null, any DMZ party team they sit in is used, which
     * is what a now-party-less arrival needs. An emptied team row is removed so it cannot leak in scoreboard.dat.
     *
     * <h2>The membership check is not optional</h2>
     * When {@code partyId} is given, the team is resolved BY NAME, which says nothing about whether this player is in
     * it. Vanilla's {@code removePlayerFromTeam} throws {@code IllegalStateException} unless the player is on exactly
     * the team passed:
     *
     * <pre>
     *     if (this.getPlayersTeam(p_83464_) != p_83465_) {
     *         throw new IllegalStateException("Player is either on another team or not on any team. ...");
     * </pre>
     *
     * <p>A player who has just logged in is on NO team, so that check fails for them every single time. Detaching an
     * arrival from a stale party whose team still exists here, because other members are on it, therefore threw, the
     * exception left {@link #reconcile} before the roster was rebuilt, and the caller logged it and moved on. The
     * player landed in no party at all: "switching servers forces people to leave the party they are in".
     */
    private void removeFromPartyTeam(MinecraftServer server, ServerPlayer player, UUID partyId)
    {
        Scoreboard scoreboard = server.getScoreboard();
        String scoreName = player.getScoreboardName();
        PlayerTeam team = partyId != null
                ? scoreboard.getPlayerTeam(teamName(partyId))
                : scoreboard.getPlayersTeam(scoreName);
        if (team == null || !team.getName().startsWith(TEAM_PREFIX))
        {
            return;
        }
        if (scoreboard.getPlayersTeam(scoreName) == team)
        {
            scoreboard.removePlayerFromTeam(scoreName, team);
        }
        // Still worth tidying an empty row even when this player was never on it: the team may have been left behind
        // by the last member to leave, and nothing else will clear it.
        if (team.getPlayers().isEmpty())
        {
            scoreboard.removePlayerTeam(team);
        }
    }

    /** DMZ's team name for a party: "dmzp_" + the first 11 chars of the dashless party UUID (read from bytecode). */
    private static String teamName(UUID partyId)
    {
        return TEAM_PREFIX + partyId.toString().replace("-", "").substring(0, 11);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, PartyInstance> parties(PartySavedData data)
    {
        try
        {
            return (Map<UUID, PartyInstance>) PARTIES_FIELD.get(data);
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalStateException("Cannot read PartySavedData.parties", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, UUID> playerMap(PartySavedData data)
    {
        try
        {
            return (Map<UUID, UUID>) PLAYER_MAP_FIELD.get(data);
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalStateException("Cannot read PartySavedData.playerPartyMap", e);
        }
    }
}
