package net.shurui.shuruisutilities.ocarina;

import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.dragonminez.common.init.entities.questnpc.QuestNPCEntity;
import com.dragonminez.server.world.npc.NPCPlacementSavedData;

import net.shurui.shuruisutilities.api.key.OcarinaHooks;

/**
 * Tapion, the ocarina master. He is a DragonMineZ quest NPC ({@code dragonminez:quest_npc}, npc id {@code tapion})
 * placed by DMZ's own StructureNpcs beside Trunks' ship; the placement file ships in the Ragnarok Key, so a keyless
 * server never places him.
 *
 * <p>This is the keyless half: a Tapion saved while a key was installed is not let into a keyless world (private
 * things are absent, never half there), and his placement record is cleared so the key places him again later.
 */
public final class TapionNpc
{
    private TapionNpc() {}

    public static boolean isTapion(Entity e)
    {
        return e instanceof QuestNPCEntity q && OcarinaSkills.TAPION.equals(q.getNpcId());
    }

    /**
     * Forget DMZ's record that this Tapion was placed, so StructureNpcs places him again the next time his chunk is
     * scanned. The record maps a tracking key to the entity's UUID; it has no lookup by UUID, so the map is read.
     */
    public static void clearPlacement(ServerLevel level, Entity tapion)
    {
        try
        {
            NPCPlacementSavedData data = NPCPlacementSavedData.get(level);
            java.lang.reflect.Field f = NPCPlacementSavedData.class.getDeclaredField("placements");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, UUID> placements = (Map<String, UUID>) f.get(data);
            UUID id = tapion.getUUID();
            String found = null;
            for (Map.Entry<String, UUID> e : placements.entrySet())
                if (id.equals(e.getValue()))
                    found = e.getKey();
            if (found != null)
                data.clear(found);
        }
        catch (Throwable ignored)
        {
            // a DMZ build without the record: nothing to clear
        }
    }

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok")
    public static final class KeylessGuard
    {
        private KeylessGuard() {}

        @SubscribeEvent
        public static void onJoin(EntityJoinLevelEvent event)
        {
            if (event.getLevel().isClientSide() || !isTapion(event.getEntity()) || OcarinaHooks.available())
                return;
            if (event.getLevel() instanceof ServerLevel sl)
                clearPlacement(sl, event.getEntity());
            event.setCanceled(true);
        }
    }
}
