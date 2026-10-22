package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.dragonminez.common.init.entities.questnpc.QuestNPCEntity;
import com.dragonminez.server.world.npc.NPCPlacementManager;
import com.dragonminez.server.world.npc.NPCPlacementSavedData;
import com.dragonminez.server.world.structure.helper.StructureFrame;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Puts Tien (Budokai Tenkaichi grounds) and Majin Buu (Buu's house) into structures that were generated before they
 * were added, so worlds made on older DragonMineZ versions get both masters without regenerating anything.
 *
 * <p>Buu comes from an entity baked into DMZ's buu_house template, which only spawns when the structure is first
 * generated. Tien comes from DMZ's structure_npcs list. Both are placed at the exact spot DMZ uses, in the
 * structure's own frame (rotation and offset included).
 *
 * <p>Once per structure: a placement key in DMZ's own placement data records it. Before spawning, the structure's
 * area is checked for that master (so a world that already has him, from the template or from DMZ, just records the
 * key). Entities load separately from chunks, so a chunk is only judged once its entities are in.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class MasterBackfill
{
    private MasterBackfill() {}

    private record Master(String id, ResourceLocation structure, ResourceLocation entity, String questNpcId,
                          Vec3 local, float yaw, String dmzKeyId) {}

    private static final List<Master> MASTERS = List.of(
            new Master("master_tien", new ResourceLocation("dragonminez", "budokai_tenkaichi"),
                    new ResourceLocation("dragonminez", "master_tien"), null, new Vec3(78.5, 24.0, 95.5), 270.0F,
                    "master_tien"),
            new Master("master_buu", new ResourceLocation("dragonminez", "buu_house"),
                    new ResourceLocation("dragonminez", "quest_npc"), "buu", new Vec3(44.74, 3.0, 74.49), 0.0F, null));

    private static final int CHUNKS_PER_TICK = 4;
    private static final int MAX_RETRIES = 200;

    private static final Map<ResourceKey<Level>, ConcurrentLinkedQueue<Long>> PENDING = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Map<Long, Integer>> RETRIES = new ConcurrentHashMap<>();

    /** -Ddmz_ragnarok.masterBackfill=false turns it off (for testing, or a server that places masters by hand). */
    private static final boolean ENABLED = !"false".equals(System.getProperty("dmz_ragnarok.masterBackfill"));

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event)
    {
        if (!ENABLED) return;
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        if (level.dimension() != Level.OVERWORLD) return;
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Structure structure : chunk.getAllReferences().keySet())
        {
            ResourceLocation id = registry.getKey(structure);
            if (id != null && isTracked(id))
            {
                PENDING.computeIfAbsent(level.dimension(), k -> new ConcurrentLinkedQueue<>()).add(chunk.getPos().toLong());
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event)
    {
        if (event.getLevel() instanceof ServerLevel level)
        {
            PENDING.remove(level.dimension());
            RETRIES.remove(level.dimension());
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        ConcurrentLinkedQueue<Long> queue = PENDING.get(level.dimension());
        if (queue == null || queue.isEmpty()) return;

        Set<Long> batch = new LinkedHashSet<>();
        Long next;
        while (batch.size() < CHUNKS_PER_TICK && (next = queue.poll()) != null) batch.add(next);
        Map<Long, Integer> retries = RETRIES.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        List<Long> again = new ArrayList<>();
        for (long packed : batch)
        {
            ChunkPos pos = new ChunkPos(packed);
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null || !level.areEntitiesLoaded(packed))
            {
                if (retries.merge(packed, 1, Integer::sum) <= MAX_RETRIES) again.add(packed);
                else retries.remove(packed);
                continue;
            }
            retries.remove(packed);
            try
            {
                process(level, chunk);
            }
            catch (RuntimeException e)
            {
                LoggingHandler.sulog.warn("[masters] backfill failed on chunk {}: {}", pos, e.toString());
            }
        }
        queue.addAll(again);
    }

    private static boolean isTracked(ResourceLocation structure)
    {
        for (Master m : MASTERS) if (m.structure().equals(structure)) return true;
        return false;
    }

    private static void process(ServerLevel level, LevelChunk chunk)
    {
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Structure structure : chunk.getAllReferences().keySet())
        {
            ResourceLocation id = registry.getKey(structure);
            if (id == null || !isTracked(id)) continue;
            for (StructureStart start : level.structureManager().startsForStructure(chunk.getPos(), c -> c == structure))
            {
                if (!start.isValid()) continue;
                for (Master m : MASTERS) if (m.structure().equals(id)) placeIfMissing(level, chunk.getPos(), start, m);
            }
        }
    }

    private static void placeIfMissing(ServerLevel level, ChunkPos chunk, StructureStart start, Master m)
    {
        Optional<StructureFrame> frame = StructureFrame.forTemplate(start, null);
        if (frame.isEmpty()) return;
        Vec3 pos = frame.get().toWorld(m.local());
        BlockPos block = BlockPos.containing(pos);
        if (SectionPos.blockToSectionCoord(block.getX()) != chunk.x
                || SectionPos.blockToSectionCoord(block.getZ()) != chunk.z) return;

        NPCPlacementSavedData tracking = NPCPlacementSavedData.get(level);
        String key = "dmz_ragnarok/backfill/" + m.structure() + "/" + start.getChunkPos().toLong() + "/" + m.id();
        if (tracking.hasPlacement(key)) return;
        // DMZ's own structure_npcs key for the same spot: if DMZ already placed him, leave it to DMZ.
        String dmzKey = m.dmzKeyId() == null ? null
                : "structure/" + m.structure() + "/" + start.getChunkPos().toLong() + "/" + m.dmzKeyId();

        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(m.entity());
        if (type == null) return;

        Entity existing = findExisting(level, start, type, m);
        if (existing != null || (dmzKey != null && tracking.hasPlacement(dmzKey)))
        {
            tracking.markSpawned(key, existing != null ? existing.getUUID() : new java.util.UUID(0L, 0L));
            return;
        }

        Entity entity = type.create(level);
        if (entity == null) return;
        float yaw = frame.get().toWorldYaw(m.yaw());
        entity.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);
        entity.setYHeadRot(yaw);
        entity.setYBodyRot(yaw);
        entity.setInvulnerable(true);
        if (entity instanceof Mob mob) mob.setPersistenceRequired();
        entity.getPersistentData().putString(NPCPlacementManager.PLACEMENT_TAG, m.id());
        if (entity instanceof QuestNPCEntity npc && m.questNpcId() != null)
        {
            npc.setNpcId(m.questNpcId());
            npc.setMasterNpc(true);
        }
        if (!level.addFreshEntity(entity)) return;
        tracking.markSpawned(key, entity.getUUID());
        if (dmzKey != null) tracking.markSpawned(dmzKey, entity.getUUID());
        LoggingHandler.sulog.info("[masters] placed {} in {} at {}, {}, {}", m.id(), m.structure(),
                Math.round(pos.x), Math.round(pos.y), Math.round(pos.z));
    }

    private static Entity findExisting(ServerLevel level, StructureStart start, EntityType<?> type, Master m)
    {
        AABB box = AABB.of(start.getBoundingBox()).inflate(16.0);
        for (Entity e : level.getEntities((Entity) null, box, e -> e.getType() == type))
        {
            if (m.questNpcId() == null) return e;
            if (e instanceof QuestNPCEntity npc && m.questNpcId().equals(npc.getNpcId())) return e;
        }
        return null;
    }
}
