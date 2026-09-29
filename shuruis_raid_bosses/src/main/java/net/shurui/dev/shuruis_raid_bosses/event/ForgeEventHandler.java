package net.shurui.dev.shuruis_raid_bosses.event;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.shuruis_raid_bosses.data.RaidData;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidDamageTracker;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidInstance;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidManager;
import net.shurui.dev.shuruis_raid_bosses.region.Region;

/**
 * Server-side rule enforcement: registers commands, drives the manager tick, opens the sign-up GUI on NPC
 * interaction, disables arena PvP, feeds the {@link RaidDamageTracker} on every hit to a boss, and triggers
 * rewards on a boss's death.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_raids")
public final class ForgeEventHandler {

    @SubscribeEvent
    public static void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        net.shurui.dev.shuruis_raid_bosses.command.RaidCommand.register(event.getDispatcher());
        // The private /rg rift subtree is registered by the Ragnarok Key (absent keyless, where it was unreachable).
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        net.shurui.dev.shuruis_raid_bosses.KeyGate.logStatusOnce();
        RaidManager.init(event.getServer());
        // Rifts are private: the key brings its rift manager up here; keyless this does nothing.
        net.shurui.dev.shuruis_raid_bosses.api.key.RaidKeyHooks.get().serverStarted(event.getServer());
        // Carry raid and rift DEFINITIONS to sibling servers so an editor change is live everywhere without
        // a restart. Per-server scheduling and arena cells stay local.
        net.shurui.dev.shuruis_raid_bosses.data.RaidStateSync.register();
        // Waypoint providers register once per JVM, not per server start: a provider is never unregistered,
        // so a second start (world reload) would list every raid/tear twice.
        if (!raidSignupWaypointsRegistered) {
            raidSignupWaypointsRegistered = true;
            net.shurui.dev.shuruis_raid_bosses.raid.RaidSignupWaypoints.register();
            net.shurui.dev.shuruis_raid_bosses.api.key.RaidKeyHooks.get().registerRiftWaypoints();
        }
    }

    private static boolean raidSignupWaypointsRegistered;

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // Rifts first: closing a run cancels its fight, which needs the raid manager still standing.
        net.shurui.dev.shuruis_raid_bosses.api.key.RaidKeyHooks.get().serverStopping();
        RaidManager.clear();
        net.shurui.dev.shuruis_raid_bosses.entity.RaidNpcs.clearRegistry();
    }

    /** Track a sign-up host as it enters a level (initial spawn, chunk reload, restart). */
    @SubscribeEvent
    public static void onHostJoinLevel(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide) return;
        net.shurui.dev.shuruis_raid_bosses.entity.RaidNpcs.register(event.getEntity());
    }

    /** Stop tracking a host as it leaves a level (discard, chunk unload). */
    @SubscribeEvent
    public static void onHostLeaveLevel(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide) return;
        net.shurui.dev.shuruis_raid_bosses.entity.RaidNpcs.unregister(event.getEntity());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        RaidManager manager = RaidManager.get();
        if (manager != null) manager.tick();
        // After the raids: a rift run reads the state its private raid instance settled this same tick, so
        // ticking it first would always see the fight one tick stale.
        MinecraftServer server = event.getServer();
        net.shurui.dev.shuruis_raid_bosses.api.key.RaidKeyHooks.get().tickRifts(server);
        if (server != null) {
            net.shurui.dev.shuruis_raid_bosses.entity.RaidNpcs.tickLook(server);
            net.shurui.dev.shuruis_raid_bosses.dmz.ZSoulManager.tick(server);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            net.shurui.dev.shuruis_raid_bosses.dmz.ZSoulManager.onLogout(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            net.shurui.dev.shuruis_raid_bosses.dmz.ZSoulManager.applySlotCount(sp);
            // Drop a player routed here to join a raid into it on arrival. Runs after the vault payload
            // (applied at HIGHEST) so the pending marker it carried is readable.
            net.shurui.dev.shuruis_raid_bosses.raid.RaidNetwork.onArrival(sp);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            net.shurui.dev.shuruis_raid_bosses.dmz.ZSoulManager.applySlotCount(sp);
        }
    }

    /**
     * Right-clicking any entity tagged as a raid host: holding a Raid Soul immediately starts that soul's
     * raid (and consumes it); otherwise it opens the raid browser listing every joinable raid by category.
     */
    @SubscribeEvent
    public static void onEntityInteract(net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        String hostTag = net.shurui.dev.shuruis_raid_bosses.entity.RaidNpcs.raidOf(event.getTarget());
        if (hostTag == null) return; // not a raid host
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        net.minecraft.world.item.ItemStack held = player.getItemInHand(event.getHand());
        String soulRaid = net.shurui.dev.shuruis_raid_bosses.item.RaidSoulItem.raidOf(held);
        if (soulRaid != null) {
            handleSoulUse(player, soulRaid, held);
        } else {
            net.shurui.dev.shuruis_raid_bosses.entity.RaidNpcs.openBrowser(player);
        }
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.CONSUME);
    }

    private static void handleSoulUse(ServerPlayer player, String raidId, net.minecraft.world.item.ItemStack soul) {
        RaidBossDef def = RaidData.get(player.getServer()).getDef(raidId);
        if (def == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "soul.dmz_ragnarok.raid.raid_gone"));
            return;
        }
        RaidManager manager = RaidManager.get();
        if (manager != null && manager.startImmediate(def.id, player)) {
            soul.shrink(1);
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "soul.dmz_ragnarok.raid.ignites",
                    net.shurui.dev.shuruis_raid_bosses.util.TextUtil.color(def.name)));
        } else {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "soul.dmz_ragnarok.raid.cannot_start",
                    net.shurui.dev.shuruis_raid_bosses.util.TextUtil.color(def.name)));
        }
    }

    /**
     * DMZ saga bosses transform instead of dying: a new form entity spawns and the old one is discarded. The
     * raid tracks by UUID, so that discard reads as the boss vanishing and ends the raid early (the "boss
     * dies as soon as it spawns" bug) while the new form roams untracked. Catch the new form joining and hand
     * it to the raid whose arena it appeared in. The original boss is registered before spawn, so its own
     * join is ignored.
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof com.dragonminez.common.init.entities.sagas.DBSagasEntity boss)) return;
        if (RaidDamageTracker.isBoss(boss.getUUID())) return; // already tracked (initial spawn or prior adoption)
        RaidManager manager = RaidManager.get();
        if (manager != null) manager.tryAdoptTransformedBoss(boss);
    }

    /**
     * Friendly fire between a player and a raid ALLY (an NPC on the players' side), both directions. Ally
     * hitting player is the AI slipping its leash for a tick before
     * {@link net.shurui.dev.shuruis_raid_bosses.raid.RaidInstance#tickAllies} re-points it; player hitting
     * ally is a stray area attack that would otherwise kill the partner they were given.
     *
     * <p>Side is read from the entity tag ({@code RaidInstance.ALLY_TAG}), not the raid instance, since this
     * handler has only the two entities. HIGHEST priority and a plain cancel, so nothing downstream sees it.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAllyFriendlyFire(LivingHurtEvent event) {
        net.minecraft.world.entity.Entity victim = event.getEntity();
        net.minecraft.world.entity.Entity attacker = event.getSource().getEntity();
        if (victim == null || attacker == null || victim.level().isClientSide) return;
        boolean victimIsAlly = victim.getPersistentData()
                .getBoolean(net.shurui.dev.shuruis_raid_bosses.raid.RaidInstance.ALLY_TAG);
        boolean attackerIsAlly = attacker.getPersistentData()
                .getBoolean(net.shurui.dev.shuruis_raid_bosses.raid.RaidInstance.ALLY_TAG);
        if (victimIsAlly && attacker instanceof Player) {
            // The target carries the raid ally tag, so participant friendly fire is refused.
            event.setCanceled(true);
            return;
        }
        if (attackerIsAlly && victim instanceof Player) {
            event.setCanceled(true);
            return;
        }
        // Ally on ally: a side swap turned two enemies into partners mid-swing.
        if (victimIsAlly && attackerIsAlly) {
            event.setCanceled(true);
        }
    }

    /** Disable PvP inside any raid arena so allies can't hit each other during a fight. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer target)) return;
        MinecraftServer server = target.getServer();
        if (server == null) return;
        if (!(event.getSource().getEntity() instanceof Player attacker) || attacker == target) return;
        for (RaidBossDef def : RaidData.get(server).allDefs().values()) {
            if (!def.pvpInArena && inRegion(def.arena, target)) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /**
     * DEATH-SAVE hook. If a raid boss is about to land a lethal blow on a participant, cancel the hit and
     * teleport them back to sign-up, fully healed.
     *
     * <p>DMZ runs a two-phase combat pipeline on this event chain: {@code onLivingHurt} (HIGH) stashes the
     * RAW pre-resistance damage under the victim's {@code dmz_raw_damage} tag and setAmount(raw); later
     * {@code overrideVanillaArmorReduction} (LOWEST) applies resistance and setAmount(final). So a HIGHEST
     * read of {@code getAmount()} sees the RAW number and would falsely rescue players whose max HP sits
     * near the boss's raw stat.
     *
     * <p>Must compare against POST-mitigation damage. Run at LOWEST, but cross-mod same-priority ordering is
     * not guaranteed, so do NOT trust {@code getAmount()} alone: recompute DMZ's base post-mitigation value
     * via {@link com.dragonminez.common.stats.StatsData#calculatePostMitigationDamage(double, boolean, double)}
     * and take the MIN of that and the live amount. DMZ's remaining stages only reduce further, so the MIN
     * never over-estimates and never triggers a false rescue. Read is side-effect-free (the tags stay DMZ's).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onLethalBossHit(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer target)) return;
        if (!(event.getSource().getEntity() instanceof LivingEntity source)) return;
        if (!RaidDamageTracker.isBoss(source.getUUID())) return;
        if (postMitigationDamage(target, event.getAmount()) < target.getHealth()) return; // survivable hit
        RaidManager manager = RaidManager.get();
        if (manager == null) return;
        RaidInstance inst = manager.instance(RaidDamageTracker.defOf(source.getUUID()));
        if (inst != null && inst.rescueFromLethal(target)) {
            event.setCanceled(true);
        }
    }

    /**
     * Lowest estimate of the damage {@code target} takes after DMZ resistance. MIN of DMZ's recomputed base
     * post-mitigation value (when the raw tag is present) and the live event amount, so it is correct
     * whether or not DMZ's LOWEST handler has run. Read-only. Falls back to {@code eventAmount} with no DMZ
     * character.
     */
    private static float postMitigationDamage(ServerPlayer target, float eventAmount) {
        var statsOpt = net.shurui.dev.shuruis_raid_bosses.dmz.DmzHooks.stats(target);
        if (statsOpt.isEmpty()) return eventAmount;
        net.minecraft.nbt.CompoundTag pdata = target.getPersistentData();
        if (!pdata.contains("dmz_raw_damage")) return eventAmount; // DMZ already consumed it; event is authoritative
        com.dragonminez.common.stats.StatsData stats = statsOpt.get();
        double raw = pdata.getDouble("dmz_raw_damage");
        double defensePen = pdata.contains("dmz_defense_pen") ? pdata.getDouble("dmz_defense_pen") : 0.0;
        boolean guardBroken = stats.getStatus().isStunEffect() && stats.getResources().getCurrentPoise() <= 0f;
        double dmzBase = stats.calculatePostMitigationDamage(raw, guardBroken, defensePen);
        if (!Double.isFinite(dmzBase) || dmzBase < 0) dmzBase = 0;
        return (float) Math.min(dmzBase, eventAmount);
    }

    /**
     * PER-HIT CEILING. Holds a single hit on a raid boss to {@link RaidBossDef#maxDamagePercentPerHit} of the boss's
     * max health, so no one fighter can delete the encounter in one punch. Nothing about the hitter is changed: their
     * stats, their damage everywhere else and their own ceiling-free hits on anything that is not a raid boss all
     * stand. -1 from {@code maxDamagePerHit} means this raid opted out.
     *
     * <p>Runs at LOWEST so it sees the amount AFTER every mitigation stage (DMZ's resistance and the suite's own
     * {@code dmz_npc_defense} curve both land at LivingDamage/LOWEST). Same-priority ordering between mods is not
     * guaranteed, but it does not need to be here: mitigation only ever REDUCES, so whichever of the two runs first,
     * the damage that lands is at most this ceiling.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onBossHitCeiling(LivingDamageEvent event) {
        double cap = bossPerHitCap(event.getEntity());
        if (cap >= 0 && event.getAmount() > cap) {
            event.setAmount((float) cap);
        }
    }

    /**
     * DAMAGE TRACKER hook. If the target is a registered raid boss and the source is (or is owned by) a
     * player, accumulate the damage in memory: no NBT writes per hit.
     */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide) return;
        if (!RaidDamageTracker.isBoss(target.getUUID())) return;
        // getEntity() resolves to the owner for projectiles/ki blasts, so ranged damage is credited too.
        if (event.getSource().getEntity() instanceof Player player) {
            // Credit what the boss will actually take, not the swing. This handler runs before onBossHitCeiling
            // (DEFAULT beats LOWEST), so without the same ceiling the leaderboard would still hand MVP to the one
            // fighter whose hits are being held back, which is the opposite of what the ceiling is for.
            double cap = bossPerHitCap(target);
            float amount = cap >= 0 ? (float) Math.min(event.getAmount(), cap) : event.getAmount();
            RaidDamageTracker.record(target.getUUID(), player.getUUID(), amount);
        }
    }

    /**
     * The per-hit damage ceiling in force for {@code target}, or -1 when there is none: not a boss, no live raid, or
     * a raid whose ceiling is switched off. Cheap enough for the damage path (two map lookups, no allocation).
     */
    private static double bossPerHitCap(LivingEntity target) {
        if (target == null || target.level().isClientSide) return -1;
        if (!RaidDamageTracker.isBoss(target.getUUID())) return -1;
        RaidManager manager = RaidManager.get();
        if (manager == null) return -1;
        RaidInstance inst = manager.instance(RaidDamageTracker.defOf(target.getUUID()));
        if (inst == null) return -1;
        RaidBossDef def = inst.definition();
        return def == null ? -1 : def.maxDamagePerHit(target);
    }

    /** Boolean persistent tag: suppress the enemy's own loot-table drops (raid rewards come from the reward table). */
    public static final String NO_VANILLA_DROPS_TAG = "srb_novanilla";

    /** Raid enemies drop nothing of their own unless the raid's Vanilla Drops toggle is on. */
    @SubscribeEvent
    public static void onLivingDrops(net.minecraftforge.event.entity.living.LivingDropsEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (event.getEntity().getPersistentData().getBoolean(NO_VANILLA_DROPS_TAG)) {
            event.setCanceled(true);
        }
    }

    /** When a tracked enemy dies, credit the kill, hand out rewards when the raid resolves, and clear it. */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide) return;
        // A participant who actually dies (death-save missed: void, /kill, non-boss damage) is out of the
        // raid; the instance keeps them out of the arena until it ends.
        if (dead instanceof ServerPlayer deadPlayer) {
            RaidManager mgr = RaidManager.get();
            if (mgr != null) mgr.onPlayerDeath(deadPlayer);
            return;
        }
        if (!RaidDamageTracker.isBoss(dead.getUUID())) {
            // Late-adoption-on-death: a DMZ saga boss can do its FINAL transform with NO LivingDeathEvent and
            // end up untracked, so the player kills an untracked form that reaches here as a non-boss. If it
            // dies in an ACTIVE raid's arena while a tracked boss just vanished, credit it as that raid's
            // stage death so victory fires instead of a false timeout. The manager gates on the vanish-record
            // fingerprint, so an unrelated saga boss dying nearby cannot falsely win.
            if (dead instanceof com.dragonminez.common.init.entities.sagas.DBSagasEntity) {
                RaidManager lateMgr = RaidManager.get();
                if (lateMgr != null) lateMgr.creditUntrackedBossKill(dead);
            }
            return;
        }
        // Log what killed a tracked boss, for diagnosing "boss died as soon as it spawned" (environmental
        // source like inWall/outOfWorld vs. an actual player).
        org.slf4j.LoggerFactory.getLogger("shuruis_raid_bosses").debug(
                "[raid] tracked boss {} died at {}, source='{}', attacker={}",
                dead.getType().getDescriptionId(), dead.blockPosition(),
                event.getSource().getMsgId(),
                event.getSource().getEntity() == null ? "none" : event.getSource().getEntity().getName().getString());
        RaidManager manager = RaidManager.get();
        if (manager == null) {
            RaidDamageTracker.clear(dead.getUUID());
            return;
        }
        // Resolve the killer (owner for projectiles/ki blasts) so parallel-quest kills are credited.
        java.util.UUID killer = event.getSource().getEntity() instanceof Player p ? p.getUUID() : null;
        manager.onEntityDeath(dead.getUUID(), killer);
    }

    private static boolean inRegion(Region region, ServerPlayer player) {
        return region != null && region.contains(player);
    }
}
