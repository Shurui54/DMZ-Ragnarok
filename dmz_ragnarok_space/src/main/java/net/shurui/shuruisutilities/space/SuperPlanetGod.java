package net.shurui.shuruisutilities.space;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;
import com.dragonminez.server.events.DragonBallsHandler;
import com.dragonminez.server.world.data.DragonBallSavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.core.misc.SafeSpotResolver;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.world.space.SurfaceSnap;

/**
 * Server-side controller for a SUPER body's single guardian: the randomly chosen Destroyer God a player must beat to
 * claim that body's Super Dragon Ball. It follows the {@link PlanetGarrison} recipe (attributes, the {@code
 * dmz_npc_defense} curve, the {@code DBSagasEntity} battle power / AI tier / ki skills, {@code dmz_stats_configured} last)
 * but spawns exactly ONE god at the surface centre, and on that god's death PLACES the matching {@code dball<star>_super}
 * ball block. All persisted progress lives in {@link SuperPlanetData}; the god itself carries only a marker.
 *
 * <h3>The drop is on DEATH, the claim is on COLLECTION</h3>
 * A god's death only DROPS the ball (marks the body {@code ballDropped}); it does NOT claim the body. The body stays
 * stone-grey and LANDABLE while the ball sits uncollected, so a player who missed the drop can fly back for it, and the
 * god is not respawned (it is already beaten). The body only becomes {@code claimed} (dragon-ball render, not landable)
 * when a player actually COLLECTS the ball, handled in {@link SuperPlanetLifecycle}. This split is what removes the old
 * soft-lock where a ball nobody picked up was stranded on a body that could no longer be landed on.
 *
 * <h3>Edge cases (the ball is never lost and never doubled)</h3>
 * <ul>
 *   <li><b>Double death / respawn:</b> the {@code ballDropped} flag in {@link SuperPlanetData} is the single guard. Once
 *       set (or once {@code claimed}), a second death (a duplicate that slipped through, or a re-killed respawn) places
 *       nothing, and {@link #ensureGuardian} refuses to respawn a god, so no second ball can ever be minted.</li>
 *   <li><b>Void death / despawn:</b> the ball is placed at the surface CELL CENTRE (pinned solid), never at the death
 *       position, so a god that wanders off the rim and dies in the void still drops a reachable ball. The god is
 *       {@code setPersistenceRequired} so it never despawns from inactivity.</li>
 *   <li><b>Chunk unload / lost entity:</b> aliveness is never stored. {@link #ensureGuardian} reconciles on every
 *       landing: only when the ball is neither dropped nor collected, and no living god for this body is on the loaded
 *       surface, does it (re)spawn the SAME god (chosen deterministically from the star), so a lost god self-heals
 *       instead of soft-locking, while a beaten-but-uncollected body is left alone.</li>
 * </ul>
 */
public final class SuperPlanetGod
{
    private SuperPlanetGod()
    {
    }

    // marker written on the god so its death, and the reconcile scan, can recognise it and route to the right super body.
    private static final String GOD_FLAG = "su_super_defender";
    private static final String GOD_PLANET = "su_super_defender_planet";

    // the suite-wide opt-out flag, stamped LAST so DMZ's join handler leaves our numbers alone (see PlanetGarrison).
    private static final String DMZ_STATS_CONFIGURED = "dmz_stats_configured";
    // raw NPC-defense key read by SU's own mitigation handler (never also set ARMOR).
    private static final String NPC_DEFENSE_KEY = "dmz_npc_defense";

    // combat numbers derived from battle power through the same divisors the garrison uses, so a single configurable
    // battle-power band drives health, melee and defense. A god's band is far higher (see PlanetSpawnModule), so at the
    // default band it lands tens of thousands of health.
    private static final double HEALTH_BP_DIVISOR = 50.0;
    private static final double MELEE_BP_DIVISOR = 500.0;
    private static final double DEFENSE_BP_DIVISOR = 1000.0;
    private static final double MIN_HEALTH = 100.0;
    private static final double MIN_MELEE = 10.0;

    // how far, in blocks, the god spawns from the surface centre. The player lands at that exact centre column (see
    // SpaceTravelModule.landOnSuper -> SurfaceStamp.ensureAndLandingPos), so spawning the god there put it inside the
    // player; this offsets it a short walk away instead. 24 is comfortably clear of any NPC's footprint (even a large
    // saga-boss model), yet on the largest (500-wide) surface it is still close enough that the player finds the god
    // immediately rather than hunting the rim.
    private static final double GOD_SPAWN_DISTANCE = 24.0;
    // cap the offset at this fraction of the disc half-extent so on a small planet the god never lands past the feathered
    // rim in the void, where SafeSpotResolver (this dimension is not "space", so it has real terrain) would relocate it
    // off-planet. At half = 48 (a 100-wide surface, minus the garrison's 2-block inset) this caps the offset near 29
    // blocks, well inside the solid disc.
    private static final double GOD_SPAWN_MAX_DISC_FRACTION = 0.6;

    private static final AtomicBoolean DMZ_STAT_WARNED = new AtomicBoolean(false);
    private static final AtomicBoolean SPAWN_WARNED = new AtomicBoolean(false);
    private static final AtomicBoolean BALL_WARNED = new AtomicBoolean(false);

    /**
     * Ensure the Destroyer God for this super body exists, spawning it the first time a player lands and reconciling a
     * lost one on later landings. No-op if the feature is disabled, the ball has already dropped, or a living god for this
     * body is already on the surface. Runs on the server thread. Never throws.
     */
    public static void ensureGuardian(MinecraftServer server, ServerLevel surface, String superId)
    {
        if (server == null || surface == null || superId == null || !SuperPlanetPositions.isSuper(superId))
        {
            return;
        }
        if (!PlanetSpawnModule.superGodEnabled())
        {
            // gods disabled: place the ball immediately so the super set is still obtainable rather than soft-locked.
            dropBall(server, surface, superId);
            return;
        }
        SuperPlanetData data = SuperPlanetData.get(server);
        if (data.isClaimed(superId) || data.isBallDropped(superId))
        {
            // already beaten: either the ball is collected (claimed) or it is dropped and waiting to be picked up.
            // Either way the god must NOT respawn, or beating the fresh god would mint a SECOND ball for this star.
            return;
        }
        if (countLiveGods(surface, superId) > 0)
        {
            return; // still guarding; do not spawn a second.
        }
        try
        {
            spawnGod(surface, superId);
        }
        catch (Throwable t)
        {
            if (SPAWN_WARNED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.warn("[SuperPlanet] Failed to spawn the Destroyer God for {}; dropping its ball so "
                        + "the super set is still obtainable.", superId, t);
            }
            // failure-open: place the ball rather than leave the body permanently unwinnable.
            dropBall(server, surface, superId);
        }
    }

    // spawn one god of the deterministic face NEAR (not on top of) the surface centre, stat-stamp it, mark it, add it to
    // the world.
    private static void spawnGod(ServerLevel surface, String superId)
    {
        int star = SuperPlanetPositions.starOf(superId);
        LivingEntity god = SuperPlanetGodRoster.create(surface, star);
        if (god == null)
        {
            // no god face available (version drift): drop the ball so the body is still winnable.
            dropBall(surface.getServer(), surface, superId);
            return;
        }
        Vec3 centre = SurfaceDimension.cellCentre(superId);
        RandomSource random = surface.getRandom();

        // offset the god a set distance from the centre in a random direction, then resolve a safe standing spot on the
        // pinned-solid surface, following the PlanetGarrison.spawnOne recipe (scatter within the disc, then
        // SafeSpotResolver.resolve). The offset is capped to a fraction of the disc so a small planet never puts it in the
        // void past the rim, and it stays well inside the confine goal's half-extent, so setHomePlanet does not fight it.
        int half = Math.max(1, GeneratedPlanetClaims.stampedSizeForId(surface.getServer(), superId) / 2 - 2);
        double offset = Math.min(GOD_SPAWN_DISTANCE, half * GOD_SPAWN_MAX_DISC_FRACTION);
        double angle = random.nextDouble() * Math.PI * 2.0;
        double gx = centre.x + Math.cos(angle) * offset;
        double gz = centre.z + Math.sin(angle) * offset;
        SafeSpotResolver.Result spot = SafeSpotResolver.resolve(surface, gx, centre.y, gz);
        god.moveTo(spot.x, spot.y, spot.z, random.nextFloat() * 360.0F, 0.0F);

        applyGodStats(god, rollBattlePower(random));

        god.getPersistentData().putBoolean(GOD_FLAG, true);
        god.getPersistentData().putString(GOD_PLANET, superId);

        // the god IS a garrison-defender chassis, so give it a home planet: its confine goal then clamps it to this super
        // body's surface disc (keyed by the super id, exactly where it was placed) instead of wandering off into the void.
        if (god instanceof PlanetGarrisonDefenderEntity defender)
        {
            defender.setHomePlanet(superId);
        }

        if (god instanceof Mob mob)
        {
            mob.setPersistenceRequired();
        }

        if (!surface.addFreshEntity(god) || god.isRemoved() || !god.isAlive())
        {
            LoggingHandler.sulog.warn("[SuperPlanet] The Destroyer God for {} failed to spawn.", superId);
        }
    }

    private static double rollBattlePower(RandomSource random)
    {
        double min = PlanetSpawnModule.superGodBattlePowerMin();
        double max = PlanetSpawnModule.superGodBattlePowerMax();
        if (min >= max)
        {
            return min;
        }
        return min + random.nextDouble() * (max - min);
    }

    // push the god's combat numbers on, following PlanetGarrison.applyDefenderStats. Every DMZ-specific call is wrapped so
    // an API drift degrades to a plain melee god, never a crash.
    private static void applyGodStats(LivingEntity god, double battlePower)
    {
        double health = Math.max(MIN_HEALTH, battlePower / HEALTH_BP_DIVISOR);
        double melee = Math.max(MIN_MELEE, battlePower / MELEE_BP_DIVISOR);
        double defense = battlePower / DEFENSE_BP_DIVISOR;

        setAttribute(god, Attributes.MAX_HEALTH, health);
        god.setHealth((float) health);
        setAttribute(god, Attributes.ATTACK_DAMAGE, melee);

        if (defense > 0.0)
        {
            god.getPersistentData().putDouble(NPC_DEFENSE_KEY, defense);
        }

        if (god instanceof DBSagasEntity saga)
        {
            try
            {
                saga.setBattlePower((int) Math.round(Math.min(battlePower, Integer.MAX_VALUE)));
                saga.setAiTierById(DBSagasEntity.AiTier.SIMPLE.ordinal() + 1);
                saga.getSkillPool().clear();
                saga.addKiSkill(DBSagasEntity.KiSkillType.GENERIC_KI_WAVE, 60, 1.0F);
                saga.addKiSkill(DBSagasEntity.KiSkillType.KI_LASER, 45, 1.0F);
            }
            catch (Throwable t)
            {
                if (DMZ_STAT_WARNED.compareAndSet(false, true))
                {
                    LoggingHandler.sulog.warn("[SuperPlanet] DragonMineZ saga stat/skill API drift; the Destroyer God "
                            + "fights with basic stats only.", t);
                }
            }
        }

        god.getPersistentData().putBoolean(DMZ_STATS_CONFIGURED, true);
    }

    private static void setAttribute(LivingEntity entity, Attribute attribute, double value)
    {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null && value > 0.0)
        {
            instance.setBaseValue(value);
        }
    }

    /**
     * Route a dying entity to its super body: if it carries the god marker and its body's ball has not already dropped,
     * place the ball block and mark the body dropped-but-uncollected. Called from the LivingDeathEvent handler. A non-god
     * death is a cheap no-op. Never throws.
     */
    public static void onGodDeath(MinecraftServer server, LivingEntity entity)
    {
        if (server == null || entity == null)
        {
            return;
        }
        if (!entity.getPersistentData().getBoolean(GOD_FLAG))
        {
            return;
        }
        String superId = entity.getPersistentData().getString(GOD_PLANET);
        if (!SuperPlanetPositions.isSuper(superId))
        {
            return;
        }
        ServerLevel surface = SurfaceDimension.level(server);
        if (surface == null)
        {
            return;
        }
        dropBall(server, surface, superId);
    }

    // place this super body's ball block at the surface cell centre, once. The ballDropped flag guards against a double
    // or respawn-kill drop. Placing at the CENTRE (pinned solid) not the death position means a void/off-rim death still
    // drops a reachable ball. The body is NOT claimed here and stays grey + landable: it becomes claimed only when a
    // player collects the ball (see SuperPlanetLifecycle), so a missed drop can still be returned for. Never throws.
    private static void dropBall(MinecraftServer server, ServerLevel surface, String superId)
    {
        if (server == null || surface == null)
        {
            return;
        }
        SuperPlanetData data = SuperPlanetData.get(server);
        if (!data.markBallDropped(superId))
        {
            return; // already dropped or claimed: guard against a double death / respawn re-kill.
        }
        // no client resync here: the body stays grey and landable in the dropped-but-uncollected state, so nothing the
        // client draws (its claimed flag) has changed. The repaint to a dragon ball happens on COLLECTION.
        try
        {
            int star = SuperPlanetPositions.starOf(superId);
            Block ball = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("dragonminez", "dball" + star + "_super"));
            if (ball == null)
            {
                if (BALL_WARNED.compareAndSet(false, true))
                {
                    LoggingHandler.sulog.warn("[SuperPlanet] Super ball block dragonminez:dball{}_super is not "
                            + "registered; cannot drop the ball for {}.", star, superId);
                }
                return;
            }
            Vec3 centre = SurfaceDimension.cellCentre(superId);
            // Resolve a sky-exposed surface at the body's cell centre. skyExposedSurface forces the column to FULL (so
            // the read is a real surface, never the world floor) and rejects a column that cannot see the sky.
            BlockPos at = SurfaceSnap.skyExposedSurface(surface, (int) Math.floor(centre.x), (int) Math.floor(centre.z));
            if (at == null)
            {
                // The cell centre is where players land, so it is effectively always open sky and this fallback does
                // not fire in practice; but the ball drop must NEVER soft-lock the super set, so if the sky check
                // somehow rejects the centre we still seat the ball on the forced-FULL surface rather than leave the
                // body permanently unwinnable.
                Vec3 snapped = SurfaceSnap.snap(surface, centre.x, centre.z);
                at = BlockPos.containing(snapped.x, snapped.y, snapped.z);
            }
            // clear whatever is at the landing spot first (a plant, snow) so the ball block always sets cleanly.
            if (!surface.getBlockState(at).isAir())
            {
                surface.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
            }
            BlockState state = ball.defaultBlockState();
            surface.setBlock(at, state, 3);
            // Register the ball with DMZ so the radar can see it. A raw setBlock does NOT fire
            // BlockEvent.EntityPlaceEvent, which is the ONLY hook DragonBallsHandler registers balls through, so
            // without this the god-dropped Super ball is a real block in the world that is invisible to every Super
            // radar and to the cross-shard radar view. This mirrors what ApophisSummonModule does for Black Star and
            // what DMZ's own onBlockPlace does for a player-placed ball: add the exact position to the set's active
            // list for this star, mark the data dirty, and resync the radar packet.
            try
            {
                DragonBallSavedData db = DragonBallSavedData.get(surface);
                List<BlockPos> activeForStar = db.getActiveBalls("super").get(star);
                if (activeForStar != null && !activeForStar.contains(at))
                {
                    activeForStar.add(at);
                    db.setDirty();
                }
                DragonBallsHandler.syncRadar(surface);
            }
            catch (Throwable radarBookkeeping)
            {
                // The ball block is already placed; a failure to register it must not undo the drop, only cost the
                // blip until the next radar resync picks it up on a later event.
                if (BALL_WARNED.compareAndSet(false, true))
                {
                    LoggingHandler.sulog.warn("[SuperPlanet] Placed the super ball for {} but failed to register it "
                            + "with the radar.", superId, radarBookkeeping);
                }
            }
        }
        catch (Throwable t)
        {
            if (BALL_WARNED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.warn("[SuperPlanet] Failed to place the super ball for {}.", superId, t);
            }
        }
    }

    /** True if a living Destroyer God for this super body is on the loaded surface right now. For the admin status readout. */
    public static boolean godAlive(ServerLevel surface, String superId)
    {
        return surface != null && countLiveGods(surface, superId) > 0;
    }

    // count the living Destroyer Gods for a super body on the surface right now, matched on the marker + id.
    private static int countLiveGods(ServerLevel surface, String superId)
    {
        int count = 0;
        for (Entity entity : surface.getAllEntities())
        {
            if (entity.getPersistentData().getBoolean(GOD_FLAG)
                    && superId.equals(entity.getPersistentData().getString(GOD_PLANET))
                    && entity.isAlive() && !entity.isRemoved())
            {
                count++;
            }
        }
        return count;
    }
}
