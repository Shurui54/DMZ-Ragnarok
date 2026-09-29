package net.shurui.shuruisutilities.prestige;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.util.PlayerUtil;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Where the prestige reward ledger and the character slot grant live.
 *
 * <h2>The drift this fixes</h2>
 * Prestige LEVEL is on the player, in their persisted NBT, so on a network it travels in the vault with
 * everything else they own. Its reward LEDGER and their slot capacity are permission properties, and the
 * permission store is per install, so they stay behind on whichever server granted them.
 *
 * <p>Apart, those two mean something wrong. The ledger records the highest prestige ever PAID OUT for, and
 * exists so climbing back through a level already rewarded does not hand out a second slot. A player who
 * prestiges on one server and then plays on another finds a ledger that never saw those levels, so it pays
 * out again; go back and the reverse happens, and the ledger is ahead of the level so real progress earns
 * nothing. It is the same divergence already documented for deleting a player's .dat, reproduced across
 * servers and much easier to hit.
 *
 * <h2>The fix</h2>
 * Both values move next to the level they describe, in the player's persisted NBT, so all three travel
 * together or not at all and cannot disagree. Nothing else about prestige changes.
 *
 * <p>Only when a network is live. On a single server the permission property stays exactly as it was, because
 * there is nothing to drift from and moving the data would be churn for no gain.
 *
 * <h2>Carrying existing players over</h2>
 * A read finds nothing in NBT for anybody who earned their prestige before this existed, so it falls back to
 * the permission property AND copies it across. That happens once, on the first read, so nobody loses a slot
 * they were granted and nobody has to be migrated by hand. That carry over now applies to the ledger only: the slot
 * capacity takes the higher of player data and the permission store on every read instead (see {@link #slotsRaw}).
 */
public final class PrestigeLedger
{
    private PrestigeLedger() {}

    /** Reward ledger: the highest prestige level this player has ever been paid out for. */
    private static final String LEDGER_KEY = "su_prestige_rewarded";

    /** Character slot capacity, the value CommandCharacter.maxSlots reads. */
    private static final String SLOTS_KEY = "su_character_slots";

    /** Shared only when the network is live; a single server keeps the permission property. */
    private static boolean shared()
    {
        return net.shurui.shuruisutilities.shard.ShardSync.active();
    }

    public static String ledgerRaw(ServerPlayer p)
    {
        if (!shared())
            return permission(p, PrestigeManager.REWARDED_PROP);
        return readOrMigrate(p, LEDGER_KEY, PrestigeManager.REWARDED_PROP);
    }

    public static void setLedger(ServerPlayer p, int level)
    {
        if (!shared())
        {
            setPermission(p, PrestigeManager.REWARDED_PROP, String.valueOf(level));
            return;
        }
        write(p, LEDGER_KEY, String.valueOf(level));
        // The permission property is kept in step as well, so a server taken back OFF the network still finds
        // the right figure where it used to look rather than reverting to whatever it last knew.
        setPermission(p, PrestigeManager.REWARDED_PROP, String.valueOf(level));
    }

    /**
     * The slot capacity: on a network the HIGHER of the figure stored in player data and the one the permission store
     * grants now, never the stored figure alone.
     *
     * <p>Tracker #994. This used to go through {@link #readOrMigrate}, which on the first read copied whatever the
     * permission store answered into player data. For almost everybody that answer was the registered default "1",
     * and from then on the stored "1" won every read, so a later rank grant or a staff grant (a player set to 100
     * still read 1) was ignored until a prestige reset rewrote it. Taking the higher of the two keeps every stored
     * figure a prestige paid out (nobody drops below what they already had), lets any later grant raise it, and
     * nothing is written on a read any more, so the default is never persisted again. The NBT key is unchanged.
     *
     * <p>On top of that, never fewer than one slot plus one per prestige level the player has (owner, 2026-09-29:
     * players who already paid for a prestige get its extra slot automatically, for each prestige they have). See
     * {@link #prestigeSlotFloor}. It is a floor, not a bonus: a stored or granted figure above it still wins, and a
     * player whose prestige already paid the slot out reads exactly what they had.
     */
    public static String slotsRaw(ServerPlayer p)
    {
        return higher(slotsRawNoFloor(p), String.valueOf(prestigeSlotFloor(p)));
    }

    /**
     * The slot capacity as it stood before the level(s) now being paid out, for the prestige reward to add to.
     *
     * <p>The reward runs AFTER the character's new level is set, so {@link #slotsRaw} would already count the new
     * level in its floor and the reward's "+1 per newly crossed level" on top of it would pay twice. Taking the floor
     * from the levels already paid ({@code paidLevels}, the reward ledger before it is bumped) keeps a new prestige at
     * exactly one more slot, and the result never sits below the floor the new level brings. Clamped like
     * CommandCharacter.maxSlots.
     */
    public static int slotsBeforeReward(ServerPlayer p, int paidLevels)
    {
        Integer raw = parse(slotsRawNoFloor(p));
        int n = Math.max(raw == null ? 1 : raw, 1 + Math.max(0, paidLevels));
        return Math.max(1, Math.min(n, net.shurui.shuruisutilities.character.CharacterSlots.HARD_CAP));
    }

    /**
     * One slot plus one per prestige level this player has: the higher of the highest prestige held on any of their
     * characters and the reward ledger (the highest level ever paid out, which stays put when a rewarded character is
     * deleted or reset). Pure read, nothing is written; keyless the level and the ledger still read, so the floor
     * holds without the key too.
     */
    public static int prestigeSlotFloor(ServerPlayer p)
    {
        int held = net.shurui.shuruisutilities.character.CharacterSlots.peekHighestPrestige(p);
        Integer paid = parse(ledgerPeek(p));
        return 1 + Math.max(0, Math.max(held, paid == null ? 0 : paid));
    }

    /** max(stored, granted) on a network, the grant alone on a single server: the #994 figure without the floor. */
    private static String slotsRawNoFloor(ServerPlayer p)
    {
        String granted = permission(p, net.shurui.shuruisutilities.character.CharacterSlots.SLOT_LIMIT_PROP);
        if (!shared())
            return granted;
        return higher(stored(p, SLOTS_KEY), granted);
    }

    /** The reward ledger as {@link #ledgerRaw} reads it, minus the one-time carry over: a pure read. */
    private static String ledgerPeek(ServerPlayer p)
    {
        if (!shared())
            return permission(p, PrestigeManager.REWARDED_PROP);
        String inData = stored(p, LEDGER_KEY);
        return inData != null ? inData : permission(p, PrestigeManager.REWARDED_PROP);
    }

    public static void setSlots(ServerPlayer p, int limit)
    {
        if (!shared())
        {
            setPermission(p, net.shurui.shuruisutilities.character.CharacterSlots.SLOT_LIMIT_PROP,
                    String.valueOf(limit));
            return;
        }
        write(p, SLOTS_KEY, String.valueOf(limit));
        setPermission(p, net.shurui.shuruisutilities.character.CharacterSlots.SLOT_LIMIT_PROP,
                String.valueOf(limit));
    }

    /**
     * Read from NBT, falling back to the permission property and carrying it over when NBT has nothing.
     *
     * <p>The carry over writes ONLY the NBT copy. Writing the permission back would mark the permission store
     * dirty for every player who logs in, which on a busy server is a whole-tree save every few seconds for no
     * change at all.
     */
    private static String readOrMigrate(ServerPlayer p, String key, String node)
    {
        try
        {
            CompoundTag tag = PlayerUtil.getPersistedTag(p, true);
            if (tag != null && tag.contains(key))
                return tag.getString(key);
            String existing = permission(p, node);
            if (existing != null && !existing.isEmpty() && tag != null)
            {
                tag.putString(key, existing);
                LoggingHandler.sulog.debug("[Prestige] Carried {} for {} into player data.",
                        node, p.getGameProfile().getName());
            }
            return existing;
        }
        catch (Throwable t)
        {
            return permission(p, node);
        }
    }

    /** The figure stored in player data under this key, or null when there is none (or it cannot be read). */
    private static String stored(ServerPlayer p, String key)
    {
        try
        {
            CompoundTag tag = PlayerUtil.getPersistedTag(p, true);
            return tag != null && tag.contains(key) ? tag.getString(key) : null;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /** The numerically higher of two raw figures; one that does not parse loses to one that does. */
    private static String higher(String a, String b)
    {
        Integer x = parse(a);
        Integer y = parse(b);
        if (x == null)
            return y == null ? (a != null ? a : b) : b;
        if (y == null)
            return a;
        return y > x ? b : a;
    }

    private static Integer parse(String raw)
    {
        if (raw == null)
            return null;
        try
        {
            return Integer.parseInt(raw.trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static void write(ServerPlayer p, String key, String value)
    {
        try
        {
            CompoundTag tag = PlayerUtil.getPersistedTag(p, true);
            if (tag != null)
                tag.putString(key, value);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[Prestige] Could not store {} for {}: {}",
                    key, p.getGameProfile().getName(), t.toString());
        }
    }

    private static String permission(ServerPlayer p, String node)
    {
        try
        {
            return APIRegistry.perms.getUserPermissionProperty(UserIdent.get(p), node);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private static void setPermission(ServerPlayer p, String node, String value)
    {
        try
        {
            APIRegistry.perms.setPlayerPermissionProperty(UserIdent.get(p), node, value);
        }
        catch (Throwable ignored)
        {
        }
    }
}
