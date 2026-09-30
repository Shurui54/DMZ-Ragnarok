package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dragonminez.common.init.entities.IBattlePower;
import com.dragonminez.common.init.entities.sagas.DBSagasEntity;
import com.dragonminez.common.init.EntityAttributes;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.core.misc.SafeSpotResolver;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * PUBLIC (keyless) planet conquest: the "beat the world's champion, then own it yourself" flow, modelled on Dragon Block
 * Noea's planet claim but recreated from behaviour only. It sits ON TOP of the existing wild {@link PlanetGarrison}: a
 * player (or several) clears every hostile garrison NPC of an unowned generated planet, at which point ONE DEFENDER BOSS,
 * a theme-fitting DragonMineZ main character scaled to within a few percent of the triggering player's own strength, is
 * spawned near them. Defeating that boss claims the planet PERSONALLY, to a single owner uuid, with no guild involved, so
 * it works in singleplayer and on a keyless server where guilds (a key feature) are absent.
 *
 * <h2>Why a native DragonMineZ saga entity for the boss</h2>
 * The boss is spawned as a real DragonMineZ {@code saga_*} entity resolved by {@link ResourceLocation} (never compiled
 * against, like {@link PlanetGarrisonRoster}'s DMZ faces), so it renders with DragonMineZ's OWN renderer and models,
 * which ship in the DragonMineZ jar. That deliberately avoids the retired, server-streamed rgnpc set the wild garrison's
 * rgnpc faces use, so a client can always draw the boss. Its transformation chain is DISABLED
 * ({@link DBSagasEntity#setTransformationDisabled(boolean)}) so it never swaps itself for a next-form entity mid-fight,
 * which would change its uuid and break the by-identity defeat tracking; it simply fights and dies.
 *
 * <h2>Interaction with the guild claim (no conflict, ever)</h2>
 * The durable result, a personal claim, lives in {@link GeneratedPlanetClaims#setPersonalClaim} beside the guild claims,
 * and {@link GeneratedPlanetClaims#isOwned} treats a planet held by EITHER mechanism as taken. The guild
 * {@code /spaceplanet claim} refuses a personally-owned planet and this flow refuses a guild-owned one, so a planet is
 * never in both. If a guild claims a planet by command while a conquest boss is still alive, {@link #onPlanetClaimed}
 * despawns the boss and drops the pending record; if the boss is beaten first, the planet is personally owned and the
 * guild command then refuses it. The conquest boss spawns on EVERY server (keyed or not) because it is public; the guild
 * path is simply the alternative a keyed server also has.
 *
 * <p>Every DragonMineZ touchpoint is wrapped so an API drift degrades to a plainer boss (or no boss) and never crashes,
 * matching {@link PlanetGarrison}'s discipline. Generated planets and moons only: a fixed main-planet dimension has no
 * generated claim, so this never fires there.
 */
public final class PlanetConquest
{
    private PlanetConquest()
    {
    }

    // ForgeData markers written on the boss so a death or cleanup sweep can recognise it and route to the right planet.
    // Survive a chunk reload (ForgeData is saved with the entity), like PlanetGarrison's defender markers.
    static final String BOSS_FLAG = "su_conquest_boss";
    static final String BOSS_PLANET = "su_conquest_boss_planet";
    static final String BOSS_TRIGGER = "su_conquest_boss_trigger";

    // tell DragonMineZ these stats are hand-managed so its entity-join stat init does not overwrite them, and the raw
    // NPC-defense key SU's own mitigation handler reads. Written exactly as PlanetGarrison / the raid bosses write them.
    private static final String DMZ_STATS_CONFIGURED = "dmz_stats_configured";
    private static final String NPC_DEFENSE_KEY = "dmz_npc_defense";

    // A boss's health anchors to the triggering player's max health; its melee, defense and ki derive from the player's
    // battle power through these divisors, the SAME scale PlanetGarrison uses so an NPC's combat numbers stay consistent
    // across the whole space module. Each is independently rolled within +/- the configured tolerance for variety.
    private static final double MELEE_BP_DIVISOR = 500.0;    // bp 50,000 -> 100 melee
    private static final double DEFENSE_BP_DIVISOR = 1000.0; // bp 50,000 -> 50 raw NPC defense
    private static final double KI_BP_DIVISOR = 500.0;       // ki damage on the melee scale
    private static final double MIN_HEALTH = 20.0;
    private static final double MIN_MELEE = 2.0;

    private static final String DMZ = "dragonminez";

    // config, baked by PlanetSpawnModule.bakeConfig. volatile: read on the server thread, written on the config thread.
    private static volatile boolean enabled = true;
    private static volatile double statTolerance = 0.05;
    private static volatile double spawnDistance = 6.0;
    private static volatile Map<SurfaceStamp.Theme, List<String>> defenderTable = defaultTable();

    // one-shot latch so a DragonMineZ stat/skill API drift is logged ONCE across the whole run, never per boss.
    private static final AtomicBoolean DMZ_WARNED = new AtomicBoolean(false);

    /**
     * Push the baked config in. {@code table} maps each theme to its ordered list of DragonMineZ {@code saga_*} boss ids;
     * a null/empty list for a theme falls back to the OTHERWORLD list, and failing that to a hardcoded default, so a boss
     * can always be picked.
     */
    public static void setConfig(boolean on, double tolerance, double distance,
                                 Map<SurfaceStamp.Theme, List<String>> table)
    {
        enabled = on;
        statTolerance = Math.max(0.0, tolerance);
        spawnDistance = Math.max(0.0, distance);
        defenderTable = (table == null || table.isEmpty()) ? defaultTable() : table;
    }

    /** Whether the public conquest flow is enabled (the operator switch). */
    public static boolean isEnabled()
    {
        return enabled;
    }

    /**
     * Called when the LAST hostile garrison NPC of a planet is defeated (see {@link PlanetGarrison#onDefenderDeath}).
     * Spawns the one defender boss near the triggering player, unless the feature is off, the planet is already owned or
     * destroyed, a boss is already pending, or there is no player to scale to. Never throws.
     */
    public static void onGarrisonCleared(MinecraftServer server, ServerLevel surface, String planetId,
                                         ServerPlayer trigger)
    {
        if (!enabled || server == null || surface == null || planetId == null || planetId.isEmpty())
        {
            return;
        }
        spawnBoss(server, surface, planetId, trigger, false);
    }

    /**
     * Spawn the conquest boss for a planet. Returns true if a boss was spawned. Refuses (returns false) if the feature
     * is off, the planet is not a generated/moon body, it is already owned or destroyed, a boss is already alive/pending,
     * or no anchor player is available to scale to. The command and the debug force-spawn call this with {@code force} to
     * bypass the already-cleared / already-pending guards where appropriate. Never throws.
     */
    public static boolean spawnBoss(MinecraftServer server, ServerLevel surface, String planetId, ServerPlayer anchor,
                                    boolean force)
    {
        try
        {
            if (!enabled || server == null || surface == null || planetId == null || planetId.isEmpty() || anchor == null)
            {
                return false;
            }
            if (!GeneratedPlanets.isDestructible(planetId))
            {
                return false; // fixed main-planet dimensions are not conquerable this way; generated planets and moons are.
            }
            GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);
            if (claims.isOwned(planetId) || claims.isDestroyed(planetId))
            {
                return false;
            }
            // a boss already pending? if a live one exists, do not spawn a second. A stale record whose entity is gone is
            // reconciled (cleared) so a fresh boss can appear.
            if (bossAlive(server, surface, planetId))
            {
                return false;
            }
            claims.clearConquestBoss(planetId);

            RandomSource random = surface.getRandom();
            SurfaceStamp.Theme theme = GeneratedPlanetClaims.stampedThemeForId(server, planetId);
            LivingEntity boss = createBoss(surface, theme, random);
            if (boss == null)
            {
                return false; // no boss id resolved (a DMZ version drift on every id in the theme list); logged once below.
            }

            placeNear(surface, boss, anchor, random);
            applyBossStats(boss, anchor, random);

            boss.getPersistentData().putBoolean(BOSS_FLAG, true);
            boss.getPersistentData().putString(BOSS_PLANET, planetId);
            boss.getPersistentData().putString(BOSS_TRIGGER, anchor.getUUID().toString());
            if (boss instanceof Mob mob)
            {
                mob.setPersistenceRequired();
            }

            if (!surface.addFreshEntity(boss) || boss.isRemoved() || !boss.isAlive())
            {
                LoggingHandler.sulog.warn("[PlanetConquest] Boss for planet {} failed to spawn; planet stays claimable.",
                        planetId);
                return false;
            }
            claims.setConquestBoss(planetId, boss.getUUID());
            announceSpawn(surface, planetId, boss);
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[PlanetConquest] Failed spawning the conquest boss for planet {}; skipping.",
                    planetId, t);
            return false;
        }
    }

    /**
     * Route every server-side death here (from {@link PlanetSpawnModule}). If the dead entity is a conquest boss, grant
     * the planet as a PERSONAL claim to the player who dealt the killing blow (falling back to the player who triggered
     * the boss), clear the boss record and the garrison bookkeeping, and announce it. A non-boss death is a cheap no-op.
     * Never throws.
     */
    public static void onLivingDeath(MinecraftServer server, LivingEntity entity, DamageSource source)
    {
        if (server == null || entity == null)
        {
            return;
        }
        if (!entity.getPersistentData().getBoolean(BOSS_FLAG))
        {
            return;
        }
        try
        {
            String planetId = entity.getPersistentData().getString(BOSS_PLANET);
            if (planetId.isEmpty())
            {
                return;
            }
            GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);
            claims.clearConquestBoss(planetId);

            // the planet may have been claimed by a guild while the boss was alive (that path despawns the boss, but
            // guard anyway) or destroyed: in either case there is nothing to grant.
            if (claims.isOwned(planetId) || claims.isDestroyed(planetId))
            {
                return;
            }

            ServerPlayer claimer = resolveClaimer(server, entity, source);
            if (claimer == null)
            {
                // no player to credit (a boss killed by the environment with the trigger long offline): leave the planet
                // unclaimed but now boss-free, so the next player to clear/trigger it can conquer it.
                broadcastOnSurface(server, planetId, Component.translatable(
                        "message.dmz_ragnarok.core.planet_conquest_defeated_unclaimed",
                        Component.literal(GeneratedPlanets.nameFor(planetId))));
                return;
            }

            claims.setPersonalClaim(planetId, claimer.getUUID());
            // capture the new owner's look + stats now, while they are online, so their defender AVATAR can render as them
            // and fight at their strength even after they log off or move to another shard (see PlanetOwnerAvatar).
            PlanetOwnerAvatar.captureSnapshot(server, planetId, claimer);
            // the garrison is dead by definition here; clear its bookkeeping so it does not linger on an owned world.
            PlanetGarrison.onPlanetClaimed(server, planetId);
            SpaceLayoutSync.syncAll();

            String planetName = GeneratedPlanets.nameFor(planetId);
            claimer.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.planet_conquest_claimed",
                    Component.literal(planetName)));
            broadcastOnSurfaceExcept(server, planetId, claimer, Component.translatable(
                    "message.dmz_ragnarok.core.planet_conquest_claimed_other",
                    claimer.getDisplayName(), Component.literal(planetName)));
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[PlanetConquest] Failed resolving a conquest boss defeat; continuing.", t);
        }
    }

    /** Drop a planet's conquest state on DESTRUCTION: discard the boss, and clear both the boss and personal-claim rows. */
    public static void onPlanetDestroyed(MinecraftServer server, String planetId)
    {
        clearConquest(server, planetId, true);
    }

    /** Drop a planet's conquest state when a GUILD claims it first: discard the boss and drop the pending boss record. */
    public static void onPlanetClaimed(MinecraftServer server, String planetId)
    {
        clearConquest(server, planetId, false);
    }

    // discard any live conquest boss for a planet and clear the boss record; on destruction also drop the personal claim.
    private static void clearConquest(MinecraftServer server, String planetId, boolean alsoDropPersonalClaim)
    {
        if (server == null || planetId == null || planetId.isEmpty())
        {
            return;
        }
        try
        {
            discardBoss(server, planetId);
            // also remove any live owner-avatar defender for the planet (a guild just claimed it, or it was destroyed).
            PlanetOwnerAvatar.discardAvatar(server, planetId);
            GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);
            claims.clearConquestBoss(planetId);
            if (alsoDropPersonalClaim)
            {
                // unclaimPersonal also drops the avatar snapshot and defeat window for the destroyed planet.
                claims.unclaimPersonal(planetId);
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[PlanetConquest] Failed clearing conquest state for planet {}; continuing.",
                    planetId, t);
        }
    }

    // ---- boss creation + scaling ------------------------------------------------------------------------------------

    // resolve a boss face for the planet's theme and mint a fresh, un-added DragonMineZ saga entity for it. Tries each id
    // in the theme's list (shuffled) until one resolves to a registered LivingEntity, so a single missing id (version
    // drift) falls through to the next rather than yielding no boss. Returns null only if EVERY id for the theme (and the
    // OTHERWORLD fallback) is missing, which is logged once.
    private static LivingEntity createBoss(ServerLevel level, SurfaceStamp.Theme theme, RandomSource random)
    {
        List<String> ids = idsForTheme(theme);
        List<String> shuffled = new ArrayList<>(ids);
        java.util.Collections.shuffle(shuffled, new java.util.Random(random.nextLong()));
        for (String id : shuffled)
        {
            LivingEntity boss = createDmz(level, id);
            if (boss != null)
            {
                return boss;
            }
        }
        return null;
    }

    // the configured boss id list for a theme, falling back to OTHERWORLD then a hardcoded default so a boss is always
    // pickable even if an operator blanked a theme's list.
    private static List<String> idsForTheme(SurfaceStamp.Theme theme)
    {
        Map<SurfaceStamp.Theme, List<String>> table = defenderTable;
        List<String> ids = theme == null ? null : table.get(theme);
        if (ids == null || ids.isEmpty())
        {
            ids = table.get(SurfaceStamp.Theme.OTHERWORLD);
        }
        if (ids == null || ids.isEmpty())
        {
            ids = defaultTable().get(SurfaceStamp.Theme.OTHERWORLD);
        }
        return ids;
    }

    // resolve a DragonMineZ entity id to a fresh LivingEntity, or null if it is not registered / not living. Never throws.
    private static LivingEntity createDmz(ServerLevel level, String id)
    {
        try
        {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(DMZ, id));
            if (type == null)
            {
                return null;
            }
            Entity e = type.create(level);
            if (!(e instanceof LivingEntity living))
            {
                if (e != null)
                {
                    e.discard();
                }
                return null;
            }
            return living;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    // place the boss on safe ground near the anchor player, a configurable distance out at a random bearing, snapped to
    // the surface so it never lands inside a wall or hanging over the void.
    private static void placeNear(ServerLevel surface, LivingEntity boss, ServerPlayer anchor, RandomSource random)
    {
        double angle = random.nextDouble() * Math.PI * 2.0;
        double dx = Math.cos(angle) * spawnDistance;
        double dz = Math.sin(angle) * spawnDistance;
        SafeSpotResolver.Result spot =
                SafeSpotResolver.resolve(surface, anchor.getX() + dx, anchor.getY(), anchor.getZ() + dz);
        boss.moveTo(spot.x, spot.y, spot.z, random.nextFloat() * 360.0F, 0.0F);
    }

    // stat the boss to within the configured tolerance of the triggering player: health anchors to the player's own max
    // health, and battle power to the player's battle power, so the boss reads as an even match on a scouter. Melee,
    // defense and ki derive from that battle power through the shared divisors. Each number is independently rolled inside
    // [1 - tol, 1 + tol]. The DragonMineZ saga chassis also gets a real AI tier, a ranged skill pool, and its
    // transformation chain disabled so its identity is stable for the by-uuid defeat tracking. Never throws.
    private static void applyBossStats(LivingEntity boss, ServerPlayer anchor, RandomSource random)
    {
        double playerBp = playerBattlePower(anchor);
        double playerHp = Math.max(MIN_HEALTH, anchor.getMaxHealth());
        double effectiveBp = playerBp * roll(random);

        double health = Math.max(MIN_HEALTH, playerHp * roll(random));
        double melee = Math.max(MIN_MELEE, (effectiveBp / MELEE_BP_DIVISOR) * roll(random));
        double defense = (effectiveBp / DEFENSE_BP_DIVISOR) * roll(random);
        double ki = (effectiveBp / KI_BP_DIVISOR) * roll(random);

        setAttribute(boss, Attributes.MAX_HEALTH, health);
        boss.setHealth((float) health);
        setAttribute(boss, Attributes.ATTACK_DAMAGE, melee);
        if (defense > 0.0)
        {
            boss.getPersistentData().putDouble(NPC_DEFENSE_KEY, defense);
        }
        setKiBlastDamage(boss, (float) ki);

        // battle power so a scouter reads the boss as an even match. Set on any IBattlePower chassis (a DBSagasEntity is
        // one), like PlanetGarrison does.
        if (boss instanceof IBattlePower ibp)
        {
            try
            {
                ibp.setBattlePower((int) Math.round(Math.min(effectiveBp, Integer.MAX_VALUE)));
            }
            catch (Throwable t)
            {
                warnDrift(t);
            }
        }
        if (boss instanceof DBSagasEntity saga)
        {
            try
            {
                // a main-character boss fights at the ADVANCED tier (id = ordinal + 1), not the garrison's SIMPLE.
                saga.setAiTierById(DBSagasEntity.AiTier.ADVANCED.ordinal() + 1);
                saga.getSkillPool().clear();
                saga.addKiSkill(DBSagasEntity.KiSkillType.GENERIC_KI_WAVE, 60, 1.0F);
                saga.addKiSkill(DBSagasEntity.KiSkillType.KI_LASER, 40, 1.0F);
                saga.addKiSkill(DBSagasEntity.KiSkillType.FINAL_FLASH, 120, 1.0F);
                // keep the identity stable: never let it transform into a next-form entity (new uuid), which would break
                // the by-uuid defeat routing. It simply fights and dies.
                saga.setTransformationDisabled(true);
                // guard against flying off the surface disc into the void: a wild-planet boss defends the ground.
                saga.setCanFly(false);
            }
            catch (Throwable t)
            {
                warnDrift(t);
            }
        }

        // stamp the opt-out flag LAST so DragonMineZ's join handler leaves every number above untouched.
        boss.getPersistentData().putBoolean(DMZ_STATS_CONFIGURED, true);
    }

    // a multiplier inside [1 - tolerance, 1 + tolerance].
    private static double roll(RandomSource random)
    {
        double tol = statTolerance;
        return 1.0 + (random.nextDouble() * 2.0 - 1.0) * tol;
    }

    // the anchor player's DragonMineZ battle power (current form), or a small floor if it cannot be read so the boss is
    // still a real fight rather than a one-shot.
    private static double playerBattlePower(ServerPlayer player)
    {
        try
        {
            StatsData stats = StatsProvider.<StatsData>get(StatsCapability.INSTANCE, player).resolve().orElse(null);
            if (stats != null)
            {
                double bp = stats.getBattlePowerExact();
                if (bp > 0.0 && !Double.isNaN(bp))
                {
                    return bp;
                }
            }
        }
        catch (Throwable t)
        {
            warnDrift(t);
        }
        return 5000.0;
    }

    private static void setKiBlastDamage(LivingEntity entity, float value)
    {
        try
        {
            AttributeInstance instance = entity.getAttribute(EntityAttributes.KI_BLAST_DAMAGE.get());
            if (instance != null && value > 0.0F)
            {
                instance.setBaseValue(value);
            }
        }
        catch (Throwable t)
        {
            warnDrift(t);
        }
    }

    private static void setAttribute(LivingEntity entity,
                                     net.minecraft.world.entity.ai.attributes.Attribute attribute, double value)
    {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null && value > 0.0)
        {
            instance.setBaseValue(value);
        }
    }

    private static void warnDrift(Throwable t)
    {
        if (DMZ_WARNED.compareAndSet(false, true))
        {
            LoggingHandler.sulog.warn("[PlanetConquest] DragonMineZ stat/skill API drift; conquest bosses use basic "
                    + "stats only.", t);
        }
    }

    // ---- credit + announcements -------------------------------------------------------------------------------------

    // the player to credit a boss kill to: the killer if it is a player, else the player who triggered the boss if they
    // are online, else null.
    private static ServerPlayer resolveClaimer(MinecraftServer server, LivingEntity boss, DamageSource source)
    {
        if (source != null && source.getEntity() instanceof ServerPlayer killer)
        {
            return killer;
        }
        String triggerUuid = boss.getPersistentData().getString(BOSS_TRIGGER);
        if (!triggerUuid.isEmpty())
        {
            try
            {
                return server.getPlayerList().getPlayer(UUID.fromString(triggerUuid));
            }
            catch (IllegalArgumentException ignored)
            {
                // malformed stored uuid: fall through to null.
            }
        }
        return null;
    }

    private static void announceSpawn(ServerLevel surface, String planetId, LivingEntity boss)
    {
        MinecraftServer server = surface.getServer();
        if (server == null)
        {
            return;
        }
        broadcastOnSurface(server, planetId, Component.translatable(
                "message.dmz_ragnarok.core.planet_conquest_spawned",
                boss.getDisplayName(), Component.literal(GeneratedPlanets.nameFor(planetId))));
    }

    // send a message to every player standing on the given planet's surface cell.
    private static void broadcastOnSurface(MinecraftServer server, String planetId, Component message)
    {
        broadcastOnSurfaceExcept(server, planetId, null, message);
    }

    private static void broadcastOnSurfaceExcept(MinecraftServer server, String planetId, ServerPlayer except,
                                                 Component message)
    {
        ServerLevel surface = SurfaceDimension.level(server);
        if (surface == null)
        {
            return;
        }
        for (ServerPlayer player : surface.players())
        {
            if (player == except)
            {
                continue;
            }
            if (planetId.equals(net.shurui.shuruisutilities.world.space.SurfaceTravelData.planetId(player)))
            {
                player.sendSystemMessage(message);
            }
        }
    }

    // ---- boss liveness / cleanup ------------------------------------------------------------------------------------

    // whether a live conquest boss for the planet is currently loaded on the surface. Matches on the marker + planet id.
    private static boolean bossAlive(MinecraftServer server, ServerLevel surface, String planetId)
    {
        for (Entity entity : surface.getAllEntities())
        {
            if (isBossFor(entity, planetId) && entity.isAlive() && !entity.isRemoved())
            {
                return true;
            }
        }
        return false;
    }

    // discard any live conquest boss for a planet in the surface dimension.
    private static void discardBoss(MinecraftServer server, String planetId)
    {
        ServerLevel surface = SurfaceDimension.level(server);
        if (surface == null)
        {
            return;
        }
        List<Entity> snapshot = new ArrayList<>();
        for (Entity entity : surface.getAllEntities())
        {
            snapshot.add(entity);
        }
        for (Entity entity : snapshot)
        {
            if (isBossFor(entity, planetId))
            {
                entity.discard();
            }
        }
    }

    private static boolean isBossFor(Entity entity, String planetId)
    {
        return entity.getPersistentData().getBoolean(BOSS_FLAG)
                && planetId.equals(entity.getPersistentData().getString(BOSS_PLANET));
    }

    // ---- default boss table -----------------------------------------------------------------------------------------

    /**
     * The default theme -&gt; main-character boss table. Every id is a DragonMineZ {@code saga_*} entity confirmed present
     * in dragonminez-2.1.3 (so its model is in the DragonMineZ jar), and every one is a normal-sized fighter, not a giant
     * or multipart boss. Operators override each theme's list in the SpacePlanets config (comma-separated).
     */
    static Map<SurfaceStamp.Theme, List<String>> defaultTable()
    {
        EnumMap<SurfaceStamp.Theme, List<String>> t = new EnumMap<>(SurfaceStamp.Theme.class);
        // Conquest is the VILLAIN's role: the player is invading a world, and its defenders are the canonical HEROES.
        // Every id below is a GOOD-aligned DragonMineZ main character (a normal-sized fighter, no giant or multipart
        // boss), and each list is kept byte-identical to the matching conquestBoss* default in PlanetSpawnModule so the
        // one-time config migration there can recognise the old villain defaults and replace them with these.
        // barren rock: the reformed Prince and the young Saiyan hybrids defend it.
        t.put(SurfaceStamp.Theme.STONY,
                List.of("saga_vegeta_mid_ssj", "saga_ftrunks_ssj", "saga_goten_ssj", "saga_kid_trunks_ssj"));
        // Earth-like: Earth's champions.
        t.put(SurfaceStamp.Theme.OVERWORLD, List.of("saga_goku_end_ssj", "saga_gohan_end_ssj2", "saga_piccolo",
                "saga_krillin", "saga_tien_early", "saga_yamcha", "saga_a17", "saga_a18"));
        // Namek ground: the Namekian defenders (Nail and the Piccolo line).
        t.put(SurfaceStamp.Theme.NAMEK, List.of("saga_nail", "saga_piccolo", "saga_piccolo_kami"));
        // demon realm: the strongest heroes, fusions and Ultimate Gohan.
        t.put(SurfaceStamp.Theme.NETHER, List.of("saga_vegetto_ssj", "saga_gohan_end_ultimate", "saga_goku_end_ssj3",
                "saga_gotenks_ssj3"));
        // cold space: the pure-Saiyan powerhouses in their highest forms.
        t.put(SurfaceStamp.Theme.END, List.of("saga_goku_end_ssj3", "saga_vegeta_end_ssj2", "saga_ftrunks_ssg3",
                "saga_vegetto_base"));
        // King Kai's world / otherworld: Pikkon, the Supreme Kai and Kibito.
        t.put(SurfaceStamp.Theme.KAIO, List.of("saga_paikuhan", "saga_shin", "saga_kibito"));
        // otherworld / universal fallback: a mix of the strongest heroes.
        t.put(SurfaceStamp.Theme.OTHERWORLD,
                List.of("saga_paikuhan", "saga_goku_end_ssj2", "saga_gohan_end_ultimate", "saga_vegetto_ssj"));
        return t;
    }

    // ---- headless self-test (-Ddmzr.conquestSelfTest=true) ----------------------------------------------------------

    /**
     * Headless self-test run at server start when {@code -Ddmzr.conquestSelfTest=true}. Exercises the three deterministic
     * halves of this feature with no client: (1) every default/configured boss id per theme resolves to a real, living
     * DragonMineZ saga entity (so its model is in the jar and a client can draw it); (2) the stat-scaling roll stays
     * within the configured tolerance; (3) a personal claim survives the {@link GeneratedPlanetClaims} save round-trip
     * (the same store, and therefore the same cross-shard path, the guild claims use). Logs one PASS/FAIL summary. A no-op
     * unless the property is set, so it never affects a normal run.
     */
    public static void runSelfTest(MinecraftServer server)
    {
        if (server == null || !"true".equals(System.getProperty("dmzr.conquestSelfTest")))
        {
            return;
        }
        boolean ok = true;
        LoggingHandler.sulog.info("[PlanetConquest][selftest] begin.");

        // 1. every theme's boss ids resolve to a living DMZ saga entity (model present in the jar).
        ServerLevel overworld = server.overworld();
        for (SurfaceStamp.Theme theme : SurfaceStamp.Theme.values())
        {
            List<String> ids = idsForTheme(theme);
            int resolved = 0;
            for (String id : ids)
            {
                LivingEntity probe = createDmz(overworld, id);
                if (probe instanceof DBSagasEntity)
                {
                    resolved++;
                    probe.discard();
                }
                else
                {
                    if (probe != null)
                    {
                        probe.discard();
                    }
                    LoggingHandler.sulog.warn("[PlanetConquest][selftest] theme {} boss id '{}' did NOT resolve to a "
                            + "DragonMineZ saga entity.", theme, id);
                }
            }
            if (resolved == 0)
            {
                ok = false;
                LoggingHandler.sulog.warn("[PlanetConquest][selftest] theme {} has NO resolvable boss ({} listed).",
                        theme, ids.size());
            }
            else
            {
                LoggingHandler.sulog.info("[PlanetConquest][selftest] theme {} -> {}/{} boss id(s) resolved.",
                        theme, resolved, ids.size());
            }
        }

        // 2. the stat-scaling roll stays inside [1 - tol, 1 + tol].
        RandomSource random = RandomSource.create(0x5EEDL);
        double tol = statTolerance;
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (int i = 0; i < 100000; i++)
        {
            double f = roll(random);
            lo = Math.min(lo, f);
            hi = Math.max(hi, f);
        }
        boolean rollOk = lo >= 1.0 - tol - 1.0E-9 && hi <= 1.0 + tol + 1.0E-9;
        ok &= rollOk;
        LoggingHandler.sulog.info("[PlanetConquest][selftest] stat roll (tol {}): observed [{}, {}] -> {}.",
                tol, String.format(Locale.ROOT, "%.5f", lo), String.format(Locale.ROOT, "%.5f", hi),
                rollOk ? "OK" : "FAIL");

        // 3. personal-claim persistence round-trip through the shared GeneratedPlanetClaims store.
        boolean claimOk;
        try
        {
            GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);
            String testId = "sugen:conquest_selftest_synthetic";
            UUID owner = UUID.randomUUID();
            claims.setPersonalClaim(testId, owner);
            boolean live = claims.isPersonallyClaimed(testId) && claims.isOwned(testId)
                    && owner.toString().equals(claims.personalOwner(testId));
            // save round-trip: the entry must be written into the persisted tag (what a shard hop / disk save carries).
            net.minecraft.nbt.CompoundTag tag = claims.save(new net.minecraft.nbt.CompoundTag());
            boolean persisted = false;
            net.minecraft.nbt.ListTag list = tag.getList("personalClaims", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++)
            {
                net.minecraft.nbt.CompoundTag c = list.getCompound(i);
                if (testId.equals(c.getString("planet")) && owner.toString().equals(c.getString("owner")))
                {
                    persisted = true;
                    break;
                }
            }
            claims.unclaimPersonal(testId);
            boolean cleared = !claims.isPersonallyClaimed(testId);
            claimOk = live && persisted && cleared;
            LoggingHandler.sulog.info("[PlanetConquest][selftest] claim persistence: live={} persisted={} cleared={} -> {}.",
                    live, persisted, cleared, claimOk ? "OK" : "FAIL");
        }
        catch (Throwable t)
        {
            claimOk = false;
            LoggingHandler.sulog.warn("[PlanetConquest][selftest] claim persistence threw.", t);
        }
        ok &= claimOk;

        // 4. every rgnpc garrison face id resolves to a bundled RgNpcModels entry (no silent 2stars/Haze Shenron default
        // fallback, the "upa" bug). The wild garrison is part of a conquered planet, so this belongs with the conquest set.
        List<String> badGarrison = PlanetGarrisonRoster.validateRgNpcModelIds();
        boolean garrisonOk = badGarrison.isEmpty();
        ok &= garrisonOk;
        LoggingHandler.sulog.info("[PlanetConquest][selftest] rgnpc garrison faces: {} -> {}.",
                garrisonOk ? "all resolve" : ("UNRESOLVED " + badGarrison), garrisonOk ? "OK" : "FAIL");

        LoggingHandler.sulog.info("[PlanetConquest][selftest] RESULT: {}.", ok ? "PASS" : "FAIL");
    }

    /**
     * Parse a comma-separated boss-id string into a clean list (trimmed, lower-cased, empties dropped). Used by the config
     * bake to turn each per-theme config string into its id list.
     */
    static List<String> parseIds(String csv)
    {
        List<String> out = new ArrayList<>();
        if (csv == null)
        {
            return out;
        }
        for (String part : csv.split(","))
        {
            String id = part.trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty())
            {
                out.add(id);
            }
        }
        return out;
    }
}
