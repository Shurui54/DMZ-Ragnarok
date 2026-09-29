package net.shurui.dev.shuruis_dmz_tournaments.event;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.shuruis_dmz_tournaments.Config;
import net.shurui.dev.shuruis_dmz_tournaments.data.TournamentData;
import net.shurui.dev.shuruis_dmz_tournaments.region.Region;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentDef;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentInstance;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentManager;

/**
 * Server-side rule enforcement: drives the manager tick, blocks item use for contestants when items are
 * disallowed, prevents lethal blows during a match, and disables PvP in the waiting area and stands.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_tournaments")
public final class ForgeEventHandler {

    private static boolean signupWaypointsRegistered;

    @SubscribeEvent
    public static void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        net.shurui.dev.shuruis_dmz_tournaments.command.TournamentCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        net.shurui.dev.shuruis_dmz_tournaments.KeyGate.logStatusOnce();
        TournamentManager.init(event.getServer());
        // carry tournament DEFINITIONS (and their grounds) to sibling servers so an editor change is live
        // everywhere without a restart. Self-gated per module; live brackets and per-server scheduling stay local.
        net.shurui.dev.shuruis_dmz_tournaments.data.TournamentStateSync.register();
        // list an open sign-up on the quest tracker. Registered once for the JVM, not per server start: a provider
        // is never unregistered, so a second start (world reload) would list it twice.
        if (!signupWaypointsRegistered) {
            signupWaypointsRegistered = true;
            net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentSignupWaypoints.register();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // swap every online tournament-character player back to their real character BEFORE the manager state is
        // dropped, so their save holds the real one. The persistent marker is the fallback for offline players or
        // a hard crash where this never runs (recovered on next login).
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.isActive(player)) {
                net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.exit(player);
            }
        }
        TournamentManager.clear();
        net.shurui.dev.shuruis_dmz_tournaments.entity.TournamentNpcs.clearRegistry();
    }

    /** Track a sign-up host as it enters a level (initial spawn, chunk reload, restart). */
    @SubscribeEvent
    public static void onHostJoinLevel(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide) return;
        net.shurui.dev.shuruis_dmz_tournaments.entity.TournamentNpcs.register(event.getEntity());
    }

    /** Stop tracking a host as it leaves a level (discard, chunk unload). */
    @SubscribeEvent
    public static void onHostLeaveLevel(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide) return;
        net.shurui.dev.shuruis_dmz_tournaments.entity.TournamentNpcs.unregister(event.getEntity());
    }

    /** Safety: if a stashed player disconnects, hand their items back before their data is saved. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // swap a tournament-character player back to their real character BEFORE the shard vault captures this
            // logout. ShardSync.onLogout captures at NORMAL priority, so HIGH here guarantees the fighter is gone
            // first: otherwise the vault stores the fighter as the active character and applies it on the next login,
            // which reads as a stat/level rollback (tickets 869 and 836). exit is a no-op when the player holds no
            // tournament character, and the character swap and the inventory stash are mutually exclusive.
            net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.exit(player);
            net.shurui.dev.shuruis_dmz_tournaments.inventory.InventoryVault.restore(player);
        }
    }

    /** Crash recovery + title display on join. */
    @SubscribeEvent
    public static void onLogin(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TournamentManager manager = TournamentManager.get();
            boolean inTournament = manager != null && manager.isInAnyTournament(player.getUUID());
            if (!inTournament && net.shurui.dev.shuruis_dmz_tournaments.inventory.InventoryVault.hasStash(player)) {
                net.shurui.dev.shuruis_dmz_tournaments.inventory.InventoryVault.restore(player);
            }
            // stranding recovery: a player swapped into their tournament character across a disconnect or restart is
            // put back on their real one, unless they are in a LIVE match (reconnected mid-run). Gated on an actual
            // running bout via activeFighterInstance, NOT isInAnyTournament: a stale sign-up that outlives its match
            // would otherwise keep the player frozen on the fighter for as long as the sign-up sat open (869, 836).
            boolean inLiveMatch = manager != null && manager.isActiveFighter(player.getUUID());
            if (!inLiveMatch && net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.isActive(player)) {
                net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.exit(player);
            }
            // an UNFINISHED creation sandbox is recovered even when the player is signed up for something, because
            // the sandbox is never a live match: beginCreation refuses while active and enter refuses while active,
            // so the two states cannot overlap. A pending sign-up used to hold the "in a tournament" flag true and
            // skip the recovery above for as long as that tournament sat open, which left the player frozen on a
            // blank fighter with every TP source cancelled (tickets 811 and 812, 2026-09-15).
            if (net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.isCreating(player)) {
                net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.exit(player);
            }
            // reconnecting mid-run still holds a tournament character: re-seat them in the fighter set so the
            // flat-multiplier mixin keeps applying, then push the current set to this fresh client so its HUD matches
            if (net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.isActive(player)) {
                net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.markFighter(player, true);
            }
            net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet.sendFightersTo(player);
            // Push the server's recognised title ids so the editor's title dropdowns reflect the server and not this
            // client's own (possibly stale) common config. See TitleListSyncPacket / TitleListClient.
            net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet.sendTitlesTo(player);
            if (player.getServer() != null) {
                net.shurui.dev.shuruis_dmz_tournaments.reward.TitleDisplay.apply(player.getServer(), player);
            }
            // a player routed here to enter a tournament on this world is signed up on arrival. Runs after the
            // vault payload (HIGHEST) so the pending marker it carried is readable.
            net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentNetwork.onArrival(player);
        }
    }

    /** Title chat prefix (used only when the above-head team isn't already decorating chat). */
    @SubscribeEvent
    public static void onServerChat(net.minecraftforge.event.ServerChatEvent event) {
        if (!Config.TITLE_CHAT_PREFIX.get() || Config.TITLE_NAMETAG_PREFIX.get()) return;
        ServerPlayer player = event.getPlayer();
        MinecraftServer server = player.getServer();
        if (server == null) return;
        String titleId = net.shurui.dev.shuruis_dmz_tournaments.reward.TitleManager.heldTitle(server, player.getUUID());
        if (titleId == null) return;
        String prefix = net.shurui.dev.shuruis_dmz_tournaments.reward.TitleManager.prefix(titleId);
        if (prefix == null || prefix.isEmpty()) return;
        net.minecraft.network.chat.Component full = net.minecraft.network.chat.Component.empty()
                .append(net.shurui.dev.shuruis_dmz_tournaments.util.TextUtil.color(prefix))
                .append(player.getName())
                .append(net.minecraft.network.chat.Component.literal(": "))
                .append(event.getMessage());
        event.setCanceled(true);
        server.getPlayerList().broadcastSystemMessage(full, false);
    }

    /**
     * Right-clicking a tournament host opens its GUI: a browser NPC ({@code SdtBrowse} tag) lists its group; a
     * legacy bound NPC ({@code SdtTournament} tag) opens that one sign-up screen.
     */
    @SubscribeEvent
    public static void onEntityInteract(net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        var target = event.getTarget();
        String browseGroup = net.shurui.dev.shuruis_dmz_tournaments.entity.TournamentNpcs.browseGroupOf(target);
        String tournamentId = net.shurui.dev.shuruis_dmz_tournaments.entity.TournamentNpcs.tournamentOf(target);
        if (browseGroup == null && tournamentId == null) return;
        if (event.getEntity() instanceof ServerPlayer player) {
            if (browseGroup != null) {
                net.shurui.dev.shuruis_dmz_tournaments.entity.TournamentNpcs.openBrowser(player, browseGroup);
            } else {
                net.shurui.dev.shuruis_dmz_tournaments.entity.TournamentNpcs.openSignup(player, tournamentId);
            }
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.CONSUME);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        TournamentManager manager = TournamentManager.get();
        if (manager != null) manager.tick();
        MinecraftServer server = event.getServer();
        if (server != null) {
            net.shurui.dev.shuruis_dmz_tournaments.entity.TournamentNpcs.tickLook(server);
            // advance any players in the full-creation sandbox once DMZ reports their character created
            net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.tickCreations(server);
        }
    }

    /** Block item use for active fighters when their tournament disallows items. */
    @SubscribeEvent
    public static void onUseItem(LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TournamentManager manager = TournamentManager.get();
            if (manager == null) return;
            TournamentInstance inst = manager.activeFighterInstance(player.getUUID());
            if (inst != null && inst.definition() != null && !inst.definition().itemsAllowed) {
                event.setCanceled(true);
            }
        }
    }

    /** Prevent lethal damage during a match: convert a would-be kill into a knockout the manager reads. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer target)) return;
        MinecraftServer server = target.getServer();
        if (server == null) return;
        TournamentManager manager = TournamentManager.get();

        // PvP suppression in the non-combat zones
        Player attacker = directPlayerAttacker(event);
        if (attacker != null && attacker != target) {
            // teammates can't damage each other in a team bout
            if (manager != null && manager.sameActiveTeam(attacker.getUUID(), target.getUUID())) {
                event.setCanceled(true);
                return;
            }
            for (TournamentDef def : TournamentData.get(server).allDefs().values()) {
                if (inRegion(def.waiting, target) && !def.pvpInWaiting) {
                    event.setCanceled(true);
                    return;
                }
                if (inRegion(def.stands, target) && !def.pvpInStands) {
                    event.setCanceled(true);
                    return;
                }
            }
        }

        // in an active match, cap damage so nobody dies; the manager decides the loser
        if (manager != null) {
            TournamentInstance inst = manager.activeFighterInstance(target.getUUID());
            if (inst != null) {
                // forced-transformation invulnerability window: drop the hit entirely
                if (inst.isInIframe(target.getUUID(), target.level().getGameTime())) {
                    event.setCanceled(true);
                    return;
                }
                float remaining = target.getHealth() - event.getAmount();
                if (remaining < 1.0f) {
                    remaining = 1.0f;
                    event.setAmount(Math.max(0f, target.getHealth() - 1.0f));
                }
                // half-health forced transform (fires once per bout, opens the iframe window)
                inst.checkForcedTransform(target, remaining);
            }
        }
    }

    /**
     * Freeze training-point gain for a player in a tournament character: cancels the DragonMineZ
     * {@code TPGainEvent} for all earned TP. Dynamic growth is frozen separately by the template's growth flags.
     * Admin {@code /points} uses {@code setTrainingPoints} and bypasses this, which is fine.
     */
    @SubscribeEvent
    public static void onTpGain(com.dragonminez.common.events.DMZEvent.TPGainEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player
                && net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.isActive(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        TournamentManager manager = TournamentManager.get();
        if (manager != null && manager.isActiveFighter(player.getUUID())) {
            // safety net: never let a contestant die mid-match
            event.setCanceled(true);
            player.setHealth(1.0f);
        }
    }

    private static Player directPlayerAttacker(LivingHurtEvent event) {
        if (event.getSource().getEntity() instanceof Player p) return p;
        return null;
    }

    private static boolean inRegion(Region region, ServerPlayer player) {
        return region != null && region.contains(player);
    }
}
