package net.shurui.shuruisutilities.guilds.raid;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Overworld-attached saved data holding the guild-raid SPOILS: per winning guild, one unspent RIGHT to destroy a
 * specific planet later. Mirrors the SavedData idiom used across the suite ({@link
 * net.shurui.shuruisutilities.space.GeneratedPlanetClaims}, {@link GuildRaidLockouts}): persisted with the overworld
 * data storage under a fixed name so it survives restarts.
 *
 * <p>This is the whole "one win, one destruction" ledger, and NOTHING here destroys a planet. A raid win only makes
 * destruction POSSIBLE: it grants a time-limited entitlement that a SEPARATE redemption trigger (an overcharged ki
 * blast, designed elsewhere) spends by calling {@link #consume}. Keeping the grant and the spend apart is the point,
 * so the destruction is always a deliberate choice the guild makes later, never an automatic consequence of winning.
 *
 * <p>Shape: attacker guild id -&gt; the single {@link Spoil} that guild holds. One outstanding right per guild is
 * enough, so a new win REPLACES any older unspent one (logged at info, since an admin will want that state change in
 * the log). Each row carries the target planet id, the game time the right was granted, and the game time it expires.
 * Expiry is an ABSOLUTE overworld game time compared at use time, so no scheduler is needed: a right simply lapses
 * once the game time passes it, and the slow housekeeping sweep in the space module only tidies the map. The special
 * value {@link #NEVER} means the right does not lapse at all (the operator choice behind a 0-seconds config).
 */
public final class GuildRaidSpoils extends SavedData
{
    private static final String NAME = "shuruisutilities_guild_raid_spoils";

    // sentinel expiry meaning "this right never lapses". Chosen as Long.MAX_VALUE so the plain "now >= expiry" test
    // naturally never fires for it, with no separate branch: a real game time can never reach it.
    public static final long NEVER = Long.MAX_VALUE;

    // attacker guild id -> the one unspent destruction right that guild holds
    private final Map<String, Spoil> spoils = new HashMap<>();

    // the live server, re-set on every get() so the no-argument queries (has / planetFor / consume) can read the
    // current overworld game time to judge expiry. NOT persisted: it is a runtime handle, re-supplied each get().
    private transient MinecraftServer server;

    /**
     * One unspent destruction right. {@code planetId} is the planet the holding guild may destroy, {@code
     * grantedAtGameTime} is the overworld game time the win minted the right at, and {@code expiryGameTime} is the
     * absolute overworld game time it lapses at ({@link #NEVER} for a right that never lapses).
     */
    public static final class Spoil
    {
        public final String planetId;
        public final long grantedAtGameTime;
        public final long expiryGameTime;

        Spoil(String planetId, long grantedAtGameTime, long expiryGameTime)
        {
            this.planetId = planetId;
            this.grantedAtGameTime = grantedAtGameTime;
            this.expiryGameTime = expiryGameTime;
        }
    }

    public static GuildRaidSpoils get(MinecraftServer server)
    {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        GuildRaidSpoils s = overworld.getDataStorage().computeIfAbsent(GuildRaidSpoils::load, GuildRaidSpoils::new, NAME);
        // re-supply the runtime server handle so the no-argument expiry queries can read the current game time.
        s.server = server;
        return s;
    }

    private static GuildRaidSpoils load(CompoundTag tag)
    {
        GuildRaidSpoils s = new GuildRaidSpoils();
        ListTag entries = tag.getList("spoils", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++)
        {
            CompoundTag e = entries.getCompound(i);
            s.spoils.put(e.getString("guild"),
                    new Spoil(e.getString("planet"), e.getLong("granted"), e.getLong("expiry")));
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        ListTag entries = new ListTag();
        for (Map.Entry<String, Spoil> e : spoils.entrySet())
        {
            CompoundTag c = new CompoundTag();
            c.putString("guild", e.getKey());
            c.putString("planet", e.getValue().planetId);
            c.putLong("granted", e.getValue().grantedAtGameTime);
            c.putLong("expiry", e.getValue().expiryGameTime);
            entries.add(c);
        }
        tag.put("spoils", entries);
        return tag;
    }

    // the current overworld game time, or Long.MIN_VALUE when no server is attached (never at runtime, only in a bare
    // load before a get()). MIN_VALUE reads as "nothing has expired yet", the safe direction for a missing handle.
    private long now()
    {
        return server != null ? server.overworld().getGameTime() : Long.MIN_VALUE;
    }

    // a right is expired only once the game time has reached its absolute expiry. NEVER (Long.MAX_VALUE) can never be
    // reached, so a never-lapsing right is never expired without any special-casing here.
    private static boolean expired(Spoil spoil, long gameTime)
    {
        return spoil.expiryGameTime != NEVER && gameTime >= spoil.expiryGameTime;
    }

    /**
     * Grant the attacking guild the right to destroy {@code planetId}, minted at {@code gameTime} and lapsing at the
     * absolute {@code expiryGameTime} ({@link #NEVER} for a right that never lapses). One right per guild: this
     * REPLACES any older unspent right the guild held, which is logged at info because it is a real state transition
     * (the guild traded an unspent right on one planet for a fresh one). The caller has already run the win guards.
     */
    public void grant(String guildId, String planetId, long gameTime, long expiryGameTime)
    {
        Spoil previous = spoils.put(guildId, new Spoil(planetId, gameTime, expiryGameTime));
        if (previous != null)
        {
            LoggingHandler.sulog.info(
                    "[GuildRaid] Replaced unspent planet-destruction right for guild {}: {} -> {}.",
                    guildId, previous.planetId, planetId);
        }
        setDirty();
    }

    /** True only if this guild holds an UNEXPIRED right for this exact planet id. */
    public boolean has(String guildId, String planetId)
    {
        Spoil spoil = spoils.get(guildId);
        return spoil != null && spoil.planetId.equals(planetId) && !expired(spoil, now());
    }

    /** The planet id this guild may destroy right now (an unexpired right), or null if it holds none. */
    public String planetFor(String guildId)
    {
        Spoil spoil = spoils.get(guildId);
        return (spoil != null && !expired(spoil, now())) ? spoil.planetId : null;
    }

    /**
     * The absolute game time this guild's right lapses at ({@link #NEVER} for a right that never lapses), or 0 if the
     * guild holds no right at all. Purely informational (for a "time remaining" readout); the authoritative gate on
     * whether the right can be spent is {@link #has} / {@link #consume}.
     */
    public long expiryFor(String guildId)
    {
        Spoil spoil = spoils.get(guildId);
        return spoil != null ? spoil.expiryGameTime : 0L;
    }

    /**
     * Spend this guild's right on this exact planet, removing it. Returns false (and removes nothing) if the guild
     * holds no right, holds one for a different planet, or its right has lapsed. This is the ONLY way a right is
     * removed on redemption, so the "one win, one destruction" rule cannot be bypassed by any other path.
     */
    public boolean consume(String guildId, String planetId)
    {
        Spoil spoil = spoils.get(guildId);
        if (spoil == null || !spoil.planetId.equals(planetId) || expired(spoil, now()))
        {
            return false;
        }
        spoils.remove(guildId);
        setDirty();
        return true;
    }

    /**
     * Drop every right that has lapsed as of {@code gameTime}, returning how many were dropped. Housekeeping only: an
     * expired right already fails {@link #has} / {@link #consume} through the lazy expiry check, so this just keeps the
     * map from accumulating dead rows. Called on the space module's slow sweep tick, not on its own timer.
     */
    public int sweepExpired(long gameTime)
    {
        int dropped = 0;
        for (Iterator<Map.Entry<String, Spoil>> it = spoils.entrySet().iterator(); it.hasNext(); )
        {
            if (expired(it.next().getValue(), gameTime))
            {
                it.remove();
                dropped++;
            }
        }
        if (dropped > 0)
        {
            setDirty();
        }
        return dropped;
    }
}
