package net.shurui.shuruisutilities.cosmetics.wardrobe.pet;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.shard.PreHopTeardown;

/**
 * Server lifecycle for cosmetic pets: keep the spawned pet in line with the equipped PET slot across every path that
 * a wardrobe change alone does not cover.
 *
 * <p>The annotation is deliberately BARE (no modid): the five original addons are one jar now, so naming a modid is
 * a chance to name the wrong one, and a wrong modid on an {@code @Mod.EventBusSubscriber} fails SILENTLY. Only
 * FORGE-bus, server-relevant events live here; the equip-time reconcile is driven straight from
 * {@code WardrobeManager.equip} so it catches the GUI, the command and the editor at one point.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class CosmeticPetEvents
{
    private CosmeticPetEvents()
    {
    }

    /** How often the liveness reconcile runs, in server ticks. Two seconds: cheap, and quick to bring a lost pet back. */
    private static final int RECONCILE_INTERVAL = 40;

    private static int reconcileClock;

    /**
     * Slow-tick liveness reconcile. A cosmetic pet is {@code .noSave()}, so a chunk unload, the entity's own
     * non-finite guard or a brief cross-level flicker can remove it while the manager still holds a (now dead)
     * reference, and only a lifecycle event would otherwise re-spawn it. Every {@link #RECONCILE_INTERVAL} ticks,
     * ask the manager to revive any player's pet whose entity has gone. Only players who already have a tracked pet
     * cost anything, so this is a light sweep.
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        if (++reconcileClock < RECONCILE_INTERVAL)
            return;
        reconcileClock = 0;
        MinecraftServer server = event.getServer();
        if (server == null)
            return;
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            CosmeticPetManager.tickReconcile(player);
    }

    /**
     * Register the pre-hop teardown. A pet entity does not travel a shard hop (the destination shard never spawns
     * it), so it must be despawned BEFORE the vault capture, exactly like the mount, or the owner would leave a
     * pet standing on the origin. Idempotent, and a no-op for a player with no pet.
     */
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event)
    {
        PreHopTeardown.register("cosmetic_pet", CosmeticPetManager::despawn);
    }

    /**
     * On login (a fresh join OR a shard-hop arrival, which is a login), spawn the equipped pet if there is one. LOW
     * priority so the vault has applied the arriving wardrobe first; the reconcile then reads the settled slot.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
            CosmeticPetManager.refresh(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            CosmeticPetManager.despawn(player);
            CosmeticPetManager.forget(player.getUUID());
        }
    }

    /**
     * Respawn (after death, or the end-portal return): the player entity is new, so re-spawn the pet to follow it.
     */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
            CosmeticPetManager.refresh(player);
    }

    /**
     * Dimension change: the pet cannot cross with the player, so the old one is discarded (the entity's own tick
     * also retires a pet whose owner left its level) and a fresh one is spawned in the destination level.
     */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
            CosmeticPetManager.refresh(player);
    }

    /**
     * The owner died: remove the pet now rather than leaving it standing over the body. It comes back on respawn.
     */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
            CosmeticPetManager.despawn(player);
    }
}
