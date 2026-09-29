package net.shurui.shuruisutilities.corrupted;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.compat.dmz.ShadowDragonStatsCompat;
import net.shurui.shuruisutilities.corrupted.region.Region;
import net.shurui.shuruisutilities.ragnarok.RgNpcFighterEntity;
import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Phase-3c home for the real shadow dragon fight: spawning the seven bosses at the end of the cinematic, keeping
 * their compass pips current, enforcing the lifetime timeout, and re-registering pips after a restart. Death and
 * kill-credit are handled on the Forge bus by {@link ShadowDragonForgeHandler}; both read/write the single live
 * state in {@link ShadowDragonStorage}, which is persisted so the timeout and kill accounting survive a restart.
 *
 * <p>All boss stats follow the raid-bosses addon's convention (see {@code RaidInstance.createEnemy}): the
 * suite-wide {@code dmz_npc_defense} key for defence (NOT vanilla armour, so damage is not double-mitigated), the
 * {@code dmz_stats_configured} marker so DMZ 2.1.3 does not overwrite the stats on entity join, and the four
 * DMZ-specific setters behind {@link ShadowDragonStatsCompat}.
 */
public final class ShadowDragonBossManager
{
    private ShadowDragonBossManager() {}

    /**
     * Suite-wide "NPC defense" key (a raw double on persistent data). An sdu Forge handler reads it and applies
     * DMZ's resistance-mitigation curve; without sdu the key just sits unread. Written raw so SU never classloads
     * sdu. Do NOT also set ARMOR/ARMOR_TOUGHNESS or damage gets mitigated twice.
     */
    private static final String NPC_DEFENSE_KEY = "dmz_npc_defense";

    /**
     * DMZ 2.1.3 overwrites NPC stats on entity join unless this marker is present. Set immediately after applying
     * stats, exactly as RaidInstance does; without it the carefully set stats are silently wiped.
     */
    private static final String DMZ_STATS_CONFIGURED_KEY = "dmz_stats_configured";

    /**
     * Spawn one dragon per configured slot at the end of the cinematic, record each in storage, set the encounter
     * start tick and register a pip for each. A slot whose arena is null cannot spawn and is skipped with a log
     * line. If NO slot is configured, admins are told the event fired but produced no dragons (rather than the
     * event vanishing silently). Safe to call with sdu and DMZ absent.
     */
    public static void spawnEncounter(MinecraftServer server)
    {
        spawnEncounter(server, null, null);
    }

    /**
     * {@link #spawnEncounter(MinecraftServer)} with the event location (the dimension and position where the balls
     * were defiled). Keyless only, the location centres the random spawn spots of slots that have no usable arena
     * ({@link ShadowDragonKeylessDefaults}); a keyed server ignores it. Either argument may be null (world spawn).
     */
    public static void spawnEncounter(MinecraftServer server, ResourceKey<Level> eventDim, BlockPos eventPos)
    {
        ShadowDragonStorage storage = ShadowDragonStorage.get(server);
        // S20b: servers without the Ragnarok Key cannot edit the slots, so unedited values fall back to the keyless
        // defaults (Majin Buu stats, random spots). Works on copies: the stored definitions are never changed here.
        boolean keyless = ShadowDragonKeylessDefaults.active();
        List<Vec3> taken = ShadowDragonKeylessDefaults.newTakenList();

        int spawned = 0;
        int configured = 0;
        for (ShadowDragonDef stored : storage.allDefs())
        {
            ShadowDragonDef def = keyless ? ShadowDragonKeylessDefaults.withBuuStats(stored) : stored;
            ShadowDragonKeylessDefaults.Spot spot = null;
            boolean arenaUsable = def.hasArena() && (!keyless || server.getLevel(def.arena.dimension()) != null);
            if (!arenaUsable && keyless)
            {
                spot = ShadowDragonKeylessDefaults.randomSpot(server, eventDim, eventPos, server.overworld().getRandom(),
                        taken);
                if (spot == null)
                {
                    LoggingHandler.sulog.info("[wishtracking] shadow dragon slot {} has no arena and no safe random "
                            + "spot was found, skipping it", def.index);
                    continue;
                }
                LoggingHandler.sulog.info("[wishtracking] shadow dragon slot {} has no usable arena, keyless random "
                        + "spot {} in {}", def.index, BlockPos.containing(spot.pos()), spot.level().dimension().location());
            }
            else if (!def.hasArena())
            {
                LoggingHandler.sulog.info("[wishtracking] shadow dragon slot {} has no arena set, skipping it",
                        def.index);
                continue;
            }
            configured++;
            LivingEntity dragon = spawnOne(server, def, spot);
            if (dragon == null)
                continue;

            storage.addLiveDragon(dragon.getUUID(), def.index);
            // Fight start: drop any stale damage/death records under this entity UUID so a re-used UUID or a fight
            // that follows a previous one cannot leak the earlier fight's accounting into the sub-race award (rule 2).
            ShadowDragonDamageTracker.clearFight(dragon.getUUID());
            updatePip(def.index, dragon, def.name);
            spawned++;
        }

        if (configured == 0)
        {
            // the admin needs to know the event fired but no dragons appeared because no arenas were set.
            LoggingHandler.sulog.warn("[wishtracking] the swap-boss event fired but no shadow dragon slot has an "
                    + "arena configured, so no dragons spawned");
            ChatOutputHandler.broadcast(Component.literal(ChatOutputHandler.formatColors(
                    "&c[wishtracking] The malice took form but found nowhere to manifest: no shadow dragon "
                    + "arenas are configured.")));
            return;
        }
        if (spawned == 0)
        {
            LoggingHandler.sulog.warn("[wishtracking] {} shadow dragon slot(s) were configured but none spawned "
                    + "(entity creation failed for all)", configured);
            return;
        }

        // start the encounter clock so the lifetime timeout can be enforced across restarts.
        storage.setEncounterStartTick(server.overworld().getGameTime());
        LoggingHandler.sulog.info("[wishtracking] spawned {} of {} configured shadow dragon(s)", spawned, configured);
    }

    /**
     * Build, stat, position and spawn one dragon for a slot with an arena. null when the entity type is invalid,
     * is not a living entity, or fails to enter the world. Never records anything in storage: the caller does that
     * only on success.
     */
    private static LivingEntity spawnOne(MinecraftServer server, ShadowDragonDef def,
                                         ShadowDragonKeylessDefaults.Spot spot)
    {
        Region arena = def.arena;
        ServerLevel level = spot != null ? spot.level() : server.getLevel(arena.dimension());
        if (level == null)
        {
            LoggingHandler.sulog.warn("[wishtracking] shadow dragon slot {} arena dimension {} is not loaded, "
                    + "skipping", def.index, arena.dimension().location());
            return null;
        }

        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(def.entityType));
        if (type == null)
        {
            LoggingHandler.sulog.warn("[wishtracking] shadow dragon slot {} has an invalid entity type '{}', "
                    + "skipping", def.index, def.entityType);
            return null;
        }
        Entity entity = type.create(level);
        if (!(entity instanceof LivingEntity dragon))
        {
            LoggingHandler.sulog.warn("[wishtracking] shadow dragon slot {} entity '{}' is not a living entity, "
                    + "skipping", def.index, def.entityType);
            return null;
        }

        // position: explicit spawn point if the admin set one, else the arena centre. either way, snap onto the
        // terrain surface (the arena is an X/Z area) then nudge up out of any embedding blocks so the dragon does
        // not suffocate the instant it spawns.
        // a keyless random spot (S20b) is already a checked surface position, so it is used as it is.
        Vec3 snapped;
        if (spot != null)
            snapped = spot.pos();
        else
        {
            Vec3 base = def.spawnPos != null
                    ? new Vec3(def.spawnPos.getX() + 0.5, def.spawnPos.getY(), def.spawnPos.getZ() + 0.5)
                    : arena.center();
            snapped = surfaceSnap(level, base);
        }
        dragon.moveTo(snapped.x, snapped.y, snapped.z, 0f, 0f);
        nudgeClear(level, dragon);

        // Wear the slot's canonical dragon model. Only the rgnpc fighter carries a per-instance model; a slot an
        // admin repointed at some other entity type spawns unchanged. setModelId self-sanitises and falls back to
        // the rgnpc default for an unknown id, so a bad value can never reach a client render thread.
        if (dragon instanceof RgNpcFighterEntity fighter && def.baseModel != null && !def.baseModel.isBlank())
            fighter.setModelId(def.baseModel);

        applyStats(dragon, def);
        // opt out of DMZ's on-join config-stat overwrite so our stats survive (DMZ 2.1.3). MUST be after applyStats.
        dragon.getPersistentData().putBoolean(DMZ_STATS_CONFIGURED_KEY, true);
        if (dragon instanceof Mob mob)
            mob.setPersistenceRequired();

        if (!level.addFreshEntity(dragon) || dragon.isRemoved() || !dragon.isAlive())
        {
            LoggingHandler.sulog.warn("[wishtracking] shadow dragon slot {} '{}' failed to spawn at {}",
                    def.index, def.entityType, dragon.blockPosition());
            return null;
        }
        LoggingHandler.sulog.info("[wishtracking] spawned shadow dragon slot {} '{}' hp={}/{} at {}",
                def.index, def.entityType, dragon.getHealth(), dragon.getMaxHealth(), dragon.blockPosition());
        return dragon;
    }

    // apply the generic vanilla attributes + the suite-wide defence key + the DMZ-specific stats. every value
    // honours the "0 keeps the entity default" convention.
    private static void applyStats(LivingEntity dragon, ShadowDragonDef def)
    {
        if (def.health > 0)
        {
            AttributeInstance maxHp = dragon.getAttribute(Attributes.MAX_HEALTH);
            if (maxHp != null)
                maxHp.setBaseValue(def.health);
            dragon.setHealth((float) def.health);
        }
        if (def.meleeDamage > 0)
        {
            AttributeInstance atk = dragon.getAttribute(Attributes.ATTACK_DAMAGE);
            if (atk != null)
                atk.setBaseValue(def.meleeDamage);
        }
        if (def.moveSpeed > 0)
        {
            AttributeInstance spd = dragon.getAttribute(Attributes.MOVEMENT_SPEED);
            if (spd != null)
                spd.setBaseValue(def.moveSpeed);
        }
        // defence: write the raw suite-wide key (sdu applies DMZ's mitigation curve from it). Remove it at 0 so an
        // unset slot carries no key. Do NOT set ARMOR/ARMOR_TOUGHNESS as well or damage is mitigated twice.
        if (def.defense > 0)
            dragon.getPersistentData().putDouble(NPC_DEFENSE_KEY, def.defense);
        else
            dragon.getPersistentData().remove(NPC_DEFENSE_KEY);

        if (def.name != null && !def.name.isBlank())
        {
            dragon.setCustomName(Component.literal(ChatOutputHandler.formatColors(def.name)));
            dragon.setCustomNameVisible(true);
        }

        // DMZ-specific stats (battle power, ki-blast, scale, AI tier) only apply to DMZ saga entities, and only
        // when DMZ is present; degrades to a logged no-op otherwise.
        ShadowDragonStatsCompat.applyDmzStats(dragon, def.battlePower, def.kiBlastDamage, def.scale, def.aiTier);
    }

    // snap X/Z onto the terrain surface Y. MOTION_BLOCKING_NO_LEAVES gives the first empty block above the top
    // solid one (where feet go), so no +1. getHeight on a ServerLevel force-loads the chunk, so an unloaded arena
    // is fine. Mirrors RaidInstance.surfaceSnap.
    private static Vec3 surfaceSnap(ServerLevel level, Vec3 pos)
    {
        int bx = Mth.floor(pos.x);
        int bz = Mth.floor(pos.z);
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        return new Vec3(pos.x, surfaceY, pos.z);
    }

    // step straight up until the bounding box clears blocks, so the dragon does not suffocate on spawn. Mirrors
    // RaidInstance.nudgeClear.
    private static void nudgeClear(ServerLevel level, LivingEntity dragon)
    {
        double ceiling = dragon.getY() + 16;
        int guard = 0;
        while (!level.noCollision(dragon) && dragon.getY() < ceiling && guard++ < 48)
        {
            dragon.setPos(dragon.getX(), dragon.getY() + 1, dragon.getZ());
        }
    }

    /**
     * Called from the existing throttled server tick in {@link CorruptedBallHandler} (every RETRY_INTERVAL_TICKS),
     * never from a second ticker. Returns immediately when no encounter is running, so the no-work case is free.
     * When an encounter is running it: enforces the lifetime timeout (ending the encounter if it has run longer
     * than the configured lifetime), drops any live dragon that no longer exists, and refreshes the compass pip of
     * every living dragon so it tracks the dragon as it moves.
     */
    public static void tick(MinecraftServer server)
    {
        ShadowDragonStorage storage = ShadowDragonStorage.get(server);
        if (!storage.isEncounterActive())
            return;

        long now = server.overworld().getGameTime();
        long elapsed = now - storage.getEncounterStartTick();
        int lifetime = SUConfig.corruptedDragonLifetimeTicks;
        if (elapsed >= lifetime)
        {
            timeoutEncounter(server, storage);
            return;
        }

        // refresh pips and prune dragons that have despawned/died without our seeing a death event.
        for (Map.Entry<UUID, Integer> e : storage.getLiveDragons().entrySet())
        {
            UUID id = e.getKey();
            int slot = e.getValue();
            LivingEntity dragon = findLiving(server, id);
            if (dragon == null)
            {
                // gone with no death event we caught (unloaded then discarded, /kill on a chunk we didn't tick,
                // etc.). drop it and its pip so it does not hold the encounter open forever.
                storage.removeLiveDragon(id);
                SduDragonMarkerBridge.remove(slot);
                // it left without a death event we caught: free its damage/death tracking so it does not linger.
                ShadowDragonDamageTracker.clear(id);
                LoggingHandler.sulog.info("[wishtracking] shadow dragon slot {} ({}) no longer exists, dropping it",
                        slot, id);
                continue;
            }
            maybeTransform(storage, slot, dragon);
            updatePip(slot, dragon, dragonName(storage, slot));
        }

        // if pruning emptied the live set, the encounter is over.
        if (!storage.hasLiveDragons())
            endEncounter(server, storage, "&aThe shadow dragons have all been vanquished.");
    }

    // remove every remaining dragon + pip, clear the encounter and announce it. driven off the persisted start
    // tick, so the timeout survives a restart: the elapsed time is (currentGameTime - persistedStartTick).
    private static void timeoutEncounter(MinecraftServer server, ShadowDragonStorage storage)
    {
        for (Map.Entry<UUID, Integer> e : storage.getLiveDragons().entrySet())
        {
            LivingEntity dragon = findLiving(server, e.getKey());
            if (dragon != null)
                dragon.remove(Entity.RemovalReason.KILLED);
            SduDragonMarkerBridge.remove(e.getValue());
            // timed-out fight: no sub-race award, so just free its damage/death tracking.
            ShadowDragonDamageTracker.clear(e.getKey());
        }
        endEncounter(server, storage, "&7The shadow dragons faded back into the corrupted balls.");
        LoggingHandler.sulog.info("[wishtracking] shadow dragon encounter timed out and was cleared");
    }

    /**
     * On server start, if the storage says an encounter is active, re-register a compass pip for every still-living
     * dragon (looking each UUID up in its level) and drop any that no longer exist. sdu's markers are transient, so
     * without this a restart would silently leave the encounter running with no pips at all.
     */
    public static void restoreOnStart(MinecraftServer server)
    {
        ShadowDragonStorage storage = ShadowDragonStorage.get(server);
        if (!storage.isEncounterActive())
            return;

        int restored = 0;
        for (Map.Entry<UUID, Integer> e : storage.getLiveDragons().entrySet())
        {
            UUID id = e.getKey();
            int slot = e.getValue();
            LivingEntity dragon = findLiving(server, id);
            if (dragon == null)
            {
                storage.removeLiveDragon(id);
                LoggingHandler.sulog.info("[wishtracking] shadow dragon slot {} ({}) did not survive the restart, "
                        + "dropping it", slot, id);
                continue;
            }
            updatePip(slot, dragon, dragonName(storage, slot));
            restored++;
        }

        if (!storage.hasLiveDragons())
        {
            // every dragon is gone; close the encounter so a stale start tick does not linger.
            endEncounter(server, storage, "&7The last shadow dragon did not survive the restart; the encounter ends.");
            return;
        }
        LoggingHandler.sulog.info("[wishtracking] restored {} shadow dragon pip(s) after restart", restored);
    }

    /**
     * End the current encounter: clear all live state (dragons, start tick, kill credit is retained for phase 4 to
     * read... see below) and announce {@code message}. NOTE: kill credit is deliberately NOT cleared here, so
     * phase 4 can consume it to unlock races; {@link ShadowDragonStorage#clearEncounter} wipes it, so we clear only
     * the live dragons and start tick and leave the credit in place.
     */
    static void endEncounter(MinecraftServer server, ShadowDragonStorage storage, String message)
    {
        // remove any lingering pips first (the caller may not have).
        for (int slot : storage.getLiveDragons().values())
            SduDragonMarkerBridge.remove(slot);
        // clear the live dragons and the start tick but KEEP kill credit for phase 4 (clearEncounter would wipe it).
        storage.clearLiveDragons();
        storage.setEncounterStartTick(-1L);
        ChatOutputHandler.broadcast(Component.literal(ChatOutputHandler.formatColors(message)));
    }

    // register/move the pip for a slot at the dragon's current position, labelled with its name. no-op without sdu.
    static void updatePip(int slot, LivingEntity dragon, String name)
    {
        String dim = dragon.level().dimension().location().toString();
        SduDragonMarkerBridge.set(slot, dim, dragon.getX(), dragon.getY(), dragon.getZ(), name);
    }

    static void removePip(int slot)
    {
        SduDragonMarkerBridge.remove(slot);
    }

    // resolve a UUID to a living entity across all loaded levels, or null if it is gone/not a LivingEntity.
    static LivingEntity findLiving(MinecraftServer server, UUID id)
    {
        for (ServerLevel level : server.getAllLevels())
        {
            Entity entity = level.getEntity(id);
            if (entity instanceof LivingEntity living && living.isAlive())
                return living;
        }
        return null;
    }

    /** Health fraction at or below which a shadow dragon changes into its transformed model. */
    private static final float TRANSFORM_AT_HEALTH_FRACTION = 0.5f;

    /** Persisted marker so a dragon transforms ONCE, and stays transformed across a save, reload or chunk unload. */
    private static final String TRANSFORMED_KEY = "su_shadow_dragon_transformed";

    /**
     * Swap a shadow dragon onto its transformed model once it drops to half health.
     *
     * <p>{@code transformModel} was stored, defaulted per slot and shown in the editor, but nothing ever read it,
     * so no dragon ever changed. This is that missing half.
     *
     * <p>The change is a MODEL SWAP on the living entity, deliberately not a respawn into a different entity. The
     * encounter tracks its dragons by UUID (live-dragon map, pips, {@link ShadowDragonDamageTracker}, kill credit
     * for the race unlocks), so replacing the entity would drop every one of those and leave the encounter holding
     * a dragon that no longer exists. This is the same reasoning that turned DragonMineZ's native saga transform
     * off for these bosses in the first place.
     *
     * <p>The marker is written to persistent data rather than held in memory because the encounter survives a
     * restart ({@code restoreOnStart}), and an in-memory flag would let a reloaded dragon transform a second time
     * and re-announce itself. A dragon healed back above the line stays transformed, which is what the marker
     * being one-way means.
     */
    private static void maybeTransform(ShadowDragonStorage storage, int slot, LivingEntity dragon)
    {
        if (!(dragon instanceof RgNpcFighterEntity fighter))
            return;
        if (dragon.getPersistentData().getBoolean(TRANSFORMED_KEY))
            return;
        ShadowDragonDef def = storage.getDef(slot);
        // Slot 3 (Eis Shenron) has no transformed form by design, so a blank here is a legitimate "never changes".
        if (def == null || !def.hasTransformModel())
            return;
        float max = dragon.getMaxHealth();
        if (max <= 0.0f || dragon.getHealth() > max * TRANSFORM_AT_HEALTH_FRACTION)
            return;
        // setModelId self-sanitises and falls back to the default for an id that is not a live rgnpc entry, so a
        // def edited to a since-removed model degrades to the fighter rather than throwing on the render thread.
        fighter.setModelId(def.transformModel);
        dragon.getPersistentData().putBoolean(TRANSFORMED_KEY, true);
        LoggingHandler.sulog.info("[wishtracking] shadow dragon slot {} ({}) reached {}% health and changed to "
                + "model '{}'", slot, dragonName(storage, slot),
                Math.round(TRANSFORM_AT_HEALTH_FRACTION * 100), def.transformModel);
    }

    // the display name for a slot, from its def, falling back to a generic label if the def is somehow gone.
    private static String dragonName(ShadowDragonStorage storage, int slot)
    {
        ShadowDragonDef def = storage.getDef(slot);
        return def != null && def.name != null && !def.name.isBlank() ? def.name : "Shadow Dragon " + slot;
    }
}
