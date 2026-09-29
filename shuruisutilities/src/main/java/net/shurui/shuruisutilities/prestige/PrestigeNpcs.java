package net.shurui.shuruisutilities.prestige;

import net.minecraft.world.entity.Entity;

/**
 * A prestige NPC is any registered entity tagged (in its Forge persistent data) as a prestige master, exactly
 * like the tournament / raid-boss host NPCs. A global interact handler opens the prestige screen for any tagged
 * entity, so a DMZ, sdu or vanilla mob can all serve as the prestige master.
 *
 * <p>Since S19b only the tag (a persisted entity data key) and the permission node names stay here, because public
 * code asks {@link #isPrestigeNpc} (combat bystanders) and names the nodes. Spawning a prestige NPC, the interact
 * handler and the prestige screens are private and live in the Ragnarok Key.
 */
public final class PrestigeNpcs
{
    private PrestigeNpcs() {}

    /** Persistent-data key marking an entity as a prestige master. */
    public static final String TAG = "SuPrestige";

    public static final String PERM_USE = "su.prestige.use";
    public static final String PERM_ADMIN = "su.prestige.admin";

    public static boolean isPrestigeNpc(Entity entity)
    {
        return entity != null && entity.getPersistentData().getBoolean(TAG);
    }

    public static void tag(Entity entity)
    {
        entity.getPersistentData().putBoolean(TAG, true);
    }
}
