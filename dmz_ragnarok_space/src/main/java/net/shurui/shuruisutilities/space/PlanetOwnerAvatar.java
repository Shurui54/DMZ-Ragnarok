package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dragonminez.common.init.EntityAttributes;
import com.dragonminez.common.init.entities.IBattlePower;
import com.dragonminez.common.init.entities.sagas.DBSagasEntity;
import com.dragonminez.common.stats.StatsData;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

import net.shurui.shuruisutilities.compat.DmzBridge;
import net.shurui.shuruisutilities.core.misc.SafeSpotResolver;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.world.space.SurfaceTravelData;

/**
 * The OWNER-AVATAR defender for a personally-conquered planet (see {@link PlanetConquest} for the conquest that grants the
 * personal claim). Once a planet is personally owned, it can no longer be blown up freely: it is guarded by an AVATAR of
 * the owning player, a {@link PlanetOwnerAvatarEntity} that renders as the owner (their skin plus DragonMineZ race body)
 * and fights with a snapshot of the owner's combat stats. Only a NON-owner challenger who beats the avatar can then
 * destroy the planet, and only for a limited window before the avatar returns.
 *
 * <h2>Lifecycle (server tick, driven by {@link PlanetSpawnModule})</h2>
 * <ul>
 *   <li>The avatar spawns for a personally-claimed planet ONLY when a non-owner player is standing on its surface, one at
 *       a time, never for the owner and never while nobody else is there.</li>
 *   <li>It despawns as soon as no challenger remains on the planet.</li>
 *   <li>When a challenger defeats it, the planet becomes explodable for {@code explodableWindowMinutes}, and the avatar
 *       may respawn only after {@code respawnMinutes} (both measured from the defeat).</li>
 * </ul>
 *
 * <h2>Stats and look are a persisted snapshot ({@link GeneratedPlanetClaims.AvatarSnapshot})</h2>
 * Captured when the planet is conquered and refreshed whenever the owner is online on it, so the avatar fights at the
 * owner's strength and wears the owner's look even while the owner is offline or on another shard. The snapshot rides the
 * same overworld-SavedData (and therefore cross-shard) path as the personal claim.
 *
 * <p>Every DragonMineZ touchpoint is wrapped so an API drift degrades (a plainer avatar, or none) and never crashes,
 * matching {@link PlanetConquest} / {@link PlanetGarrison}.
 */
public final class PlanetOwnerAvatar
{
    private PlanetOwnerAvatar()
    {
    }

    // ForgeData markers on the avatar, so a death or cleanup sweep routes it to the right planet. Survive a chunk reload.
    static final String AVATAR_FLAG = "su_owner_avatar";
    static final String AVATAR_PLANET = "su_owner_avatar_planet";

    private static final String DMZ_STATS_CONFIGURED = "dmz_stats_configured";
    private static final String NPC_DEFENSE_KEY = "dmz_npc_defense";

    // combat derivation from the owner's battle power, matching the shared space-module scale (PlanetConquest / garrison).
    private static final double MELEE_BP_DIVISOR = 500.0;
    private static final double DEFENSE_BP_DIVISOR = 1000.0;
    private static final double KI_BP_DIVISOR = 500.0;
    private static final double MIN_HEALTH = 20.0;
    private static final double MIN_MELEE = 2.0;
    private static final double FALLBACK_BP = 5000.0;

    private static final double SPAWN_DISTANCE = 6.0;
    private static final int NO_TINT = 0xFFFFFF;

    // config (baked by PlanetSpawnModule). volatile: read on the server thread, written on the config thread.
    private static volatile boolean enabled = true;
    private static volatile double statMultiplier = 1.0;
    private static volatile long respawnMillis = 10L * 60L * 1000L;
    private static volatile long explodableWindowMillis = 10L * 60L * 1000L;

    // client-facing landing notice: the last planet id we told each player they were on, so we only message on a change.
    private static final Map<UUID, String> LAST_PLANET = new ConcurrentHashMap<>();

    // one-shot latch so a DragonMineZ stat/look API drift is logged ONCE across the whole run.
    private static final AtomicBoolean DMZ_WARNED = new AtomicBoolean(false);

    /**
     * Push the baked config in. {@code respawnMinutes}/{@code explodableWindowMinutes} are minutes; a non-positive value
     * is floored at 0 (immediate).
     */
    public static void setConfig(boolean on, double multiplier, double respawnMinutes, double explodableWindowMinutes)
    {
        enabled = on;
        statMultiplier = Math.max(0.0, multiplier);
        respawnMillis = (long) (Math.max(0.0, respawnMinutes) * 60.0 * 1000.0);
        explodableWindowMillis = (long) (Math.max(0.0, explodableWindowMinutes) * 60.0 * 1000.0);
    }

    public static boolean isEnabled()
    {
        return enabled;
    }

    /**
     * The destruction gate for a PERSONALLY-claimed planet, consulted by the planet-buster gates and {@link
     * PlanetDestruction}. Returns true (destruction allowed) when: the planet is not personally claimed at all; the
     * feature is off; there is no owner-avatar snapshot to guard with; or the avatar has been defeated and the explodable
     * window is still open. Returns false (destruction refused) while the avatar guards the planet. Never throws; fails
     * OPEN (allows destruction) on any error so a broken gate never makes a planet indestructible.
     */
    public static boolean explodableNow(MinecraftServer server, String planetId)
    {
        try
        {
            if (server == null || planetId == null || planetId.isEmpty())
            {
                return true;
            }
            GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);
            if (!claims.isPersonallyClaimed(planetId))
            {
                return true; // not a personal claim: this gate does not apply (guild claims use the raid-entitlement gate).
            }
            if (!enabled)
            {
                return true; // feature off: personal claims are freely explodable (no avatar, no raid requirement).
            }
            if (claims.avatarSnapshot(planetId) == null)
            {
                return true; // no snapshot to build an avatar from: never leave the planet permanently locked.
            }
            long defeatedAt = claims.avatarDefeatedAt(planetId);
            if (defeatedAt <= 0L)
            {
                return false; // never defeated: the avatar guards it.
            }
            // defeated: explodable only while the window is open. Compared on the shared DB clock (OrbitClock's server
            // epoch), NOT the host wall clock, because shard wall clocks run many hours apart.
            return OrbitClock.serverEpochMillis() < defeatedAt + explodableWindowMillis;
        }
        catch (Throwable t)
        {
            return true; // fail open.
        }
    }

    /**
     * Capture (or refresh) the owner-avatar snapshot from the owning player's live DragonMineZ look and stats. Called when
     * the planet is first conquered (from {@link PlanetConquest}) and again while the owner is online on the planet. Never
     * throws; a read failure simply leaves the previous snapshot (or none).
     */
    public static void captureSnapshot(MinecraftServer server, String planetId, ServerPlayer owner)
    {
        if (!enabled || server == null || planetId == null || planetId.isEmpty() || owner == null)
        {
            return;
        }
        try
        {
            GeneratedPlanetClaims.AvatarSnapshot snapshot = buildSnapshot(owner);
            if (snapshot != null)
            {
                GeneratedPlanetClaims.get(server).setAvatarSnapshot(planetId, snapshot);
            }
        }
        catch (Throwable t)
        {
            warnDrift(t);
        }
    }

    // read the owner's DMZ look (race body appearance) and battle power, and derive the combat numbers, into a snapshot.
    private static GeneratedPlanetClaims.AvatarSnapshot buildSnapshot(ServerPlayer owner)
    {
        String race = "";
        int bodyType = 0;
        int body1 = NO_TINT;
        int body2 = NO_TINT;
        int body3 = NO_TINT;
        int hair = NO_TINT;
        double bp = FALLBACK_BP;
        double health = Math.max(MIN_HEALTH, owner.getMaxHealth());

        StatsData stats = DmzBridge.stats(owner);
        if (stats != null)
        {
            try
            {
                double exact = stats.getBattlePowerExact();
                if (exact > 0.0 && !Double.isNaN(exact))
                {
                    bp = exact;
                }
            }
            catch (Throwable t)
            {
                warnDrift(t);
            }
            try
            {
                com.dragonminez.common.stats.character.Character character = stats.getCharacter();
                if (character != null)
                {
                    race = character.getRaceName();
                    bodyType = character.getBodyType();
                    body1 = parseHex(character.getBodyColor());
                    body2 = parseHex(character.getBodyColor2());
                    body3 = parseHex(character.getBodyColor3());
                    hair = parseHex(character.getHairColor());
                }
            }
            catch (Throwable t)
            {
                warnDrift(t);
            }
        }

        double melee = Math.max(MIN_MELEE, bp / MELEE_BP_DIVISOR);
        double defense = bp / DEFENSE_BP_DIVISOR;
        float ki = (float) (bp / KI_BP_DIVISOR);
        return new GeneratedPlanetClaims.AvatarSnapshot(owner.getGameProfile().getName(), race, bodyType,
                body1, body2, body3, hair, health, melee, defense, ki, bp);
    }

    /**
     * The per-tick lifecycle + landing notice. Called from {@link PlanetSpawnModule}'s throttled server tick. Iterates
     * the shared planet-surface dimension's players, tells each who owns the planet they just arrived on, refreshes the
     * owner snapshot while an owner stands on their world, then spawns/despawns/targets one avatar per personally-claimed
     * planet that currently has a non-owner challenger on it. Never throws.
     */
    public static void tick(MinecraftServer server)
    {
        if (server == null)
        {
            return;
        }
        try
        {
            ServerLevel surface = SurfaceDimension.level(server);
            if (surface == null)
            {
                return;
            }
            GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);

            // group surface players by planet, drive the landing notice, and refresh the owner snapshot.
            Map<String, List<ServerPlayer>> byPlanet = new HashMap<>();
            Set<UUID> present = new HashSet<>();
            for (ServerPlayer player : surface.players())
            {
                String planetId = SurfaceTravelData.planetId(player);
                present.add(player.getUUID());
                landingNotice(claims, player, planetId);
                if (planetId == null || planetId.isEmpty())
                {
                    continue;
                }
                byPlanet.computeIfAbsent(planetId, k -> new ArrayList<>()).add(player);
                // keep the owner's snapshot fresh while they stand on their own world (offline/other-shard reads stay put).
                if (isOwner(claims, planetId, player))
                {
                    captureSnapshot(server, planetId, player);
                }
            }
            // forget players who left the surface, so a re-arrival re-notifies.
            LAST_PLANET.keySet().removeIf(uuid -> !present.contains(uuid));

            if (!enabled)
            {
                return; // no spawning while off; existing avatars are swept below only when enabled to avoid churn.
            }

            // which personally-claimed planets currently have a non-owner challenger present.
            Map<String, ServerPlayer> challengerByPlanet = new HashMap<>();
            for (Map.Entry<String, List<ServerPlayer>> e : byPlanet.entrySet())
            {
                if (!claims.isPersonallyClaimed(e.getKey()))
                {
                    continue;
                }
                ServerPlayer challenger = nearestChallenger(claims, e.getKey(), e.getValue());
                if (challenger != null)
                {
                    challengerByPlanet.put(e.getKey(), challenger);
                }
            }

            // reconcile live avatars: despawn any whose planet has no challenger; retarget the rest.
            Set<String> planetsWithLiveAvatar = new HashSet<>();
            List<Entity> snapshot = new ArrayList<>();
            for (Entity entity : surface.getAllEntities())
            {
                snapshot.add(entity);
            }
            for (Entity entity : snapshot)
            {
                if (!entity.getPersistentData().getBoolean(AVATAR_FLAG))
                {
                    continue;
                }
                String planetId = entity.getPersistentData().getString(AVATAR_PLANET);
                ServerPlayer challenger = challengerByPlanet.get(planetId);
                if (challenger == null || !entity.isAlive() || entity.isRemoved())
                {
                    entity.discard(); // no challenger on this planet (owner-only or empty): the avatar leaves.
                    continue;
                }
                planetsWithLiveAvatar.add(planetId);
                if (entity instanceof Mob mob)
                {
                    mob.setTarget(challenger);
                }
            }

            // spawn one avatar per challenged planet that has none and is ready to respawn. The respawn cooldown is
            // measured on the shared DB clock (see the defeat stamp), so it holds across a shard hop.
            long now = OrbitClock.serverEpochMillis();
            for (Map.Entry<String, ServerPlayer> e : challengerByPlanet.entrySet())
            {
                String planetId = e.getKey();
                if (planetsWithLiveAvatar.contains(planetId))
                {
                    continue;
                }
                long defeatedAt = claims.avatarDefeatedAt(planetId);
                if (defeatedAt > 0L && now < defeatedAt + respawnMillis)
                {
                    continue; // still in the post-defeat respawn cooldown.
                }
                spawnAvatar(server, surface, planetId, e.getValue(), claims);
            }
        }
        catch (Throwable t)
        {
            warnDrift(t);
        }
    }

    // send the arrival message once when a player's planet changes to a personally-claimed one. Actionbar, keyless-safe.
    private static void landingNotice(GeneratedPlanetClaims claims, ServerPlayer player, String planetId)
    {
        String key = planetId == null ? "" : planetId;
        String previous = LAST_PLANET.put(player.getUUID(), key);
        if (key.equals(previous) || key.isEmpty())
        {
            return;
        }
        if (!claims.isPersonallyClaimed(key))
        {
            return;
        }
        String planetName = GeneratedPlanets.nameFor(key);
        if (isOwner(claims, key, player))
        {
            player.displayClientMessage(Component.translatable(
                    "message.dmz_ragnarok.core.planet_owner_avatar_your_planet",
                    Component.literal(planetName)), true);
        }
        else
        {
            String ownerName = ownerName(claims, key);
            player.displayClientMessage(Component.translatable(
                    "message.dmz_ragnarok.core.planet_owner_avatar_claimed_by",
                    Component.literal(ownerName), Component.literal(planetName)), true);
        }
    }

    // spawn one avatar for a planet, statted from its snapshot, placed near the challenger, targeting them.
    private static void spawnAvatar(MinecraftServer server, ServerLevel surface, String planetId, ServerPlayer challenger,
                                    GeneratedPlanetClaims claims)
    {
        GeneratedPlanetClaims.AvatarSnapshot snap = claims.avatarSnapshot(planetId);
        if (snap == null)
        {
            return; // no snapshot: leave the planet explodable (explodableNow returns true) rather than lock it.
        }
        String ownerUuid = claims.personalOwner(planetId);
        if (ownerUuid == null)
        {
            return;
        }
        try
        {
            PlanetOwnerAvatarEntity avatar = PlanetOwnerAvatarEntities.type().create(surface);
            if (avatar == null)
            {
                return;
            }
            RandomSource random = surface.getRandom();
            placeNear(surface, avatar, challenger, random);
            avatar.setRaceAppearance(snap.race, snap.bodyType, snap.bodyColor1, snap.bodyColor2, snap.bodyColor3,
                    snap.hairColor);
            UUID owner = parseUuid(ownerUuid);
            avatar.setOwner(owner, snap.ownerName);
            avatar.setHomePlanet(planetId);
            avatar.setCustomName(Component.literal(snap.ownerName));
            avatar.setCustomNameVisible(true);
            applyAvatarStats(avatar, snap);

            avatar.getPersistentData().putBoolean(AVATAR_FLAG, true);
            avatar.getPersistentData().putString(AVATAR_PLANET, planetId);
            avatar.setPersistenceRequired();

            if (!surface.addFreshEntity(avatar) || avatar.isRemoved() || !avatar.isAlive())
            {
                LoggingHandler.sulog.warn("[PlanetOwnerAvatar] Avatar for planet {} failed to spawn.", planetId);
                return;
            }
            avatar.setTarget(challenger);
            broadcastOnPlanet(server, planetId, Component.translatable(
                    "message.dmz_ragnarok.core.planet_owner_avatar_spawned",
                    Component.literal(snap.ownerName), Component.literal(GeneratedPlanets.nameFor(planetId))));
        }
        catch (Throwable t)
        {
            warnDrift(t);
        }
    }

    // apply the snapshot's combat numbers (scaled by the config multiplier) and the saga combat brain to the avatar.
    private static void applyAvatarStats(PlanetOwnerAvatarEntity avatar, GeneratedPlanetClaims.AvatarSnapshot snap)
    {
        double m = statMultiplier;
        double health = Math.max(MIN_HEALTH, snap.health * m);
        double melee = Math.max(MIN_MELEE, snap.melee * m);
        double defense = snap.defense * m;
        float ki = (float) (snap.ki * m);
        double bp = snap.battlePower * m;

        setAttribute(avatar, Attributes.MAX_HEALTH, health);
        avatar.setHealth((float) health);
        setAttribute(avatar, Attributes.ATTACK_DAMAGE, melee);
        if (defense > 0.0)
        {
            avatar.getPersistentData().putDouble(NPC_DEFENSE_KEY, defense);
        }
        setKiBlastDamage(avatar, ki);

        if (avatar instanceof IBattlePower ibp)
        {
            try
            {
                ibp.setBattlePower((int) Math.round(Math.min(bp, Integer.MAX_VALUE)));
            }
            catch (Throwable t)
            {
                warnDrift(t);
            }
        }
        try
        {
            avatar.setAiTierById(DBSagasEntity.AiTier.ADVANCED.ordinal() + 1);
            avatar.getSkillPool().clear();
            avatar.addKiSkill(DBSagasEntity.KiSkillType.GENERIC_KI_WAVE, 60, 1.0F);
            avatar.addKiSkill(DBSagasEntity.KiSkillType.KI_LASER, 40, 1.0F);
            avatar.addKiSkill(DBSagasEntity.KiSkillType.FINAL_FLASH, 120, 1.0F);
            avatar.setTransformationDisabled(true);
            avatar.setCanFly(false);
        }
        catch (Throwable t)
        {
            warnDrift(t);
        }
        avatar.getPersistentData().putBoolean(DMZ_STATS_CONFIGURED, true);
    }

    /**
     * Route a server-side death here (from {@link PlanetSpawnModule}). If the dead entity is an owner avatar, stamp the
     * defeat time (opening the explodable and respawn windows) and announce it. A non-avatar death is a cheap no-op. Never
     * throws.
     */
    public static void onLivingDeath(MinecraftServer server, LivingEntity entity)
    {
        if (server == null || entity == null || !entity.getPersistentData().getBoolean(AVATAR_FLAG))
        {
            return;
        }
        try
        {
            String planetId = entity.getPersistentData().getString(AVATAR_PLANET);
            if (planetId.isEmpty())
            {
                return;
            }
            // stamp the defeat on the shared DB clock (OrbitClock's server epoch), so the explodable and respawn windows
            // read correctly on every shard; a bare host wall clock would be many hours off on another shard.
            GeneratedPlanetClaims.get(server).setAvatarDefeatedAt(planetId, OrbitClock.serverEpochMillis());
            broadcastOnPlanet(server, planetId, Component.translatable(
                    "message.dmz_ragnarok.core.planet_owner_avatar_defeated",
                    Component.literal(GeneratedPlanets.nameFor(planetId))));
        }
        catch (Throwable t)
        {
            warnDrift(t);
        }
    }

    /** Discard any live avatar for a planet (used when a guild claims or the planet is destroyed). Never throws. */
    public static void discardAvatar(MinecraftServer server, String planetId)
    {
        if (server == null || planetId == null || planetId.isEmpty())
        {
            return;
        }
        try
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
                if (entity.getPersistentData().getBoolean(AVATAR_FLAG)
                        && planetId.equals(entity.getPersistentData().getString(AVATAR_PLANET)))
                {
                    entity.discard();
                }
            }
        }
        catch (Throwable t)
        {
            warnDrift(t);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static boolean isOwner(GeneratedPlanetClaims claims, String planetId, ServerPlayer player)
    {
        String owner = claims.personalOwner(planetId);
        return owner != null && owner.equals(player.getUUID().toString());
    }

    private static String ownerName(GeneratedPlanetClaims claims, String planetId)
    {
        GeneratedPlanetClaims.AvatarSnapshot snap = claims.avatarSnapshot(planetId);
        if (snap != null && !snap.ownerName.isEmpty())
        {
            return snap.ownerName;
        }
        return "?";
    }

    // the nearest non-owner player on the planet, or null if the only players present are the owner.
    private static ServerPlayer nearestChallenger(GeneratedPlanetClaims claims, String planetId,
                                                  List<ServerPlayer> players)
    {
        String owner = claims.personalOwner(planetId);
        ServerPlayer best = null;
        for (ServerPlayer player : players)
        {
            if (owner != null && owner.equals(player.getUUID().toString()))
            {
                continue;
            }
            best = player; // any non-owner suffices; all share the planet's surface cell.
        }
        return best;
    }

    private static void placeNear(ServerLevel surface, LivingEntity avatar, ServerPlayer anchor, RandomSource random)
    {
        double angle = random.nextDouble() * Math.PI * 2.0;
        double dx = Math.cos(angle) * SPAWN_DISTANCE;
        double dz = Math.sin(angle) * SPAWN_DISTANCE;
        SafeSpotResolver.Result spot =
                SafeSpotResolver.resolve(surface, anchor.getX() + dx, anchor.getY(), anchor.getZ() + dz);
        avatar.moveTo(spot.x, spot.y, spot.z, random.nextFloat() * 360.0F, 0.0F);
    }

    private static void broadcastOnPlanet(MinecraftServer server, String planetId, Component message)
    {
        ServerLevel surface = SurfaceDimension.level(server);
        if (surface == null)
        {
            return;
        }
        for (ServerPlayer player : surface.players())
        {
            if (planetId.equals(SurfaceTravelData.planetId(player)))
            {
                player.sendSystemMessage(message);
            }
        }
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

    private static UUID parseUuid(String s)
    {
        try
        {
            return UUID.fromString(s);
        }
        catch (IllegalArgumentException ex)
        {
            return null;
        }
    }

    // parse a DMZ hex colour string ("#RRGGBB" or "RRGGBB") to packed 0xRRGGBB, defaulting to white (no tint).
    private static int parseHex(String hex)
    {
        if (hex == null || hex.isEmpty())
        {
            return NO_TINT;
        }
        try
        {
            String cleaned = hex.startsWith("#") ? hex.substring(1) : hex;
            return Integer.parseInt(cleaned, 16) & 0xFFFFFF;
        }
        catch (NumberFormatException e)
        {
            return NO_TINT;
        }
    }

    private static void warnDrift(Throwable t)
    {
        if (DMZ_WARNED.compareAndSet(false, true))
        {
            LoggingHandler.sulog.warn("[PlanetOwnerAvatar] DragonMineZ stat/look API drift, or an avatar step failed; "
                    + "the avatar degrades to basic behaviour.", t);
        }
    }
}
