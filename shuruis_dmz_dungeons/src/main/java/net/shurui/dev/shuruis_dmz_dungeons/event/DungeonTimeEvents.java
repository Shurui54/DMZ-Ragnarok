package net.shurui.dev.shuruis_dmz_dungeons.event;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;
import net.shurui.dev.shuruis_dmz_dungeons.Config;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonArenaGuests;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonCooldowns;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonDimensions;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonRules;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonTimeFormat;

// dungeon time limit + re-entry cooldown, plus the bypass permission node.
//
// enforcement keys off BEING IN THE DIM (dimension-change + player tick), not entry packets, so every path in
// is covered. state is the time accumulator in the player's PlayerPersisted tag (see runData) and a UUID-keyed
// overworld SavedData
// (DungeonCooldowns) for cooldown expiries. An eject always goes to spawn, so no return location is kept.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_dungeons")
public final class DungeonTimeEvents {

    // persistent-data keys (namespaced so we never collide with DMZ / other addons). They live in Forge's
    // PlayerPersisted sub-tag, NOT the root: the root copy is local to one server's playerdata file and is dropped
    // on death, while PlayerPersisted survives a respawn AND is the tag the shard vault carries between shards. In
    // the root, hopping OW1 to OW2 handed the player a brand new timer, which is the same "reset the dungeon clock"
    // hole /back had, through a different door (reported 2026-09-16).
    private static final String KEY_TICKS = "sdd_dungeon_ticks";
    // set true when a re-entry cooldown is stamped, cleared when the player has been told the cooldown is over. Lives
    // in persistent data (not memory) so it survives a restart and travels with the player, which is what lets a
    // player who was OFFLINE when the cooldown elapsed still get the one notification on their next login/arrival.
    private static final String KEY_COOLDOWN_NOTIFY = "sdd_dungeon_cooldown_notify";
    // wall-clock millis of the moment the player last stepped OUT of a dungeon dimension, with time still on the
    // accumulator. A re-entry within the resume window (see resumeWindowMillis) continues that run instead of
    // starting a new one, which is what stops a player stepping out and back in to hand themselves a fresh timer.
    private static final String KEY_LEFT_AT = "sdd_dungeon_left_at";

    // per-player dungeon time-left boss bar, created lazily while a non-bypass player is inside a timed dungeon floor
    // and torn down the moment they leave it, die, log out or hop shards. Vanilla ServerBossEvent: server-side only
    // (no client packet of our own), and setProgress / setName push ONLY on an actual change, so the once-a-second
    // update below is at most one packet a second per player. Not persisted: a bar is transient view state.
    private static final java.util.Map<java.util.UUID, ServerBossEvent> BARS =
            new java.util.concurrent.ConcurrentHashMap<>();
    // cached bypass result per player, so the per-second path skips the permission lookups for ~30s. Held in MEMORY
    // with a wall-clock expiry on purpose: persistent data travels between shards with the player and each shard's
    // game time differs, so a game-time expiry carried to a shard whose clock is behind would keep a stale bypass
    // for hours.
    private static final java.util.Map<java.util.UUID, long[]> BYPASS_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long BYPASS_CACHE_MS = 30_000L;
    // the per-player time accounting runs once a second, not every tick, and advances by a whole second
    private static final int TICK_INTERVAL = 20;
    // There are no sdd_dungeon_return_* keys any more. An eject goes to spawn (see returnPlayer), so the entry
    // position is not needed, and recording it was what put an ejected player back on the portal they came in by.
    // Stale copies may still sit in old players' persistent data; nothing reads them and they cost four tags.

    // Forge permission node: ops bypass via the default resolver even with no permission mod installed. The
    // "dungeons." path segment keeps this node distinct from the sibling addons' nodes after the five collapsed
    // onto the shared dmz_ragnarok modid.
    public static final PermissionNode<Boolean> BYPASS_TIMELIMIT = new PermissionNode<>(
            new ResourceLocation(Shuruis_dmz_dungeons.MODID, "dungeons.bypass_timelimit"),
            PermissionTypes.BOOLEAN,
            (player, playerUUID, context) -> player != null && player.hasPermissions(2));

    private DungeonTimeEvents() {
    }

    @SubscribeEvent
    public static void onGatherPermissionNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(BYPASS_TIMELIMIT);
    }

    // op (perm 2) OR the permission node grants bypass, so ops always bypass even without a perms mod
    private static boolean canBypass(ServerPlayer player) {
        return player.hasPermissions(2) || PermissionAPI.getPermission(player, BYPASS_TIMELIMIT);
    }

    // canBypass with a ~30s per-player cache, for the every-second tick path. entry checks stay uncached (rare).
    private static boolean cachedBypass(ServerPlayer player) {
        long now = System.currentTimeMillis();
        long[] cached = BYPASS_CACHE.get(player.getUUID()); // {expiryMs, bypass ? 1 : 0}
        if (cached != null && now < cached[0]) {
            return cached[1] == 1L;
        }
        boolean bypass = canBypass(player);
        BYPASS_CACHE.put(player.getUUID(), new long[]{now + BYPASS_CACHE_MS, bypass ? 1L : 0L});
        return bypass;
    }

    // Forge's PlayerPersisted sub-tag, created on demand. Everything this class stores about a run goes here so it
    // survives a death and travels with the player to the other shards.
    private static net.minecraft.nbt.CompoundTag runData(ServerPlayer player) {
        net.minecraft.nbt.CompoundTag root = player.getPersistentData();
        String key = net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG;
        if (!root.contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            root.put(key, new net.minecraft.nbt.CompoundTag());
        }
        return root.getCompound(key);
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ResourceKey<Level> to = event.getTo();
        ResourceKey<Level> from = event.getFrom();
        // LEAVING the dungeon (out of any dungeon dim into a non-dungeon one, including the time-out eject and a
        // manual portal out): drop the time-left bar. A floor-to-floor move stays inside a dungeon dim and keeps it.
        if (DungeonDimensions.isAnyDungeon(from) && !DungeonDimensions.isAnyDungeon(to)) {
            removeBar(player.getUUID());
            // remember WHEN they left, so a quick trip back in resumes this run rather than restarting it. Recorded
            // for every way out (a portal, /spawn, /home, the time-out eject, a death respawn), because every one of
            // them was a way to put the timer back to full.
            runData(player).putLong(KEY_LEFT_AT, System.currentTimeMillis());
        }
        // only a FRESH entry into a dungeon dim counts. moving BETWEEN dungeon dims (floor to floor, or legacy hub
        // -> themed floor) is not a new visit: leave the running accumulator alone so the timer spans the whole
        // run rather than restarting at every floor.
        if (!DungeonDimensions.isAnyDungeon(to) || DungeonDimensions.isAnyDungeon(from)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        // A raid/rift arena guest is standing in a dungeon theme dimension without being on a dungeon run: the
        // dimensional tears reuse these dimensions for their arenas. Treat them as not present for dungeon
        // purposes, or the cooldown eject below fires against the rift's own confine pass and the two thrash the
        // player across dimensions until the client runs out of memory (seen live 2026-09-15). No timer, no eject,
        // and deliberately no accumulator reseed, so a real dungeon they enter later still starts its timer fresh.
        if (DungeonArenaGuests.contains(player.getUUID())) {
            return;
        }
        // ops / bypass holders enter freely, no cooldown check, no accumulator
        if (canBypass(player)) {
            return;
        }
        // deny entry while on cooldown: bounce them straight back out
        DungeonCooldowns cooldowns = DungeonCooldowns.get(server);
        long now = System.currentTimeMillis();
        if (cooldowns.onCooldown(player.getUUID(), now)) {
            long secs = cooldowns.remainingSeconds(player.getUUID(), now);
            // backstop for non-portal entries that land in the dungeon anyway. The bounce MUST defer to the next
            // tick: this fires inside the dim change that just completed, and a synchronous returnPlayer is a
            // second transfer one tick after the first, which hangs the client on a black loading screen.
            // server.execute lets the first settle. (primary fix for SU /portal is the veto in UtilitiesPortalCompat.)
            server.execute(() -> returnPlayer(player, server));
            player.displayClientMessage(Component.translatable(
                    "message.dmz_ragnarok.dungeons.cooldown_entry", DungeonTimeFormat.human(secs)), true);
            return;
        }
        // fresh entry: reseed the accumulator so the timer spans this run only. A re-entry that lands inside the
        // resume window is the SAME run continued, so it keeps the time already spent: /back (and /home, /tp, a
        // death respawn) took a player out and straight back to where they stood with a full timer, which made the
        // time limit unenforceable. Reported 2026-09-15: "players can just /back twice to reset the dungeon timer".
        if (resumesPreviousRun(player, server)) {
            player.displayClientMessage(Component.translatable(
                    "message.dmz_ragnarok.dungeons.run_resumed"), true);
            return;
        }
        runData(player).putInt(KEY_TICKS, 0);
        runData(player).putLong(KEY_LEFT_AT, 0L);
    }

    // does this entry continue the run the player just stepped out of? Only when they still have time on the clock
    // and they left recently, where "recently" is the resume window below. Anything older is a new visit and gets a
    // fresh timer, so a player who ran a dungeon this morning is not still carrying those minutes tonight.
    private static boolean resumesPreviousRun(ServerPlayer player, MinecraftServer server) {
        if (runData(player).getInt(KEY_TICKS) <= 0) {
            return false;
        }
        long leftAt = runData(player).getLong(KEY_LEFT_AT);
        if (leftAt <= 0L) {
            return false;
        }
        long since = System.currentTimeMillis() - leftAt;
        // a negative gap means the stamp came from a shard whose clock runs ahead of this one (persistent data
        // travels with the player). Treat it as a fresh run: the worst case is the old behaviour, never a player
        // stuck with a timer they cannot clear.
        if (since < 0L) {
            return false;
        }
        long window = resumeWindowMillis(server);
        return window > 0L && since <= window;
    }

    // how long a stepped-out run stays resumable: the longer of the dungeon time limit and the re-entry cooldown,
    // both read from the live rules the dungeon manager GUI edits. Tying it to the limit means a player can never
    // wait out their own run, and tying it to the cooldown keeps it at least as strict as an eject would have been.
    private static long resumeWindowMillis(MinecraftServer server) {
        DungeonRules rules = DungeonRules.get(server);
        int limitSeconds = rules == null ? Config.dungeonTimeLimitSeconds : rules.timeLimitSeconds;
        int cooldownSeconds = rules == null ? Config.dungeonCooldownSeconds : rules.cooldownSeconds;
        long seconds = Math.max(limitSeconds, cooldownSeconds);
        return seconds <= 0L ? 0L : seconds * 1000L;
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (!DungeonDimensions.isAnyDungeon(player.level())) {
            return;
        }
        // A raid/rift arena guest is here to fight a tear, not on a dungeon run: no time limit, and no bar. Kept in
        // step with the same exemption in onChangedDimension above.
        if (DungeonArenaGuests.contains(player.getUUID())) {
            removeBar(player.getUUID());
            return;
        }
        // the permission + SavedData + NBT work below is too heavy to run every tick per player. run it once a
        // second and advance the accumulator by a whole second, so the effective time limit is unchanged (accurate
        // to within 1s). nothing reads KEY_TICKS for a per-tick display, so the coarser cadence is invisible.
        if (player.tickCount % TICK_INTERVAL != 0) {
            return;
        }
        if (cachedBypass(player)) {
            removeBar(player.getUUID()); // ops / bypass have no timer, so never a bar
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        // read the limit from the live, per-world rules (edited via the dungeon manager GUI)
        DungeonRules rules = DungeonRules.get(server);
        int limitSeconds = rules == null ? Config.dungeonTimeLimitSeconds : rules.timeLimitSeconds;
        if (limitSeconds <= 0) {
            removeBar(player.getUUID()); // feature disabled: no timer, no bar
            return;
        }
        // A floor build has this player frozen in place (pinned, with a "Building dungeon floor..." bar): they cannot
        // play or move, so do not charge them the run timer, nor eject them, while it runs. A first, cold-ground visit
        // is chunk-warmup throttled and can span many ticks, and a large floor now pins the player for the whole build
        // (see the key's floor build freeze radius); without this the limit could expire mid-build and throw a pinned player
        // to spawn.
        if (net.shurui.dev.shuruis_dmz_dungeons.api.key.DungeonKeyHooks.get().isFrozen(player.getUUID())) {
            return;
        }
        int totalTicks = limitSeconds * 20;
        int ticks = runData(player).getInt(KEY_TICKS) + TICK_INTERVAL;
        if (ticks < totalTicks) {
            runData(player).putInt(KEY_TICKS, ticks);
            // show the shrinking time-left bar. Same configured limit that enforces the eject, so the number the
            // player watches count down is exactly the number that ends the run.
            updateBar(player, totalTicks - ticks, totalTicks);
            return;
        }
        // over the limit: eject, clear accumulator, stamp cooldown. The exit stamp goes with it, so the next entry
        // (after the cooldown) is a new run with a full timer.
        runData(player).putInt(KEY_TICKS, 0);
        runData(player).putLong(KEY_LEFT_AT, 0L);
        removeBar(player.getUUID());
        stampCooldown(player, server);
        returnPlayer(player, server);
        player.displayClientMessage(Component.translatable(
                "message.dmz_ragnarok.dungeons.time_up"), true);
    }

    // create-or-update this player's time-left boss bar. remainingTicks / totalTicks drive the fill (a countdown, so
    // the bar empties as time runs out) and the title carries mm:ss. setProgress / setName no-op when the value is
    // unchanged, so at the once-a-second cadence this is at most one packet a second.
    private static void updateBar(ServerPlayer player, int remainingTicks, int totalTicks) {
        ServerBossEvent bar = BARS.get(player.getUUID());
        if (bar == null) {
            bar = new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.BLUE,
                    BossEvent.BossBarOverlay.PROGRESS);
            bar.addPlayer(player);
            BARS.put(player.getUUID(), bar);
        } else if (!bar.getPlayers().contains(player)) {
            // same UUID, new ServerPlayer instance (a respawn or relog into the dungeon): re-point the bar at it.
            bar.addPlayer(player);
        }
        long secondsLeft = (remainingTicks + 19) / 20; // ceil, so a bar with time left never reads 0:00
        float progress = totalTicks <= 0 ? 0f : Math.max(0f, Math.min(1f, remainingTicks / (float) totalTicks));
        bar.setProgress(progress);
        bar.setName(Component.translatable("bossbar.dmz_ragnarok.dungeon.time",
                DungeonTimeFormat.clock(secondsLeft)));
    }

    // tear down a player's time-left bar if they have one. Safe to call for a player who never had one.
    private static void removeBar(java.util.UUID id) {
        ServerBossEvent bar = BARS.remove(id);
        if (bar != null) {
            bar.removeAllPlayers();
            bar.setVisible(false);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // covers a plain logout AND a shard hop (a hop is a logout on this server): drop the bar so it does not leak.
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        removeBar(player.getUUID());
        // A hop leaves the dungeon dimension without ever firing a dimension change here, so the exit stamp that
        // makes a re-entry resume the same run has to be taken on the way out as well. Without it a player hopped
        // OW1 to OW2 and walked back in on a full timer.
        if (DungeonDimensions.isAnyDungeon(player.level()) && runData(player).getInt(KEY_TICKS) > 0) {
            runData(player).putLong(KEY_LEFT_AT, System.currentTimeMillis());
        }
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            removeBar(player.getUUID());
        }
    }

    // Once a second, tell any online player whose re-entry cooldown has just elapsed that they may enter again, the
    // same one-shot action-bar line repeatable quests use. Cheap: it only touches players carrying the pending flag
    // (set when a cooldown is stamped), and clears the flag after sending so it fires exactly once. A player who was
    // OFFLINE when the cooldown elapsed still carries the flag in persistent data, so their first tick after login
    // (on whichever shard) delivers the one notification; the cleared flag then travels with them, so a later shard
    // hop does not repeat it.
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null || server.getTickCount() % TICK_INTERVAL != 0) {
            return;
        }
        DungeonCooldowns cooldowns = DungeonCooldowns.get(server);
        long now = System.currentTimeMillis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!runData(player).getBoolean(KEY_COOLDOWN_NOTIFY)) {
                continue;
            }
            if (cooldowns.onCooldown(player.getUUID(), now)) {
                continue; // still waiting
            }
            runData(player).putBoolean(KEY_COOLDOWN_NOTIFY, false);
            player.displayClientMessage(Component.translatable(
                    "message.dmz_ragnarok.dungeons.cooldown_ready"), true);
        }
    }


    // eject the player out of the dungeon, to SPAWN, using the SINGLE shard-aware resolution /spawn, a fresh login
    // and spawn-target portals all use (ShardSync.placeAtServerSpawn via UtilitiesSpawnCompat). That lands the
    // ejected player at exactly THIS server's spawn: the SU spawn an operator set, else this server's overworld
    // world spawn, and on a server with no spawn of its own (the SMP) it hands them to an open world that places
    // them on arrival. It used to fall back to a RAW overworld.getSharedSpawnPos() here, which diverged from /spawn
    // (no shard route, and on the SMP the local empty overworld copy) and is why an eject could drop a player at a
    // "random spot" rather than at spawn.
    //
    // IT USED TO PREFER A RECORDED ORIGIN: the position the player stood at when they entered. That is where they
    // want to be, and it is also exactly the wrong place to put them, because the way into a dungeon is a portal
    // and the position they entered from is standing ON it. An eject therefore handed the player straight back to
    // the thing that had just rejected them, SU's PortalManager fired again the moment they landed (playerMove
    // triggers on entering a portal area, and arriving from another dimension counts as entering), and they went
    // back in. Observed in production on 2026-09-11: a player on re-entry cooldown was thrown between
    // minecraft:overworld and dmz_ragnarok:kai twelve times in six seconds, about one round trip every half
    // second, each one a full dimension transfer.
    //
    // Spawn cannot do that. It is a fixed point nowhere near a dungeon portal, so the ejected player stays
    // ejected whatever rejected them, and the loop is closed off at the source rather than guarded against.
    private static void returnPlayer(ServerPlayer player, MinecraftServer server) {
        net.shurui.dev.shuruis_dmz_dungeons.compat.UtilitiesSpawnCompat.sendToServerSpawn(player);
    }

    // stamp a re-entry cooldown expiry (now + configured cooldown). no-op if cooldown disabled.
    private static void stampCooldown(ServerPlayer player, MinecraftServer server) {
        DungeonRules rules = DungeonRules.get(server);
        int cooldownSeconds = rules == null ? Config.dungeonCooldownSeconds : rules.cooldownSeconds;
        if (cooldownSeconds <= 0) {
            return;
        }
        long expiry = System.currentTimeMillis() + cooldownSeconds * 1000L;
        DungeonCooldowns.get(server).stamp(player.getUUID(), expiry);
        // arm the "cooldown is over" notification: onServerTick clears this and tells the player once the wait ends.
        runData(player).putBoolean(KEY_COOLDOWN_NOTIFY, true);
    }
}
