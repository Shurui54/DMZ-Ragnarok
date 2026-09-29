package net.shurui.dev.sdu.quest;

import java.util.List;
import java.util.UUID;

import com.dragonminez.common.quest.PartyManager;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Who a DMZ quest kill mob belongs to, and whether a given player is one of the people it exists for.
 *
 * <h2>Why this has to exist</h2>
 *
 * <p>{@code QuestService.spawnKillObjectives} stamps every quest kill mob with {@code dmz_quest_key} (which
 * quest) and {@code dmz_quest_owner} (the UUID of the player whose acceptance spawned it). DMZ then treats
 * the owner as authoritative: {@code QuestEvents.matchesQuestSpawnTags} only credits a kill when the mob's
 * owner is the killer or one of the killer's CURRENT party members. So a quest mob spawned for somebody else
 * is not merely "not yours", it is unkillable-for-credit by you, and killing it advances nobody's quest but
 * theirs.
 *
 * <p>Anything that reasons about quest mobs therefore has to ask the same question DMZ asks, or it silently
 * disagrees with the kill-credit rule. Two places do, for opposite reasons, which is why the predicate lives
 * here instead of being written twice:
 * <ul>
 *   <li>{@link net.shurui.dev.sdu.event.DeferredSpawnHandler} suppresses a duplicate spawn when a set is
 *       already alive. Scoped to the party, "already alive" means "already alive FOR YOU".</li>
 *   <li>{@link net.shurui.dev.sdu.event.QuestMobAggroLock} vetoes target acquisition. Scoped to the party, a
 *       quest mob only ever attacks somebody who could get credit for killing it.</li>
 * </ul>
 *
 * <h2>Party membership is read from the player, not the owner</h2>
 *
 * <p>{@link #creditsPartyOf} resolves the party of the PLAYER being asked about and looks for the owner in it,
 * which is exactly what DMZ does. That matters because it needs only the asking player to be online: the owner
 * may have logged off, and their mobs are still rightfully their party's.
 *
 * <h2>Unknown is not false</h2>
 *
 * <p>{@link #creditsPartyOf} returns {@code null} when it cannot tell (no tags, no server, DMZ threw). The two
 * callers want OPPOSITE fallbacks: the spawn check must fall back to "not alive" so a failure spawns the mobs
 * rather than leaving a player with nothing to kill, and the aggro lock must fall back to "allowed" so a
 * failure leaves DMZ's aggression alone rather than making quest mobs inert. A boolean cannot carry that, so
 * this deliberately does not collapse the third state.
 */
public final class QuestMobOwnership
{
    private QuestMobOwnership()
    {
    }

    /** Which quest the mob was spawned for. DMZ writes it in {@code spawnKillObjectives}. */
    public static final String TAG_QUEST_KEY = "dmz_quest_key";

    /** UUID string of the player whose quest acceptance spawned the mob. */
    public static final String TAG_QUEST_OWNER = "dmz_quest_owner";

    /** True when the entity is a DMZ quest kill mob, i.e. carries BOTH tags with content. */
    public static boolean isQuestMob(Entity entity)
    {
        return !questKeyOf(entity).isEmpty() && ownerOf(entity) != null;
    }

    /** The quest this mob belongs to, or "" when it is not a quest mob. Never null. */
    public static String questKeyOf(Entity entity)
    {
        if (entity == null)
        {
            return "";
        }
        CompoundTag data = entity.getPersistentData();
        return data.contains(TAG_QUEST_KEY) ? data.getString(TAG_QUEST_KEY) : "";
    }

    /** The owning player's UUID, or null when absent or malformed. */
    public static UUID ownerOf(Entity entity)
    {
        if (entity == null)
        {
            return null;
        }
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(TAG_QUEST_OWNER))
        {
            return null;
        }
        String raw = data.getString(TAG_QUEST_OWNER);
        if (raw == null || raw.isBlank())
        {
            return null;
        }
        try
        {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException malformed)
        {
            return null;
        }
    }

    /**
     * Whether this mob was spawned for {@code player} or for somebody currently in their party, which is DMZ's
     * own kill-credit rule ({@code QuestEvents.matchesQuestSpawnTags}).
     *
     * @return TRUE when the mob is theirs, FALSE when it provably belongs to an unrelated player, and null when
     *         the question cannot be answered (not a quest mob, or the party lookup failed). See the class note:
     *         the two callers deliberately treat null differently.
     */
    public static Boolean creditsPartyOf(Entity mob, ServerPlayer player)
    {
        if (player == null)
        {
            return null;
        }
        UUID owner = ownerOf(mob);
        if (owner == null)
        {
            return null;
        }
        if (owner.equals(player.getUUID()))
        {
            return Boolean.TRUE; // theirs outright, no party lookup needed
        }
        try
        {
            List<ServerPlayer> party = PartyManager.getAllPartyMembers(player);
            if (party == null)
            {
                return null;
            }
            for (ServerPlayer member : party)
            {
                if (member != null && owner.equals(member.getUUID()))
                {
                    return Boolean.TRUE;
                }
            }
            // Party resolved cleanly and the owner is not in it: this mob is somebody else's.
            return Boolean.FALSE;
        }
        catch (Throwable partyLookupFailed)
        {
            return null;
        }
    }

    /** Convenience for a mob of a KNOWN quest: as {@link #creditsPartyOf} but also requires the quest to match. */
    public static Boolean creditsPartyOf(Entity mob, ServerPlayer player, String questKey)
    {
        if (questKey == null || !questKey.equals(questKeyOf(mob)))
        {
            return Boolean.FALSE;
        }
        return creditsPartyOf(mob, player);
    }
}
