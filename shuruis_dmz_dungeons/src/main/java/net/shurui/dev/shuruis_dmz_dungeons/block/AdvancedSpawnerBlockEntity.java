package net.shurui.dev.shuruis_dmz_dungeons.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.registry.ModBlockEntities;

import javax.annotation.Nullable;

// BE for the advanced spawn block. server holds the real SpawnerConfig (NBT) and runs a proximity-gated,
// nearby-capped spawner (tamer than BaseSpawner). client only needs the disguise id for rendering, so that's
// all we sync in the block-update tag.
public class AdvancedSpawnerBlockEntity extends BlockEntity {

    // mob-search boxes (alive counts, resets, boss checks) reach at least this far
    private static final double SEARCH_RANGE = 32.0;

    // activation distance = server render distance in blocks, so a spawner starts as soon as its area can be seen
    private static double activationRange(ServerLevel level) {
        return Math.max(SEARCH_RANGE, level.getServer().getPlayerList().getViewDistance() * 16);
    }

    // stamped on spawned mobs so the spawner can find/track them; survives save/load
    static final String TAG_SPAWNER = "sdd_spawner"; // long: owning spawner BlockPos.asLong()
    static final String TAG_WANDER = "sdd_wander";   // int: max wander distance in blocks
    static final String TAG_BOSS = "sdd_boss";       // boolean: this mob is the spawner's boss

    private SpawnerConfig config = new SpawnerConfig();
    private int spawnDelay = 20;

    // In-memory UUIDs of the mobs this spawner spawned, so alive-counting and tethering resolve them by id
    // (ServerLevel.getEntity) instead of scanning a wide AABB every wave and every 10 ticks. bossId is the single
    // live boss (the boss-alive check forbids a second). Rebuilt once from a tag scan after a restart / chunk reload
    // (see ensureTracked) so mobs persisted from before this session are still counted and tethered exactly.
    private final java.util.Set<java.util.UUID> spawnedIds = new java.util.HashSet<>();
    @Nullable
    private java.util.UUID bossId;
    // false until we have recovered tracking from disk for this loaded instance; keeps the fallback scan one-off
    private boolean tracked;

    // lazily-resolved disguise state (both sides). null => render as a mob spawner
    @Nullable
    private BlockState cachedDisguise;
    private boolean disguiseResolved;

    public AdvancedSpawnerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ADVANCED_SPAWNER.get(), pos, state);
    }

    public SpawnerConfig getConfig() {
        return config;
    }

    // swap config (after a validated save) and push a block update so clients re-render. editing RESETS the
    // spawner Pixelmon-style: everything it spawned is removed, next wave comes shortly. keeps stat/entity edits
    // from leaving stale NPCs around.
    public void setConfig(SpawnerConfig newConfig) {
        this.config = newConfig;
        this.disguiseResolved = false;
        setChanged();
        if (level instanceof ServerLevel server) {
            resetSpawnedMobs(server);
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    // set the config at generation time without the edit-time side effects (no spawned-mob reset, no per-block update):
    // the placement task force-loads and resends whole chunks itself, and a freshly placed spawner has nothing to reset.
    // used by SpawnerConversionTask when it drops a disguised spawner into a floor block.
    public void applyGeneratedConfig(SpawnerConfig newConfig) {
        this.config = newConfig;
        this.disguiseResolved = false;
        setChanged();
    }

    // remove everything this spawner spawned (they carry TAG_SPAWNER), bosses too, and queue a fresh wave.
    // pre-feature mobs carry no tag, so they're left alone.
    private void resetSpawnedMobs(ServerLevel server) {
        long key = worldPosition.asLong();
        var box = new net.minecraft.world.phys.AABB(worldPosition)
                .inflate(Math.max(SEARCH_RANGE, config.wanderDistance) + 48);
        for (var e : server.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
                m -> m.getPersistentData().contains(TAG_SPAWNER)
                        && m.getPersistentData().getLong(TAG_SPAWNER) == key)) {
            e.discard();
        }
        // everything we tracked was just discarded; tracking is now authoritative and empty
        spawnedIds.clear();
        bossId = null;
        tracked = true;
        spawnDelay = 20;
    }

    // recover tracking from disk once per loaded instance. after a restart / chunk reload the in-memory sets are
    // empty but our tagged mobs may still be alive, so do a single wide tag scan to repopulate, then rely on the
    // by-id fast path. identifies mobs by our own TAG_SPAWNER stamp, NOT by config.entityType(): a savedNpcRef
    // spawner spawns dmz_fighter regardless of the configured type, so a type filter would miss its own mobs.
    private void ensureTracked(ServerLevel level, BlockPos pos) {
        if (tracked) {
            return;
        }
        tracked = true;
        long key = pos.asLong();
        var box = new net.minecraft.world.phys.AABB(pos)
                .inflate(Math.max(SEARCH_RANGE, config.wanderDistance) + 48);
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
                m -> m.getPersistentData().contains(TAG_SPAWNER)
                        && m.getPersistentData().getLong(TAG_SPAWNER) == key)) {
            if (e.getPersistentData().getBoolean(TAG_BOSS)) {
                bossId = e.getUUID();
            } else {
                spawnedIds.add(e.getUUID());
            }
        }
    }

    // drop ids whose entity is gone/dead; a single pass gives both the live non-boss count and boss presence.
    private void pruneDead(ServerLevel level) {
        spawnedIds.removeIf(id -> !isAlive(level, id));
        if (bossId != null && !isAlive(level, bossId)) {
            bossId = null;
        }
    }

    private static boolean isAlive(ServerLevel level, java.util.UUID id) {
        return level.getEntity(id) instanceof net.minecraft.world.entity.LivingEntity le && le.isAlive();
    }

    // disguise state to render, or null for the vanilla mob spawner
    @Nullable
    public BlockState disguiseState() {
        if (!disguiseResolved) {
            disguiseResolved = true;
            cachedDisguise = null;
            String id = config.disguise;
            if (id != null && !id.isBlank()) {
                var loc = net.minecraft.resources.ResourceLocation.tryParse(id);
                if (loc != null) {
                    Block b = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(loc);
                    if (b != null && b != Blocks.AIR) {
                        cachedDisguise = b.defaultBlockState();
                    }
                }
            }
        }
        return cachedDisguise;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, AdvancedSpawnerBlockEntity be) {
        if (level instanceof ServerLevel server) {
            be.tickSpawn(server, pos);
            // keep mobs tethered, checked a couple times a second
            if (be.config.wanderDistance > 0 && (server.getGameTime() % 10L) == 0L) {
                be.enforceWander(server, pos);
            }
        }
    }

    // keep this spawner's mobs within wanderDistance. Mob.restrictTo is a no-op for normal mobs (stroll goals
    // ignore the home restriction), so tether by hand: just past the edge -> walk home; well beyond (chasing a
    // player) -> teleport home. found by spawner id in persistent data.
    private void enforceWander(ServerLevel level, BlockPos pos) {
        int dist = config.wanderDistance;
        double homeX = pos.getX() + 0.5, homeY = pos.getY() + 1.0, homeZ = pos.getZ() + 0.5;
        double radius2 = (double) dist * dist;
        double leash2 = (double) (dist + 6) * (dist + 6);
        // resolve our own mobs by id (all carry TAG_WANDER while wanderDistance > 0) instead of a wide box scan
        ensureTracked(level, pos);
        pruneDead(level);
        for (java.util.UUID id : spawnedIds) {
            tether(level.getEntity(id), homeX, homeY, homeZ, radius2, leash2);
        }
        if (bossId != null) {
            tether(level.getEntity(bossId), homeX, homeY, homeZ, radius2, leash2);
        }
    }

    private static void tether(@Nullable Entity e, double homeX, double homeY, double homeZ,
                               double radius2, double leash2) {
        if (!(e instanceof Mob mob)) {
            return;
        }
        double d2 = mob.distanceToSqr(homeX, homeY, homeZ);
        if (d2 <= radius2) {
            return;
        }
        if (d2 > leash2) {
            // too far (combat AI keeps re-pathing) - yank it home
            mob.moveTo(homeX, homeY, homeZ, mob.getYRot(), mob.getXRot());
            mob.getNavigation().stop();
            return;
        }
        // just past the edge - walk it back, but don't recompute the path if it is already heading home
        var nav = mob.getNavigation();
        if (nav.isDone() || !pathLeadsHome(nav.getPath(), homeX, homeY, homeZ)) {
            nav.moveTo(homeX, homeY, homeZ, 1.1);
        }
    }

    // true when the mob's current path already targets (within 2 blocks of) home, so re-issuing moveTo is redundant
    private static boolean pathLeadsHome(@Nullable net.minecraft.world.level.pathfinder.Path path,
                                         double homeX, double homeY, double homeZ) {
        if (path == null) {
            return false;
        }
        BlockPos t = path.getTarget();
        double tdx = (t.getX() + 0.5) - homeX;
        double tdy = (t.getY() + 0.5) - homeY;
        double tdz = (t.getZ() + 0.5) - homeZ;
        return tdx * tdx + tdy * tdy + tdz * tdz <= 4.0;
    }

    // short retry when a wave spawns nothing, so transient failures don't stall us
    private static final int FAILED_WAVE_RETRY_TICKS = 40;

    private void tickSpawn(ServerLevel level, BlockPos pos) {
        if (spawnDelay > 0) {
            spawnDelay--;
            return;
        }
        // cooldown between waves; reset every activation even if we don't end up spawning
        spawnDelay = Math.max(1, config.cooldownTicks);

        // on/off + spawn conditions (time / weather / redstone)
        if (!config.enabled || !conditionsMet(level, pos)) {
            return;
        }

        Player nearest = level.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                activationRange(level), false);
        if (nearest == null) {
            return;
        }

        EntityType<?> type = config.entityType();
        if (type == null) {
            return;
        }
        // spawn amount = max alive at once; a wave refills however many are missing at once, then waits the
        // cooldown. alive-count box uses the wander tether (where mobs can be), not the activation range - the
        // two are independent now that activation follows render distance.
        int cap = Math.max(1, config.maxSpawns);
        // count only THIS spawner's mobs, resolved by the ids we tracked when we spawned them (recovered from a tag
        // scan after a reload). identifying by our own stamp rather than config.entityType() is also the fix for the
        // savedNpcRef cap leak: a savedNpcRef spawner spawns dmz_fighter regardless of the configured type, so the
        // old type filter never matched its own mobs and refilled a full wave forever. one prune pass gives both the
        // alive count here and the boss-alive result maybeSpawnBoss reads below.
        ensureTracked(level, pos);
        pruneDead(level);
        int alive = spawnedIds.size();
        int toSpawn = cap - alive;
        int spawned = 0;
        for (int i = 0; i < toSpawn; i++) {
            if (spawnOne(level, pos)) {
                spawned++;
            }
        }
        if (maybeSpawnBoss(level, pos)) {
            spawned++;
        }
        // wave spawned nothing (e.g. Bukkit cancelled every spawn) -> retry soon instead of the full cooldown,
        // but only when we actually WANTED to spawn (toSpawn > 0). a full-but-idle wave keeps normal cooldown.
        if (spawned == 0 && toSpawn > 0) {
            spawnDelay = Math.min(spawnDelay, FAILED_WAVE_RETRY_TICKS);
        }
    }

    // true when every configured spawn condition (time / weather / redstone) currently holds
    private boolean conditionsMet(ServerLevel level, BlockPos pos) {
        long t = level.getDayTime() % 24000L;
        boolean ok = switch (config.timeCondition) {
            case 1 -> t < 13000L;                    // day
            case 2 -> t >= 13000L && t < 23000L;     // night
            case 3 -> t >= 23000L || t < 3000L;      // morning (sunrise)
            case 4 -> t >= 12000L && t < 14000L;     // dusk (sunset)
            default -> true;
        };
        if (!ok) {
            return false;
        }
        ok = switch (config.weatherCondition) {
            case 1 -> !level.isRaining() && !level.isThundering();
            case 2 -> level.isRaining();
            case 3 -> level.isThundering();
            default -> true;
        };
        if (!ok) {
            return false;
        }
        return switch (config.redstoneMode) {
            case 1 -> level.hasNeighborSignal(pos);
            case 2 -> !level.hasNeighborSignal(pos);
            default -> true;
        };
    }

    // boss roll, once per wave, if enabled + no boss of ours alive + the chance hits.
    //
    // PUBLIC since 2026-08-30. Used to be locked behind a key check, so a keyless server got no boss ever
    // while the block and its disguises were public. Gate kept but now asks the named public feature, so it can be
    // withheld again with one edit to PUBLIC_FEATURES.
    private boolean maybeSpawnBoss(ServerLevel level, BlockPos pos) {
        if (!config.bossEnabled || config.bossChance <= 0) {
            return false;
        }
        if (!net.shurui.shuruisutilities.core.config.PublicContent.allows(
                net.shurui.shuruisutilities.core.config.PublicContent.FEATURE_SPAWNER_BOSS)) {
            return false;
        }
        // boss presence comes from the same tracked-id pass tickSpawn already pruned (bossId != null means alive)
        if (bossId != null) {
            return false;
        }
        if (level.random.nextFloat() * 100.0f >= config.bossChance) {
            return false;
        }
        return spawnBoss(level, pos);
    }

    // pick a spawn spot near the spawner: try a few offsets, return the first whose AABB doesn't collide
    // (no spawning in walls). fall back to the original random spot (with a debug log) if nothing's clear.
    private double[] pickSpawnPos(ServerLevel level, BlockPos pos, @Nullable EntityType<?> type) {
        double baseX = pos.getX() + 0.5;
        double baseY = pos.getY() + 1.0;
        double baseZ = pos.getZ() + 0.5;
        double firstX = baseX + (level.random.nextDouble() - 0.5) * 2.0;
        double firstZ = baseZ + (level.random.nextDouble() - 0.5) * 2.0;
        if (type == null) {
            return new double[]{firstX, baseY, firstZ};
        }
        for (int i = 0; i < 6; i++) {
            double x = i == 0 ? firstX : baseX + (level.random.nextDouble() - 0.5) * 2.0;
            double z = i == 0 ? firstZ : baseZ + (level.random.nextDouble() - 0.5) * 2.0;
            if (level.noCollision(type.getAABB(x, baseY, z))) {
                return new double[]{x, baseY, z};
            }
        }
        Shuruis_dmz_dungeons.LOGGER.debug("[{}] No clear spawn spot near {} for {}; spawning at original spot",
                Shuruis_dmz_dungeons.MODID, pos, type);
        return new double[]{firstX, baseY, firstZ};
    }

    private boolean spawnOne(ServerLevel level, BlockPos pos) {
        EntityType<?> chosenType = config.entityType();
        double[] p = pickSpawnPos(level, pos, chosenType);
        double x = p[0], y = p[1], z = p[2];
        float yaw = level.random.nextFloat() * 360.0f;

        // build from NBT so sdu's DmzNpcEntity reads its ki/behaviour/model keys on load (ignored by other
        // types). loadEntityRecursive resolves the type from "id".
        CompoundTag tag = new CompoundTag();
        if (config.savedNpcRef != null && !config.savedNpcRef.isBlank()) {
            // sdu saga fighter configured from the saved Custom NPC clone; the fighter reads "sdu_clone_ref"
            // off its Forge persistent data on its first tick
            tag.putString("id", "dmz_ragnarok:dmz_fighter");
            CompoundTag forge = new CompoundTag();
            forge.putString("sdu_clone_ref", "cnpc$" + config.savedNpcRef.trim());
            tag.put("ForgeData", forge);
            // clone configurator doesn't touch scale, so write ModelScale too (fighter reads it in
            // readAdditionalSaveData/applySuSpawnNbt on load)
            tag.putFloat("ModelScale", config.scale);
        } else {
            tag.putString("id", config.entityTypeId);
            // roll per-spawn ki damage + per-move cooldowns off the level RNG so each mob varies
            config.writeNpcNbt(tag, level.random);
        }
        Entity entity = EntityType.loadEntityRecursive(tag, level, e -> {
            e.moveTo(x, y, z, yaw, 0.0f);
            return e;
        });
        if (entity == null) {
            return false;
        }

        if (entity instanceof Mob mob) {
            // let vanilla/other-mod mobs do their normal AI init before we overwrite stats
            try {
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.SPAWNER, null, null);
            } catch (Throwable t) {
                Shuruis_dmz_dungeons.LOGGER.debug("[{}] finalizeSpawn failed for {}: {}",
                        Shuruis_dmz_dungeons.MODID, config.entityTypeId, t.toString());
            }
            // ownership stamp so the spawner can find (and on edit remove) what it spawned
            var pd = mob.getPersistentData();
            pd.putLong(TAG_SPAWNER, pos.asLong());
            // tether enforced in enforceWander() (Mob.restrictTo alone does nothing)
            if (config.wanderDistance > 0) {
                pd.putInt(TAG_WANDER, config.wanderDistance);
            }
        }
        // The chosen ragnarok NPC character, for a spawned rgnpc. Every other entity type ignores it. Separate
        // from config.modelId, which is the CustomNPCs model key written into the spawn NBT above.
        net.shurui.shuruisutilities.ragnarok.RgNpcLook.apply(entity, config.rgModelId);
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            config.applyTo(living); // health/damage/speed/armor + name
            // opt out of DMZ's onEntityJoinWorld config-stat overwrite so our stats survive (DMZ 2.1.3)
            living.getPersistentData().putBoolean("dmz_stats_configured", true);
            // sdu transform chain as raw NBT (sdu-agnostic): engine reads "sdu_tf" / "dmz_quest_no_transform"
            stampTransform(living, config.mainTransform, config.mainUseDefaultTransform);
        }
        // kill rewards (TP/balance ranges, custom drops) for any entity type; see SpawnerRewardEvents
        stampRewards(entity);
        // on Mohist a Bukkit CreatureSpawnEvent (mob-limit plugins etc.) can silently cancel this Forge spawn;
        // logging the rejection is the only way admins ever see the otherwise-invisible no-spawn
        boolean added = level.addFreshEntity(entity);
        if (added && entity instanceof Mob) {
            spawnedIds.add(entity.getUUID()); // track by id so the alive count resolves it without a box scan
        } else if (!added) {
            Shuruis_dmz_dungeons.LOGGER.warn("[{}] Spawn rejected at {} for {} (addFreshEntity=false; "
                    + "a Bukkit CreatureSpawnEvent or mob-limit plugin may have cancelled it)",
                    Shuruis_dmz_dungeons.MODID, pos, entity.getType());
        }
        return added;
    }

    // same pipeline as spawnOne, but with the boss's entity/stats/clone
    private boolean spawnBoss(ServerLevel level, BlockPos pos) {
        double[] p = pickSpawnPos(level, pos, config.bossEntityType());
        double x = p[0], y = p[1], z = p[2];
        float yaw = level.random.nextFloat() * 360.0f;

        CompoundTag tag = new CompoundTag();
        if (config.bossSavedNpcRef != null && !config.bossSavedNpcRef.isBlank()) {
            tag.putString("id", "dmz_ragnarok:dmz_fighter");
            CompoundTag forge = new CompoundTag();
            forge.putString("sdu_clone_ref", "cnpc$" + config.bossSavedNpcRef.trim());
            tag.put("ForgeData", forge);
            // clone configurator doesn't touch scale - write boss ModelScale so the fighter applies it on load
            tag.putFloat("ModelScale", config.bossScale);
        } else {
            if (config.bossEntityType() == null) {
                return false;
            }
            tag.putString("id", config.bossEntityTypeId);
            config.writeBossNpcNbt(tag, level.random);
        }
        Entity entity = EntityType.loadEntityRecursive(tag, level, e -> {
            e.moveTo(x, y, z, yaw, 0.0f);
            return e;
        });
        if (entity == null) {
            return false;
        }
        if (entity instanceof Mob mob) {
            try {
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.SPAWNER, null, null);
            } catch (Throwable t) {
                Shuruis_dmz_dungeons.LOGGER.debug("[{}] finalizeSpawn failed for boss {}: {}",
                        Shuruis_dmz_dungeons.MODID, config.bossEntityTypeId, t.toString());
            }
            var pd = mob.getPersistentData();
            pd.putLong(TAG_SPAWNER, pos.asLong());
            pd.putBoolean(TAG_BOSS, true);
            if (config.wanderDistance > 0) {
                pd.putInt(TAG_WANDER, config.wanderDistance);
            }
        }
        net.shurui.shuruisutilities.ragnarok.RgNpcLook.apply(entity, config.bossRgModelId);
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            config.applyBossTo(living);
            // opt out of DMZ's onEntityJoinWorld config-stat overwrite so our applied stats survive (DMZ 2.1.3)
            living.getPersistentData().putBoolean("dmz_stats_configured", true);
            stampTransform(living, config.bossTransform, config.bossUseDefaultTransform);
        }
        stampRewards(entity);
        boolean added = level.addFreshEntity(entity);
        if (added && entity instanceof Mob) {
            bossId = entity.getUUID(); // track the single live boss by id
        } else if (!added) {
            Shuruis_dmz_dungeons.LOGGER.warn("[{}] Boss spawn rejected at {} for {} (addFreshEntity=false; "
                    + "a Bukkit CreatureSpawnEvent or mob-limit plugin may have cancelled it)",
                    Shuruis_dmz_dungeons.MODID, pos, entity.getType());
        }
        return added;
    }

    // stamp an sdu transform chain via plain persistent-data NBT (no sdu classes). non-empty chain (has a
    // "forms" list) -> "sdu_tf" + suppress DMZ's built-in transforms. no chain + useDefault=false still
    // suppresses DMZ's defaults.
    private static void stampTransform(net.minecraft.world.entity.LivingEntity e, CompoundTag chain, boolean useDefault) {
        var pd = e.getPersistentData();
        boolean hasChain = chain != null
                && chain.contains("forms", net.minecraft.nbt.Tag.TAG_LIST)
                && !chain.getList("forms", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty();
        if (hasChain) {
            pd.put("sdu_tf", chain.copy());
            pd.putBoolean("dmz_quest_no_transform", true);
        } else if (!useDefault) {
            pd.putBoolean("dmz_quest_no_transform", true);
        }
    }

    // copy the config's kill rewards onto a freshly spawned entity's persistent data
    private void stampRewards(Entity entity) {
        var pd = entity.getPersistentData();
        if (!config.vanillaDrops) {
            pd.putBoolean(net.shurui.dev.shuruis_dmz_dungeons.event.SpawnerRewardEvents.NO_VANILLA_DROPS_TAG, true);
        }
        if (config.killCommands != null && !config.killCommands.isBlank()) {
            pd.putString(net.shurui.dev.shuruis_dmz_dungeons.event.SpawnerRewardEvents.KILL_COMMANDS_TAG, config.killCommands);
        }
        if (config.tpMin > 0 || config.tpMax > 0) {
            pd.putInt(net.shurui.dev.shuruis_dmz_dungeons.event.SpawnerRewardEvents.TP_MIN_TAG, config.tpMin);
            pd.putInt(net.shurui.dev.shuruis_dmz_dungeons.event.SpawnerRewardEvents.TP_MAX_TAG, config.tpMax);
        }
        if (config.balMin > 0 || config.balMax > 0) {
            pd.putInt(net.shurui.dev.shuruis_dmz_dungeons.event.SpawnerRewardEvents.BAL_MIN_TAG, config.balMin);
            pd.putInt(net.shurui.dev.shuruis_dmz_dungeons.event.SpawnerRewardEvents.BAL_MAX_TAG, config.balMax);
        }
        if (!config.drops.isEmpty()) {
            net.minecraft.nbt.ListTag dropList = new net.minecraft.nbt.ListTag();
            for (CustomDrop d : config.drops) {
                dropList.add(d.save());
            }
            pd.put(net.shurui.dev.shuruis_dmz_dungeons.event.SpawnerRewardEvents.DROPS_TAG, dropList);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Config", config.save(new CompoundTag()));
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Config")) {
            config = SpawnerConfig.load(tag.getCompound("Config"));
        }
        disguiseResolved = false;
        // a reloaded instance must recover its spawned-mob ids once from a tag scan before trusting the fast path
        tracked = false;
        spawnedIds.clear();
        bossId = null;
    }

    // sync the disguise (rendering) plus a full config copy so creative pick-block (getCloneItemStack) can clone a
    // configured spawner. editor is creative/op-only, so handing the config to clients is basically free.
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        // disguises are ungated: every server can use them (the key locks boss spawns instead)
        tag.putString("Disguise", config.disguise == null ? "" : config.disguise);
        tag.put("Config", config.save(new CompoundTag()));
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        if (tag.contains("Config")) {
            config = SpawnerConfig.load(tag.getCompound("Config"));
        } else {
            config.disguise = tag.getString("Disguise");
        }
        disguiseResolved = false;
    }

    // full config under the same "Config" key the BE persists to; used by getCloneItemStack for pick-block
    public CompoundTag saveConfigTag() {
        return config.save(new CompoundTag());
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, ClientboundBlockEntityDataPacket pkt) {
        if (pkt.getTag() != null) {
            handleUpdateTag(pkt.getTag());
        }
    }
}
