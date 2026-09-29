package net.shurui.dev.shuruis_dmz_dungeons.compat;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.permission.PermissionAPI;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonBossUnlocks;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonCooldowns;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonDimensions;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloorConfig;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloorLayout;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloorManager;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloors;
import net.shurui.dev.shuruis_dmz_dungeons.event.DungeonTimeEvents;
import net.shurui.shuruisutilities.api.key.TeleportHooks;
import net.shurui.shuruisutilities.teleport.portal.PortalTargetResolver;
import net.shurui.shuruisutilities.teleport.portal.PortalTargets;
import net.shurui.shuruisutilities.util.NamedWorldPoint;
import net.shurui.shuruisutilities.util.events.entity.EntityPortalEvent;

// optional integration with Shurui's Utilities. the ONLY dungeons class that imports an SU type, classloaded and
// registered only behind ModList.get().isLoaded("shuruisutilities"). SU absent -> nothing here loads.
//
// two jobs, both on SU's EntityPortalEvent (posted BEFORE PortalManager runs its own teleport):
//
//   1. COOLDOWN VETO. veto a teleport into any dungeon dim while the player is on re-entry cooldown. otherwise the
//      teleport runs, then DungeonTimeEvents.onChangedDimension bounces them with a SECOND dim transfer in the same
//      tick -> client hangs on a black loading screen. cancelling here stops the teleport before any dim change.
//
//   2. MANUAL FLOOR ROUTING. let an operator point an SU /portal at a procedural FLOOR. floor N lives at a fixed
//      column X = N * DungeonFloorLayout.CELL_SPACING in its theme's dimension (Z = 0), so the operator sets the
//      target to that themed dim with that X. A raw SU teleport would strand the player at bare coords in an
//      ungenerated dim, so on a valid floor we CANCEL it and route through DungeonFloorManager.teleportToFloor,
//      which owns generation, the key gate and the entrance landing.
//
// EntityPortalEvent: a @Cancelable EntityEvent posted on the FORGE bus by PortalManager.playerMove. fields used:
// targetDimension, targetPos, posFrom (entry BlockPos).
public final class UtilitiesPortalCompat {

    // player -> server tick of their last floor routing, for the re-trigger guard in the portal handler
    private static final java.util.Map<java.util.UUID, Long> LAST_ROUTE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long ROUTE_COOLDOWN_TICKS = 40L;

    private UtilitiesPortalCompat() {
    }

    // registered from the mod constructor, only when shuruisutilities is loaded
    public static void init() {
        MinecraftForge.EVENT_BUS.register(new UtilitiesPortalCompat());
        // let SU's /portal express floor targets symbolically ("floor N" / "nextfloor") instead of by raw X column;
        // resolved at travel time into a themed-dim coordinate onSuPortal already routes.
        // Registered in core's PortalTargets, not on the portal manager: that lives in the Ragnarok Key and may not be
        // installed yet (or at all) while this module constructs.
        PortalTargets.setSymbolicResolver(new FloorResolver());
        Shuruis_dmz_dungeons.LOGGER.info("[{}] Shurui's Utilities present; dungeon portal cooldown veto + floor "
                + "targeting active.", Shuruis_dmz_dungeons.MODID);
    }

    // resolves symbolic /portal floor targets into themed dungeon-dim coordinates. the ONE place the floor ->
    // theme-dim + X-column math lives on the SU side; the point flows back through onSuPortal, sharing the gate,
    // generation and landing. resolved late, so a theme change or renumber needs no re-targeting.
    public static final class FloorResolver implements PortalTargetResolver {

        @Override
        public Result resolveFloor(ServerPlayer player, int floor) {
            MinecraftServer server = player == null ? null : player.getServer();
            if (server == null) {
                return Result.fail(Status.FAILED);
            }
            DungeonFloors floors = DungeonFloors.get(server);
            if (floors == null || !floors.isValidFloor(floor)) {
                return Result.fail(Status.NO_SUCH_FLOOR);
            }
            DungeonFloorConfig config = floors.get(floor);
            ResourceKey<Level> themeKey = DungeonDimensions.levelForTheme(config.theme);
            // make sure the themed dim is a running level BEFORE handing back a point: SU's playerMove resolves the
            // destination level by id and posts EntityPortalEvent with it, so an unloaded dim would read back null.
            ServerLevel level = DungeonDimensions.getOrCreateLevel(server, themeKey);
            if (level == null) {
                return Result.fail(Status.FAILED);
            }
            int x = DungeonFloorLayout.clampedIndex(floor) * DungeonFloorLayout.CELL_SPACING;
            NamedWorldPoint point = new NamedWorldPoint(themeKey.location().toString(), x,
                    DungeonFloorLayout.SURFACE_Y, 0);
            return Result.ok(point);
        }

        @Override
        public Result resolveNextFloor(ServerPlayer player) {
            if (player == null) {
                return Result.fail(Status.NOT_IN_DUNGEON);
            }
            Level level = player.level();
            // "the floor the portal is in": only a themed floor dim carries floors (the legacy superflat hub has none).
            if (!DungeonDimensions.isAnyDungeon(level) || DungeonDimensions.isDungeon(level)) {
                return Result.fail(Status.NOT_IN_DUNGEON);
            }
            MinecraftServer server = player.getServer();
            DungeonFloors floors = server == null ? null : DungeonFloors.get(server);
            int current = DungeonFloorLayout.floorNumberForPos(
                    player.blockPosition().getX(), player.blockPosition().getZ());
            if (floors == null || current < 1 || !floors.isValidFloor(current)) {
                return Result.fail(Status.NOT_IN_DUNGEON);
            }
            int next = current + 1;
            // LAST FLOOR: refuse. there is no defined exit (exit portals are placed manually) and wrapping to floor 1
            // would send backwards, so a next-floor portal on the final floor reports nowhere further and moves no one.
            if (!floors.isValidFloor(next)) {
                return Result.fail(Status.LAST_FLOOR);
            }
            return resolveFloor(player, next);
        }

        @Override
        public boolean floorExists(ServerPlayer player, int floor) {
            MinecraftServer server = player == null ? null : player.getServer();
            DungeonFloors floors = server == null ? null : DungeonFloors.get(server);
            return floors != null && floors.isValidFloor(floor);
        }
    }

    // HIGH so we decide before any other SU-portal listeners; cancelling here stops PortalManager's doTeleport.
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onSuPortal(EntityPortalEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        // BOSS PORTAL. An operator flags a portal in a boss arena (/rg portal bosslock) so it stays shut while that
        // floor's guardian lives and opens once it is beaten. Checked on the FROM side (where the player stands), so it
        // holds for ANY target kind (nextfloor, spawn, coord), including an arena exit that leads back to the overworld.
        // The floor's bossDefeated flag is persisted and cross-shard synced, so the boss, which lives on the owning
        // shard, opening this portal reaches every shard. A player who personally cleared this floor's guardian (their
        // own carried record) also passes, so a re-locked / respawned boss on this shard cannot re-seal their exit.
        if (bossLocked(server, player, event)) {
            event.setCanceled(true);
            player.displayClientMessage(Component.translatable("message.dmz_ragnarok.dungeons.portal_boss_locked")
                    .withStyle(net.minecraft.ChatFormatting.RED), true);
            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();
            server.execute(() -> shoveBack(player, event, px, py, pz));
            return;
        }

        // only intercept teleports whose destination is a dungeon dim (legacy superflat or a themed floor dim)
        if (event.targetDimension == null || !DungeonDimensions.isAnyDungeon(event.targetDimension)) {
            return;
        }

        // does this portal aim at a procedural floor (a themed floor dim + an X column mapping to a valid floor)?
        int floor = resolveFloorTarget(server, event);

        // COOLDOWN applies to every dungeon-dim entry (floor or not) for non-bypass players; ops/bypass enter freely.
        if (!canBypass(player)) {
            long now = System.currentTimeMillis();
            DungeonCooldowns cooldowns = DungeonCooldowns.get(server);
            if (cooldowns.onCooldown(player.getUUID(), now)) {
                // on cooldown: veto the teleport so no dimension change begins, then deny + shove the player back
                event.setCanceled(true);
                long secs = cooldowns.remainingSeconds(player.getUUID(), now);
                player.displayClientMessage(Component.translatable(
                        "message.dmz_ragnarok.dungeons.cooldown_portal",
                        net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonTimeFormat.human(secs)), true);
                double px = player.getX();
                double py = player.getY();
                double pz = player.getZ();
                // defer the knockback to the server thread so the cancel settles before we move them
                server.execute(() -> shoveBack(player, event, px, py, pz));
                return;
            }
        }

        // off cooldown (or bypass): a floor-target portal must NOT run the raw SU teleport (bare coords in an
        // ungenerated dim). Cancel and route through the gated floor manager (generation, key gate, landing).
        if (floor > 0) {
            // TICKET GATE FIRST, then BOSS GATE. This order is MESSAGE-ONLY: both gates still refuse independently
            // (each cancels and returns), so reordering never changes who gets through, only which refusal a player
            // who fails more than one sees first. A ticket-gated portal exists precisely to gate direct transport on
            // the ticket, so for the player standing in it the ticket refusal is the actionable one. firstUnclearedBossBefore
            // scans every floor below the target, so with the boss gate first a ticket-portal visitor who merely has an
            // uncleared boss somewhere below was told to beat that boss instead of to use the ticket. The boss gate
            // still refuses them immediately afterwards if they also have an uncleared boss below.
            //
            // TICKET GATE. refuse until the player has REDEEMED this floor's ticket. Either switch seals it: the
            // floor's requireTicket (/rg dungeon floor requireticket) or this portal's (/rg portal requireticket).
            // Ops / bypass skip it. Refusal SHOVES like the cooldown one: playerMove fires only on crossing in, so
            // without the shove the player stands in the area getting one message then silence.
            if (!canBypass(player) && ticketLocked(server, event, floor)
                    && !UtilitiesTicketCompat.hasUnlockedFloor(player, floor)) {
                event.setCanceled(true);
                player.displayClientMessage(Component.translatable(
                        "message.dmz_ragnarok.dungeons.portal_ticket_locked", floor)
                        .withStyle(net.minecraft.ChatFormatting.RED), true);
                double px = player.getX();
                double py = player.getY();
                double pz = player.getZ();
                server.execute(() -> shoveBack(player, event, px, py, pz));
                return;
            }
            // BOSS GATE. sequential progression: to enter floor N, every BOSS floor below N must be cleared. Enforced
            // HERE because portals are placed manually (ops / bypass skip it). Replaces the old descent pad.
            int lockedBy = canBypass(player) ? -1 : firstUnclearedBossBefore(server, player, floor);
            if (lockedBy > 0) {
                event.setCanceled(true);
                player.displayClientMessage(Component.translatable(
                        "message.dmz_ragnarok.dungeons.portal_floor_locked", lockedBy)
                        .withStyle(net.minecraft.ChatFormatting.RED), true);
                return;
            }
            // PERSONAL UNLOCK RECORD: this player just satisfied the boss gate to enter `floor`, so every in-rotation
            // boss floor below it was cleared (by this shard's flag or by their own prior record). Persist that in
            // their own carried data so a later portal on ANY shard opens for them even where that shard's boss still
            // stands. This is also the backfill: the first gate an existing player walks through after this update
            // captures their whole current progression at once. Bypass players (ops) are skipped: they overrode the
            // gate, they did not earn the clear.
            if (!canBypass(player)) {
                recordBossClearsBelow(server, player, floor);
            }
            event.setCanceled(true);
            // One routing per player per ROUTE_COOLDOWN_TICKS. On 2026-09-12 a player on ow2 re-triggered a floor 2
            // portal every two ticks for over a minute without changing dimension, and every pass ran the whole
            // floor entry (teleport, build check, guardian setup). The event stays cancelled either way.
            long now = server.getTickCount();
            Long lastRoute = LAST_ROUTE.get(player.getUUID());
            if (lastRoute != null && now - lastRoute < ROUTE_COOLDOWN_TICKS && now >= lastRoute) {
                return;
            }
            LAST_ROUTE.put(player.getUUID(), now);
            server.execute(() -> routeToFloor(player, floor));
        }
        // not a floor target (legacy hub dim, or a themed coord that is not a floor column): let SU teleport normally.
    }

    // whether the portal the player is crossing is a boss portal whose boss is still alive. Keyed on the entry
    // position (FROM), so it applies to any target kind. Fail-open: a portal with no boss to wait on (not a boss
    // floor, unknown floor, dungeons state missing) is treated as OPEN rather than stuck shut.
    private static boolean bossLocked(MinecraftServer server, ServerPlayer player, EntityPortalEvent event) {
        if (event.worldFrom == null || event.posFrom == null) {
            return false;
        }
        net.shurui.shuruisutilities.teleport.portal.Portal portal;
        try {
            // through core's TeleportHooks: the portal manager lives in the Ragnarok Key, and keyless there are no SU
            // portals, so this reads null and the portal counts as open.
            portal = TeleportHooks.get().portalAt(
                    new net.shurui.shuruisutilities.commons.selections.WorldPoint(event.worldFrom, event.posFrom));
        } catch (Throwable t) {
            return false;
        }
        if (portal == null || !portal.isBossLock()) {
            return false;
        }
        // the boss this portal waits on is the guardian of the boss floor the portal SITS in (a themed floor dim at an
        // X column). The legacy superflat hub carries no floors, so a portal there never boss-locks.
        if (!DungeonDimensions.isAnyDungeon(event.worldFrom) || DungeonDimensions.isDungeon(event.worldFrom)) {
            return false;
        }
        int floor = DungeonFloorLayout.floorNumberForPos(event.posFrom.getX(), event.posFrom.getZ());
        DungeonFloors floors = DungeonFloors.get(server);
        if (floors == null || !floors.isValidFloor(floor)) {
            return false;
        }
        DungeonFloorConfig config = floors.get(floor);
        // an out-of-rotation floor never boss-locks: its guardian is bypassed, so a boss-lock portal left in its arena
        // must not stay sealed for good. Treated as open, consistent with firstUnclearedBossBefore skipping it.
        if (config == null || !config.isBoss() || !config.inRotation) {
            return false;
        }
        // OPEN if this shard's boss is down OR the player personally cleared this floor's guardian. The personal
        // clear is carried in the player's own data (DungeonBossUnlocks), so it holds across shards and survives a
        // re-locked or respawned boss here, which is exactly the "a portal they unlocked stops working" case.
        return !floors.isBossDefeated(floor) && !DungeonBossUnlocks.hasCleared(player, floor);
    }

    // the 1-based floor a target resolves to, or -1 if not a floor target. floor N sits at X = N * CELL_SPACING, so
    // read it back off the target X. only themed floor dims carry floors; the legacy hub is a normal /portal dest.
    private static int resolveFloorTarget(MinecraftServer server, EntityPortalEvent event) {
        if (event.targetPos == null || event.targetDimension == null
                || DungeonDimensions.isDungeon(event.targetDimension)) {
            return -1;
        }
        int floor = (int) Math.round(event.targetPos.getX() / (double) DungeonFloorLayout.CELL_SPACING);
        if (floor < 1) {
            return -1;
        }
        DungeonFloors floors = DungeonFloors.get(server);
        return (floors != null && floors.isValidFloor(floor)) ? floor : -1;
    }

    // the lowest IN-ROTATION BOSS floor below `floor` not yet cleared, or -1 if none blocks entry. an uncleared boss
    // floor seals every floor after it. An OUT-OF-ROTATION floor is skipped: staff take a floor out of rotation by
    // removing its portal so players bypass it, and its guardian then can never be reached, so counting it would seal
    // every floor above it for good ("the guardian of boss floor N still stands"). Skipping it makes the gate look
    // back to the previous ENABLED boss floor instead, which is also why a portal a player legitimately unlocked keeps
    // working after floors below it are pulled from rotation: a satisfied requirement can never turn unsatisfied.
    private static int firstUnclearedBossBefore(MinecraftServer server, ServerPlayer player, int floor) {
        DungeonFloors floors = DungeonFloors.get(server);
        if (floors == null) {
            return -1;
        }
        for (int i = 1; i < floor; i++) {
            DungeonFloorConfig c = floors.get(i);
            // a boss floor blocks only when BOTH this shard's flag is unset AND the player holds no personal clear.
            // The personal clear (DungeonBossUnlocks) is the player's own carried proof they beat this guardian, so
            // it satisfies the requirement on a shard where the boss is still up without touching that shard's state.
            if (c != null && c.isBoss() && c.inRotation && !floors.isBossDefeated(i)
                    && !DungeonBossUnlocks.hasCleared(player, i)) {
                return i;
            }
        }
        return -1;
    }

    // record this player's personal clear of every in-rotation boss floor BELOW `floor`. Called when they pass the
    // sequential boss gate to enter `floor`: passing proves every such floor was satisfied for them at that moment.
    // Out-of-rotation floors are skipped, matching the gate, so a floor later put back in rotation is not silently
    // pre-cleared. Idempotent (recordCleared no-ops when already held).
    private static void recordBossClearsBelow(MinecraftServer server, ServerPlayer player, int floor) {
        DungeonFloors floors = DungeonFloors.get(server);
        if (floors == null) {
            return;
        }
        for (int i = 1; i < floor; i++) {
            DungeonFloorConfig c = floors.get(i);
            if (c != null && c.isBoss() && c.inRotation) {
                DungeonBossUnlocks.recordCleared(player, i);
            }
        }
    }

    // whether THIS crossing needs a ticket: the floor is flagged, or the portal walked into is. Missing floors /
    // storage / portals read as not required, so a bad target never becomes an accidental lock.
    private static boolean ticketLocked(MinecraftServer server, EntityPortalEvent event, int floor) {
        DungeonFloors floors = DungeonFloors.get(server);
        DungeonFloorConfig c = floors == null ? null : floors.get(floor);
        if (c != null && c.requireTicket) {
            return true;
        }
        return portalRequiresTicket(event);
    }

    // the per-portal flag, found by looking the entry position up in SU's portal registry. read here, not carried on
    // the event, because the event also fires for vanilla portal blocks with no SU portal behind them.
    private static boolean portalRequiresTicket(EntityPortalEvent event) {
        if (event.worldFrom == null || event.posFrom == null) {
            return false;
        }
        try {
            net.shurui.shuruisutilities.teleport.portal.Portal portal = TeleportHooks.get()
                    .portalAt(new net.shurui.shuruisutilities.commons.selections.WorldPoint(
                            event.worldFrom, event.posFrom));
            return portal != null && portal.isRequireTicket();
        } catch (Throwable t) {
            // an SU without the flag, or no portal registry yet: treat as unlocked rather than sealing the door
            return false;
        }
    }

    // send the player to a floor through the single gated entry, turning its Result into feedback. NO_KEY: the floor
    // feature is off, nothing generates, and the player stays put (the raw SU teleport was already cancelled).
    private static void routeToFloor(ServerPlayer player, int floor) {
        String m = "message.dmz_ragnarok.dungeons.";
        DungeonFloorManager.Result result = DungeonFloorManager.teleportToFloor(player, floor);
        if (result == DungeonFloorManager.Result.SUCCESS) {
            return;
        }
        if (result == DungeonFloorManager.Result.NO_KEY) {
            player.displayClientMessage(Component.translatable(m + "portal_floor_no_key")
                    .withStyle(net.minecraft.ChatFormatting.RED), true);
            return;
        }
        player.displayClientMessage(Component.translatable(m + "portal_floor_failed", floor)
                .withStyle(net.minecraft.ChatFormatting.RED), true);
    }

    // op (perm 2) OR the dungeon.bypass_timelimit node grants bypass, identical to DungeonTimeEvents
    private static boolean canBypass(ServerPlayer player) {
        return player.hasPermissions(2) || PermissionAPI.getPermission(player, DungeonTimeEvents.BYPASS_TIMELIMIT);
    }

    // push the player horizontally AWAY from the portal entry (posFrom), mirroring EffectKnockback. falls back to
    // opposite the player's facing when the direction is degenerate (standing on the portal centre).
    private static void shoveBack(ServerPlayer player, EntityPortalEvent event, double px, double py, double pz) {
        double cx = event.posFrom != null ? event.posFrom.getX() + 0.5 : px;
        double cz = event.posFrom != null ? event.posFrom.getZ() + 0.5 : pz;

        double dx = px - cx;
        double dz = pz - cz;
        double len = Math.sqrt(dx * dx + dz * dz);

        if (len < 1.0e-4) {
            // degenerate: shove opposite the direction the player is looking
            float yawRad = (float) Math.toRadians(player.getYRot());
            dx = Math.sin(yawRad);
            dz = -Math.cos(yawRad);
            len = 1.0;
        }

        double nx = dx / len;
        double nz = dz / len;

        // teleport one block out of the portal area and apply a modest outward velocity so they clear it
        double outX = px + nx * 1.5;
        double outZ = pz + nz * 1.5;
        player.connection.teleport(outX, py, outZ, player.getYRot(), player.getXRot());
        player.setDeltaMovement(nx * 0.6, 0.2, nz * 0.6);
        player.hurtMarked = true; // force the velocity to sync to the client
    }
}
