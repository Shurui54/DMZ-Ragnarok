package net.shurui.shuruisutilities.compat.dmz;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dragonminez.common.quest.PartyManager;
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
 * {@code playerPartyMap}). DMZ's own {@code PartyManager.onPlayerLogin} reads {@code getPartyOf(uuid)}, finds null on
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
 * <p>This listens at {@link EventPriority#LOWEST} so it runs after the vault restore ({@code ShardSync.onLogin} at
 * HIGHEST, which puts the carried {@code StatsData} on the player) and after DMZ's own NORMAL-priority login handler.
 * By that point the carried party fields are present and we set up the roster and team ourselves, idempotently.
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

    @SubscribeEvent(priority = EventPriority.LOWEST)
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
        Map<UUID, PartyInstance> parties = parties(data);
        Map<UUID, UUID> playerMap = playerMap(data);

        // A carried pending invite is a resurrection risk: it was captured on another server, its inviter context is
        // local and it is short lived. Drop it on arrival rather than let it come back to life here.
        if (quest.hasPendingPartyInvite())
        {
            quest.clearPendingPartyInvite();
        }

        UUID desiredParty = quest.getActivePartyId();
        boolean changed = false;

        // If this server already has the player mapped to a DIFFERENT party than they now carry, detach them from it
        // first. This both fixes the pointer/roster disagreement and cleans a stale team row from an earlier visit.
        UUID mapped = playerMap.get(self);
        if (mapped != null && !mapped.equals(desiredParty))
        {
            detach(server, player, parties, playerMap, mapped);
            mapped = null;
            changed = true;
        }

        if (desiredParty == null)
        {
            // The player carries no party. Make sure nothing local claims them and strip any DMZ team membership.
            if (mapped != null)
            {
                detach(server, player, parties, playerMap, mapped);
                changed = true;
            }
            else
            {
                removeFromPartyTeam(server, player, null);
            }
            if (changed)
            {
                data.setDirty();
            }
            return;
        }

        List<UUID> carriedMembers = new ArrayList<>(quest.getPartyMemberIds());
        UUID carriedLeader = quest.getPartyLeaderId();
        boolean carriedPvp = quest.isPartyPvpEnabled();

        PartyInstance party = parties.get(desiredParty);
        if (party == null)
        {
            // No member has built this party on this server yet: create it with the exact carried id, so its derived
            // scoreboard team name matches for every member of it that lands here. PvP is taken from the carried flag
            // only at creation; a later arriver never flips a pre-existing party's flag (the leader re-toggles it).
            UUID leader = carriedLeader != null ? carriedLeader : self;
            party = new PartyInstance(desiredParty, leader, new ArrayList<>(), carriedPvp);
            parties.put(desiredParty, party);
            changed = true;
        }

        // Place the player authoritatively: their own pointer decides their own membership.
        if (!party.getMembers().contains(self))
        {
            party.getMembers().add(self);
            changed = true;
        }
        if (!desiredParty.equals(playerMap.get(self)))
        {
            playerMap.put(self, desiredParty);
            changed = true;
        }

        // Opportunistically flesh out the roster from the carried snapshot, but NEVER steal a member another party on
        // this server already claims: that member's own arrival is authoritative for them and will correct any drift.
        for (UUID member : carriedMembers)
        {
            if (member.equals(self))
            {
                continue;
            }
            UUID owner = playerMap.get(member);
            if (owner == null)
            {
                if (!party.getMembers().contains(member))
                {
                    party.getMembers().add(member);
                }
                playerMap.put(member, desiredParty);
                changed = true;
            }
            else if (owner.equals(desiredParty) && !party.getMembers().contains(member))
            {
                party.getMembers().add(member);
                changed = true;
            }
        }

        // Keep the leader valid: if the recorded leader is not a member of the resolved roster, prefer the carried
        // leader when it is now present, otherwise fall back to this player.
        UUID leaderId = party.getLeaderId();
        if (leaderId == null || !party.getMembers().contains(leaderId))
        {
            UUID newLeader = (carriedLeader != null && party.getMembers().contains(carriedLeader))
                    ? carriedLeader
                    : self;
            if (!newLeader.equals(party.getLeaderId()))
            {
                party.setLeaderId(newLeader);
                changed = true;
            }
        }

        // Put the player into the party's scoreboard team, matching DMZ's own team setup: friendly fire tracks the
        // party PvP flag, invisibles are seen, and the team is created if this is the first member here.
        //
        // Guarded, because the ROSTER is the part that matters and it is already correct by this point. Letting a
        // scoreboard problem escape from here would skip the setDirty below and throw away everything above it,
        // which is how one bad removePlayerFromTeam call turned into players arriving with no party at all. The
        // team only drives friendly fire and seeing invisible team mates; losing it is a far smaller thing than
        // losing the party.
        try
        {
            addToPartyTeam(server, player, desiredParty, party.isPvpEnabled());
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[shard] Rebuilt {}'s party but could not set up its scoreboard team: {}",
                    player.getGameProfile().getName(), t.toString());
        }

        if (changed)
        {
            data.setDirty();
        }
        // Best-effort nudge so an online quest controller resyncs shared progress; harmless if there is none.
        //
        // DMZ's merge (PlayerQuestData.mergeQuestStateFrom) also copies the LEADER's difficulty onto every member, so
        // this nudge used to switch a member's difficulty on a plain shard hop. A player agrees to the party's
        // difficulty when they JOIN (acceptInvite's confirm step), not every time somebody changes server, and a
        // flipped difficulty relocks rewards on quests they completed. So each online member's own difficulty is
        // snapshotted first and put back afterwards, and anyone whose difficulty had to be restored is resynced.
        Map<UUID, com.dragonminez.common.quest.Difficulty> ownDifficulty = new java.util.HashMap<>();
        try
        {
            ServerPlayer controller = PartyManager.resolveQuestController(player);
            if (controller != null)
            {
                for (ServerPlayer member : PartyManager.getAllPartyMembers(controller))
                {
                    PlayerQuestData qd = questData(member);
                    if (qd != null && qd.getDifficulty() != null)
                        ownDifficulty.put(member.getUUID(), qd.getDifficulty());
                }
            }
            PartyManager.syncPartyQuestState(player);
        }
        catch (Throwable ignored)
        {
            // A sync failure must not undo the roster and team we just made consistent.
        }
        for (Map.Entry<UUID, com.dragonminez.common.quest.Difficulty> e : ownDifficulty.entrySet())
        {
            try
            {
                ServerPlayer member = server.getPlayerList().getPlayer(e.getKey());
                PlayerQuestData qd = member == null ? null : questData(member);
                if (qd == null || qd.getDifficulty() == e.getValue())
                    continue;
                qd.setDifficulty(e.getValue());
                com.dragonminez.common.network.NetworkHandler.sendToPlayer(
                        new com.dragonminez.common.network.S2C.ProgressionSyncS2C(member), member);
            }
            catch (Throwable ignored)
            {
                // Restoring one member must never stop the rest being restored.
            }
        }
    }

    private static PlayerQuestData questData(ServerPlayer player)
    {
        StatsData stats = StatsProvider.get(StatsCapability.INSTANCE, player).resolve().orElse(null);
        return stats == null ? null : stats.getPlayerQuestData();
    }

    /** Remove the player from a local party and its scoreboard team, dropping an emptied party and team outright. */
    private void detach(MinecraftServer server, ServerPlayer player, Map<UUID, PartyInstance> parties,
            Map<UUID, UUID> playerMap, UUID partyId)
    {
        UUID self = player.getUUID();
        playerMap.remove(self);
        PartyInstance party = parties.get(partyId);
        if (party != null)
        {
            party.getMembers().remove(self);
            if (party.getMembers().isEmpty())
            {
                parties.remove(partyId);
            }
            else if (self.equals(party.getLeaderId()))
            {
                // Keep a valid leader for the survivors rather than leaving a dangling leader id.
                party.setLeaderId(party.getMembers().get(0));
            }
        }
        // Guarded for the same reason as the add: detaching happens EARLY in reconcile, so anything escaping here
        // abandons the rebuild before it has started and the arrival ends up in no party.
        try
        {
            removeFromPartyTeam(server, player, partyId);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[shard] Detached {} from a stale party but could not clear its scoreboard"
                    + " team: {}", player.getGameProfile().getName(), t.toString());
        }
    }

    /** Add the player to the given party's team, creating it with DMZ's flags if needed and setting friendly fire. */
    private void addToPartyTeam(MinecraftServer server, ServerPlayer player, UUID partyId, boolean pvpEnabled)
    {
        Scoreboard scoreboard = server.getScoreboard();
        String scoreName = player.getScoreboardName();
        String desired = teamName(partyId);

        // Take the player out of any OTHER party team first, so they cannot sit in two.
        PlayerTeam current = scoreboard.getPlayersTeam(scoreName);
        if (current != null && current.getName().startsWith(TEAM_PREFIX) && !current.getName().equals(desired))
        {
            scoreboard.removePlayerFromTeam(scoreName, current);
            if (current.getPlayers().isEmpty())
            {
                scoreboard.removePlayerTeam(current);
            }
        }

        PlayerTeam team = scoreboard.getPlayerTeam(desired);
        if (team == null)
        {
            team = scoreboard.addPlayerTeam(desired);
            team.setSeeFriendlyInvisibles(true);
        }
        team.setAllowFriendlyFire(pvpEnabled);
        scoreboard.addPlayerToTeam(scoreName, team);
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
