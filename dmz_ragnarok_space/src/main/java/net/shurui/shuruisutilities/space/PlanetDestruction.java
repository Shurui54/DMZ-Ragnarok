package net.shurui.shuruisutilities.space;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.ResourceSyncS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsProvider;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.world.space.SurfaceTravelData;
import net.shurui.shuruisutilities.world.space.SurfaceSnap;

/**
 * The ONE server-side entry point that destroys a generated planet. Everything that can destroy a planet routes here:
 * the admin {@code /spaceplanet destroy} command in phase A, the ki-blast trigger in phase B, and a guild raid win
 * later. None of them reimplement the steps below, so the destructible rule, the on-surface death, the surface-flag
 * reset and the client sync can never drift between callers.
 *
 * <p>A moon reaches this path adapted into a {@link GeneratedPlanets.Generated} by {@link GeneratedPlanets#forMoon}, with
 * the moon id doubling as its cell key, so every step below is id-keyed and treats a moon exactly like a generated planet:
 * the same mark-destroyed, eviction, salvage, garrison clear, course invalidation and resync run unchanged, and the debris
 * timer later restores it. Only a FIXED body is refused.
 *
 * <p>The full sequence for a destroy is: destructible check (a fixed body is refused; a generated planet or a moon passes),
 * optional unclaimed check (the caller decides whether an owned planet may be destroyed), mark the cell destroyed in the
 * authoritative
 * {@link GeneratedPlanetClaims}, kill and evict every player standing on that planet's surface, clear the surface-
 * generated flag so a future planet in the slot re-stamps fresh terrain, invalidate any compass course pointing at the
 * planet, and finally re-sync the layout so every client suppresses the planet immediately. The destroyed state is read
 * back everywhere through the single {@link SpaceLayout#isDestroyed} accessor, so the client and server never derive it
 * independently.
 */
public final class PlanetDestruction
{
    private PlanetDestruction()
    {
    }

    // one-shot latch so a DMZ alignment-API drift is logged once, not every destroy. See applyAlignmentPenalty.
    private static final AtomicBoolean ALIGNMENT_WARNED = new AtomicBoolean(false);

    /**
     * Destroy a generated planet by id. Resolves the id to the live planet (which carries the cell key the store keys
     * on) via {@link GeneratedPlanets#findGenerated}, then runs the full destroy sequence. Returns a result the caller
     * can turn into feedback: {@link Result#DESTROYED} on success, or a reason it did not happen.
     *
     * @param requireUnclaimed if true, an owned planet is refused (the caller wants only wild planets destroyable).
     * @param credit the player credited with the destruction (the command sender now, the ki-blast owner in phase B),
     *               or null. Currently informational, kept in the signature so phase B and the raid path pass it too.
     */
    public static Result destroy(ServerLevel level, String planetId, ServerPlayer credit, boolean requireUnclaimed)
    {
        if (level == null || planetId == null)
        {
            return Result.NOT_FOUND;
        }
        MinecraftServer server = level.getServer();
        if (server == null)
        {
            return Result.NOT_FOUND;
        }
        // destructible rule, proven by the predicate: a fixed body (dimension-id key) never passes, so it can never be
        // destroyed here; a generated planet (sugen:) and a moon (sumoon:) both pass.
        if (!GeneratedPlanets.isDestructible(planetId))
        {
            return Result.NOT_DESTRUCTIBLE;
        }

        GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);
        if (claims.isDestroyed(planetId))
        {
            return Result.ALREADY_DESTROYED;
        }
        if (requireUnclaimed && claims.isClaimed(planetId))
        {
            return Result.CLAIMED;
        }
        // a personally-claimed planet guarded by its owner's live avatar is not destroyable by a claim-respecting caller.
        if (requireUnclaimed && claims.isPersonallyClaimed(planetId)
                && !PlanetOwnerAvatar.explodableNow(server, planetId))
        {
            return Result.CLAIMED;
        }

        GeneratedPlanets.Generated planet = GeneratedPlanets.findGenerated(server, planetId);
        if (planet == null)
        {
            // could not resolve the cell (no anchor near the planet), so we cannot key the destroyed record. The caller
            // reports "fly closer to it and try again".
            return Result.NOT_FOUND;
        }

        apply(server, claims, planet, credit);
        return Result.DESTROYED;
    }

    /**
     * The {@link Generated}-in-hand path, for a caller that already derived the planet (the ki blast that hit its body,
     * or a raid resolver). Skips the reverse lookup. The destructible and unclaimed checks still run.
     */
    public static Result destroy(ServerLevel level, GeneratedPlanets.Generated planet, ServerPlayer credit,
                                 boolean requireUnclaimed)
    {
        if (level == null || planet == null)
        {
            return Result.NOT_FOUND;
        }
        MinecraftServer server = level.getServer();
        if (server == null)
        {
            return Result.NOT_FOUND;
        }
        if (!GeneratedPlanets.isDestructible(planet.id))
        {
            return Result.NOT_DESTRUCTIBLE;
        }
        GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);
        if (claims.isDestroyed(planet.id))
        {
            return Result.ALREADY_DESTROYED;
        }
        if (requireUnclaimed && claims.isClaimed(planet.id))
        {
            return Result.CLAIMED;
        }
        // a personally-claimed planet guarded by its owner's live avatar is not destroyable by a claim-respecting caller.
        if (requireUnclaimed && claims.isPersonallyClaimed(planet.id)
                && !PlanetOwnerAvatar.explodableNow(server, planet.id))
        {
            return Result.CLAIMED;
        }
        apply(server, claims, planet, credit);
        return Result.DESTROYED;
    }

    // the shared mutation: mark, kill+evict, clear surface flag, invalidate courses, sync. Runs on the server thread.
    private static void apply(MinecraftServer server, GeneratedPlanetClaims claims,
                              GeneratedPlanets.Generated planet, ServerPlayer credit)
    {
        long gameTime = server.overworld().getGameTime();

        // A SYSTEM SUN has no surface of its own: destroying it takes out its WHOLE system. Handle it here so the one
        // destroy entry point (admin command, ki blast, future raid) cascades identically, and so the star's doom ramp
        // and shatter reuse the same DoomSequence a planet uses.
        if (GeneratedSystems.isSystemStar(planet.id))
        {
            applyStarCascade(server, claims, planet, credit, gameTime);
            return;
        }

        // read the owning guild BEFORE the unclaim below drops it: the RAID LOSS RECOVERY salvage is credited to
        // whoever owned the planet at the moment of destruction, and unclaim() would erase that answer. A wild
        // (unowned) planet returns null here and salvages nothing. Captured as a plain string so it survives the
        // unclaim untouched.
        String ownerGuildId = claims.owner(planet.id);

        claims.markDestroyed(planet.cellKey, planet.id, gameTime);

        // release any guild's ownership of the destroyed planet, keyed by the SAME planet id markDestroyed and
        // clearSurfaceGenerated use (the claims map is keyed by planet id, not the cell key). the destroyed record and
        // the ownership map are two INDEPENDENT stores, so without this a busted planet stays owned: the real
        // consequence is the leaked slot, since a guild is one-planet-per-guild and a claim on rubble would keep burning
        // that slot forever. dropped right after markDestroyed so both claim-store mutations happen together, and before
        // the single syncAll below so the cleared owner ships to clients in the same layout push.
        claims.unclaim(planet.id);
        // also release any PUBLIC personal-conquest claim on the destroyed planet, keyed by the same planet id, so a
        // busted planet is never left personally owned (which would keep it un-conquerable once a new planet forms in the
        // slot). Kept beside the guild unclaim so both claim-store mutations happen together before the syncAll below.
        claims.unclaimPersonal(planet.id);
        // drop any pending conquest boss and its record for the (now dead) surface, mirroring the garrison clear below.
        PlanetConquest.onPlanetDestroyed(server, planet.id);

        // kill every player on this planet's surface cell, then clear its stamped flag so a future planet re-stamps.
        killAndEvictOnSurface(server, planet.id);

        // RAID LOSS RECOVERY: capture a share of the destroyed surface (container contents + block item forms) into
        // the owning guild's salvage vault. Done HERE, before clearSurfaceGenerated, because this is the last point
        // at which the stamped surface still exists as readable blocks; clearing the flag lets a future planet re-
        // stamp fresh terrain over it. PlanetSalvage.capture NEVER throws (it wraps its whole body in a latched
        // try/catch), so the eviction above and the flag-clear + resync below always complete even if the walk fails.
        PlanetSalvage.capture(server, planet, ownerGuildId);

        claims.clearSurfaceGenerated(planet.id);

        // clear the wild GARRISON: discard any surviving visible defenders on the (now dead) surface and drop their
        // per-planet record, so a destroyed world never leaves an orphaned defender in the void or phantom claim-block
        // state behind. Same "no orphans" standard as the invisible clash holder. Never throws.
        PlanetGarrison.onPlanetDestroyed(server, planet.id);

        // invalidate any compass course pointing at this planet (defensive: courses today target fixed bodies, but the
        // ki blast and raid paths may set a generated-planet course later, and a course must never point at rubble).
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            if (planet.id.equals(PlanetCourse.courseKey(player)))
            {
                PlanetCourse.clearCourse(player);
            }
        }

        // dock the credited destroyer's alignment: blowing up a world is an evil act. a null credit (an admin
        // command, or the firer disconnected during the doom ramp) is a no-op, so only a live credited player pays.
        applyAlignmentPenalty(credit);

        // push the destroyed state to every client so they suppress the planet immediately, no relog.
        SpaceLayoutSync.syncAll();
    }

    // Destroy a system SUN and cascade to every planet of its system. The star has no landable surface, so instead of the
    // per-planet surface steps it: marks the star destroyed in the authoritative store (which suppresses the whole system
    // everywhere at once, since GeneratedSystems reads the same isDestroyed seam), resolves the system from the star's
    // position, and routes EACH of its planets through the ordinary single-planet destroy, so their claims, salvage,
    // surface eviction, garrison clear, course invalidation, persistence and cross-shard resync are byte-identical to
    // busting each planet on its own. The star mark is keyed by the star id as its own cell key, so like a moon it takes
    // no part in the wreck / regeneration machinery: a destroyed system simply stops existing.
    private static void applyStarCascade(MinecraftServer server, GeneratedPlanetClaims claims,
                                         GeneratedPlanets.Generated star, ServerPlayer credit, long gameTime)
    {
        // resolve the system BEFORE the mark (systemForStar reads it destroyed-inclusive, so order does not matter, but
        // reading first keeps the planet list independent of the mark).
        GeneratedSystems.System system = GeneratedSystems.systemForStar(server, star.id, star.position);

        // mark the sun destroyed: suppresses the star AND its whole system at the source everywhere (renderer, landing,
        // star map, hazard), no per-consumer filter.
        claims.markDestroyed(star.cellKey, star.id, gameTime);

        // destroy every planet of the system through the ordinary path. requireUnclaimed is false: a sun destruction takes
        // the whole system, claimed planets included (the star owned them). Each destroy runs its own surface eviction,
        // salvage, garrison clear, course invalidation and resync, so the cascade is exactly N single-planet destroys.
        if (system != null)
        {
            long epoch = OrbitClock.epochMillis();
            ServerLevel any = server.overworld();
            for (GeneratedSystems.SystemPlanet p : system.planets)
            {
                destroy(any, system.toGenerated(p, epoch), credit, false);
            }
        }

        // dock the credited destroyer's alignment once for the whole system, and push the star suppression to clients.
        applyAlignmentPenalty(credit);
        SpaceLayoutSync.syncAll();
    }

    // drop the credited destroyer's DMZ alignment by the configured penalty. alignment lives in DMZ, not the suite:
    // Resources.removeAlignment clamps the result into 0..100 for us, and bands read >60 GOOD, >40 NEUTRAL, else EVIL,
    // so a repeated destroyer slides toward EVIL. This is a DMZ-2.1.3 INTERNAL, and the workspace rule is that a mod
    // being present is not proof its API matches: a signature drift surfaces as NoSuchMethodError, which is a Throwable
    // not an Exception, so we catch Throwable and degrade to "no penalty, logged once" rather than break the destroy
    // path. Alignment has no standalone sync packet (it rides the whole-Resources sync), so after mutating we resend
    // ResourceSyncS2C or the player's HUD keeps showing the stale pre-penalty value until something else forces a sync.
    private static void applyAlignmentPenalty(ServerPlayer credit)
    {
        if (credit == null)
        {
            return;   // no credited player: nothing to dock.
        }
        int penalty = PlanetBusterModule.alignmentPenalty();
        if (penalty <= 0)
        {
            return;   // penalty disabled in config.
        }
        try
        {
            StatsProvider.get(StatsCapability.INSTANCE, credit)
                    .ifPresent(data -> data.getResources().removeAlignment(penalty));
            if (NetworkHandler.INSTANCE != null)
            {
                NetworkHandler.sendToPlayer(new ResourceSyncS2C(credit), credit);
            }
            credit.displayClientMessage(
                    Component.translatable("message.dmz_ragnarok.core.planet_alignment_penalty", penalty),
                    true);
        }
        catch (Throwable t)
        {
            if (ALIGNMENT_WARNED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.warn("[PlanetDestruction] Could not apply the alignment penalty "
                        + "(DMZ alignment API drift?); the destroy proceeds without it.", t);
            }
        }
    }

    // kill and evict every player currently standing on the destroyed planet's surface cell. The surface cell for a
    // planet id is a pure fold of the id (SurfaceTravelData records which planet each surface player is on), so we
    // match on the recorded planet id rather than on coordinates. The kill uses the datapack "planet_destroyed" damage
    // source so the death reads correctly in chat and bypasses armour/effects (a planet coming apart is unconditional).
    // If a player somehow survives the kill (a mod granting total immunity), we STILL teleport them out of the surface
    // dimension so nobody is stranded on a dead world.
    private static void killAndEvictOnSurface(MinecraftServer server, String planetId)
    {
        ServerLevel surface = SurfaceDimension.level(server);
        if (surface == null)
        {
            return;
        }
        DamageSource source = PlanetDestructionDamage.planetDestroyed(surface);
        // copy the list: killing/teleporting mutates the level's player list while we iterate.
        List<ServerPlayer> onSurface = List.copyOf(surface.players());
        for (ServerPlayer player : onSurface)
        {
            if (!planetId.equals(SurfaceTravelData.planetId(player)))
            {
                continue;
            }
            player.hurt(source, Float.MAX_VALUE);
            if (player.isAlive())
            {
                // the kill did not take (total-immunity mod, creative, ...): evict them so they are not stranded.
                evictToOverworld(server, player);
            }
            // clear the trip state either way: they are no longer on this (now destroyed) planet.
            SurfaceTravelData.clear(player);
        }
    }

    // teleport a surviving player out of the dead surface to the overworld spawn, ground-snapped.
    private static void evictToOverworld(MinecraftServer server, ServerPlayer player)
    {
        ServerLevel overworld = server.overworld();
        var spawn = overworld.getSharedSpawnPos();
        Vec3 snapped = SurfaceSnap.snap(overworld, spawn.getX() + 0.5, spawn.getZ() + 0.5);
        player.teleportTo(overworld, snapped.x, snapped.y, snapped.z, player.getYRot(), player.getXRot());
    }

    /** Restore a destroyed planet by id (admin restore). Does NOT bump the generation, so the same planet reappears. */
    public static Result restore(ServerLevel level, String planetId)
    {
        if (level == null || planetId == null)
        {
            return Result.NOT_FOUND;
        }
        MinecraftServer server = level.getServer();
        if (server == null)
        {
            return Result.NOT_FOUND;
        }
        GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);
        if (!claims.isDestroyed(planetId))
        {
            return Result.NOT_DESTROYED;
        }
        // find the cell key: the destroyed record is keyed by cell, so ask the store which cell holds this planet id.
        String cellKey = cellKeyOfDestroyed(claims, planetId);
        if (cellKey == null)
        {
            return Result.NOT_DESTROYED;
        }
        claims.restore(cellKey);
        SpaceLayoutSync.syncAll();
        return Result.RESTORED;
    }

    // the cell key of the destroyed record whose planet id matches, or null. Tiny scan over active rubble.
    private static String cellKeyOfDestroyed(GeneratedPlanetClaims claims, String planetId)
    {
        for (var e : claims.destroyedCells().entrySet())
        {
            if (e.getValue().destroyedPlanetId.equals(planetId))
            {
                return e.getKey();
            }
        }
        return null;
    }

    /** The outcome of a destroy/restore, so the caller can turn it into player feedback. */
    public enum Result
    {
        DESTROYED,
        RESTORED,
        NOT_DESTRUCTIBLE,
        CLAIMED,
        ALREADY_DESTROYED,
        NOT_DESTROYED,
        NOT_FOUND
    }
}
