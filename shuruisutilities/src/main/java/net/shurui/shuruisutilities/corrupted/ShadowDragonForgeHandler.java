package net.shurui.shuruisutilities.corrupted;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Forge-bus handler for the shadow dragon encounter's death tracking and restart restore, modelled on the
 * raid-bosses addon's {@code ForgeEventHandler}. Registered manually from {@link
 * net.shurui.shuruisutilities.core.ShuruisUtilities}, next to {@link CorruptedBallHandler}, which owns the
 * throttled upkeep tick.
 *
 * <p>Kill credit recorded here is consumed by phase 4 to unlock races, so it is kept accurate and is deliberately
 * NOT wiped when the encounter ends (the manager clears only the live dragons and the start tick, leaving credit
 * in place for phase 4 to read).
 */
public final class ShadowDragonForgeHandler
{
    /**
     * When a live shadow dragon dies, credit the killer, drop it from the live set, remove its pip, and end the
     * encounter when it was the last one. Uses {@code event.getSource().getEntity()}, which already resolves the
     * owner for projectiles and ki blasts, so ranged kills are credited too (same convention as the raid handler).
     */
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event)
    {
        if (!net.shurui.shuruisutilities.core.SUConfig.wishTrackingEnabled)
            return;
        LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide())
            return;
        MinecraftServer server = dead.getServer();
        if (server == null)
            return;

        ShadowDragonStorage storage = ShadowDragonStorage.get(server);

        // A player dying is not a dragon death: record it against every live dragon (a death mid fight disqualifies
        // the player from that dragon's top-damage-survivor sub-race, rule 2) and stop here.
        if (dead instanceof ServerPlayer deadPlayer)
        {
            for (UUID dragonId : storage.getLiveDragons().keySet())
                ShadowDragonDamageTracker.recordDeath(dragonId, deadPlayer.getUUID());
            return;
        }

        Integer slot = storage.getLiveDragons().get(dead.getUUID());
        if (slot == null)
            return; // not one of ours

        // Tier 2 (top-damage survivor): the player who dealt the most damage to this dragon and did not die during
        // its fight earns the matching sub-race. Read from the tracker BEFORE the entry is cleared. The winner may be
        // offline (granted via the durable property either way).
        ShadowDragonUnlocks.onDragonDefeated(server, slot, dead.getUUID());
        ShadowDragonDamageTracker.clear(dead.getUUID());

        // resolve the killing player (owner for projectiles/ki blasts). null when it died to the environment.
        ServerPlayer killer = event.getSource().getEntity() instanceof ServerPlayer p ? p : null;
        if (killer != null)
        {
            // Tier 3 (omega shenron): record the killing blow and grant omega / the transformation when the lifetime
            // seven-slot killing-blow threshold is newly met. onKillCredited records the kill credit itself. The
            // sub-race (tier 2) is NOT granted here anymore; it is the top-damage-survivor award above.
            ShadowDragonUnlocks.onKillCredited(server, killer, storage, slot);
        }

        storage.removeLiveDragon(dead.getUUID());
        ShadowDragonBossManager.removePip(slot);
        LoggingHandler.sulog.info("[wishtracking] shadow dragon slot {} killed by {}", slot,
                killer != null ? killer.getUUID() : "the environment");

        if (!storage.hasLiveDragons())
            ShadowDragonBossManager.endEncounter(server, storage, "&aThe shadow dragons have all been vanquished.");
    }

    /**
     * Participation tracking. On every processed hit against a live shadow dragon, accumulate the damage in memory so
     * tier 2 (the per-slot sub-race) can find the top-damage contributor at death, not just the killer. Modelled on
     * the raid addon's {@code ForgeEventHandler.onLivingDamage}: {@code getSource().getEntity()} resolves the owner
     * for projectiles and ki blasts, so ranged damage credits the shooter rather than the projectile. Nothing is
     * written to NBT per hit; the totals are read once at death.
     */
    @SubscribeEvent
    public void onLivingDamage(LivingDamageEvent event)
    {
        if (!net.shurui.shuruisutilities.core.SUConfig.wishTrackingEnabled)
            return;
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide())
            return;
        MinecraftServer server = target.getServer();
        if (server == null)
            return;
        ShadowDragonStorage storage = ShadowDragonStorage.get(server);
        if (!storage.isLiveDragon(target.getUUID()))
            return; // not one of ours
        if (event.getSource().getEntity() instanceof Player player)
            ShadowDragonDamageTracker.record(target.getUUID(), player.getUUID(), event.getAmount());
    }

    /**
     * Seed the client's defiled-balls flag on login so the Earth radar's dial is correct from the first frame. Sent
     * unconditionally (not gated on the wish-tracking master switch) so the client always reflects the real armed state;
     * when tracking is off the flag is simply false. The client cache fails closed until this arrives.
     */
    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer sp)
            DefiledBallsSync.sendTo(sp);
    }

    /**
     * A dimension change swaps the client-side level and can drop transient client state, so re-push the defiled flag so
     * the Earth radar keeps the right dial in the new dimension.
     */
    @SubscribeEvent
    public void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer sp)
            DefiledBallsSync.sendTo(sp);
    }

    /**
     * Re-register the compass pip for every still-living dragon after a restart (sdu's markers are transient), and
     * drop any that no longer exist. Delegates to the manager, which owns the storage logic.
     */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event)
    {
        if (!net.shurui.shuruisutilities.core.SUConfig.wishTrackingEnabled)
            return;
        ShadowDragonBossManager.restoreOnStart(event.getServer());
    }
}
