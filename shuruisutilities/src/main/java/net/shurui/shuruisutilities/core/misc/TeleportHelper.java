package net.shurui.shuruisutilities.core.misc;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.api.permissions.Zone;
import net.shurui.shuruisutilities.commons.selections.WarpPoint;
import net.shurui.shuruisutilities.commons.selections.WorldPoint;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.PlayerInfo;
import net.shurui.shuruisutilities.util.ServerUtil;
import net.shurui.shuruisutilities.util.events.ServerEventHandler;
import net.shurui.shuruisutilities.util.events.entity.EntityPortalEvent;
import net.shurui.shuruisutilities.util.events.player.PlayerChangedZone;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.world.level.block.Block;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class TeleportHelper extends ServerEventHandler
{
    public static class TeleportInfo
    {

        private Player player;

        private long start;

        private int timeout;

        private WarpPoint point;

        private WarpPoint playerPos;

        // retained for API/signature stability; no longer gates anything since every teleport now resolves to a
        // safe spot instead of refusing (see checkedTeleport).
        private boolean force;

        public TeleportInfo(Player player, WarpPoint point, int timeout)
        {
            this(player, point, timeout, false);
        }

        public TeleportInfo(Player player, WarpPoint point, int timeout, boolean force)
        {
            this.point = point;
            this.timeout = timeout;
            this.start = System.currentTimeMillis();
            this.player = player;
            this.playerPos = new WarpPoint(player);
            this.force = force;
        }

        public boolean check()
        {
            if (playerPos.distance(new WarpPoint(player)) > 0.2)
            {
                ChatOutputHandler.chatWarning(player, "Teleport cancelled.");
                // The move never fired, so if the depart animation was pre-played, forget it: the player's NEXT
                // teleport must play its depart normally rather than be suppressed by this abandoned one.
                net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticAnimations.cancelTimedDepart(player.getUUID());
                return true;
            }
            if (System.currentTimeMillis() - start < timeout)
            {
                return false;
            }
            checkedTeleport(player, point, force);
            ChatOutputHandler.chatConfirmation(player, "Teleported.");
            return true;
        }

    }

    public static final String TELEPORT_COOLDOWN = "su.teleport.cooldown";
    public static final String TELEPORT_WARMUP = "su.teleport.warmup";
    // skip all teleport cooldowns AND warmups (evaluated against whoever ORDERED the teleport, not the moved player)
    public static final String TELEPORT_COOLDOWN_BYPASS = "su.teleport.cooldown.bypass";
    // land EXACTLY at the requested destination, entering flight instead of being relocated when the spot is not a
    // safe standing position. Only entitled players get this; everyone else keeps the nearest-safe-spot relocate.
    // Being ALREADY able to fly (getAbilities().mayfly) also qualifies, so an admin already in flight is not demoted
    // to the ground on arrival. Gated because free flight for anyone teleporting into a wall would be an exploit.
    public static final String TELEPORT_EXACT = "su.teleport.exact";

    // teleport "kinds" with their own cooldown/warmup via su.teleport.cooldown.K / su.teleport.warmup.K
    // (seconds). blank/unset kind property falls back to the global TELEPORT_COOLDOWN/TELEPORT_WARMUP.
    public static final String[] KINDS = { "home", "warp", "pwarp", "back", "tpa", "spawn", "bed", "top", "jump",
            "tppos", "tp" };
    public static final String TELEPORT_CROSSDIM_FROM = "su.teleport.crossdim.from";
    public static final String TELEPORT_CROSSDIM_TO = "su.teleport.crossdim.to";
    public static final String TELEPORT_CROSSDIM_PORTALFROM = "su.teleport.crossdim.portalfrom";
    public static final String TELEPORT_CROSSDIM_PORTALTO = "su.teleport.crossdim.portalto";
    public static final String TELEPORT_FROM = "su.teleport.from";
    public static final String TELEPORT_TO = "su.teleport.to";
    public static final String TELEPORT_PORTALFROM = "su.teleport.portalfrom";
    public static final String TELEPORT_PORTALTO = "su.teleport.portalto";

    private static Map<UUID, TeleportInfo> tpInfos = new HashMap<>();

    // per-kind last-teleport timestamps (ms), separate from PlayerInfo's single global one used by /back
    private static final Map<UUID, Map<String, Long>> lastTeleportByKind = new HashMap<>();

    private static long lastTeleportOfKind(UUID id, String kind)
    {
        Map<String, Long> m = lastTeleportByKind.get(id);
        Long t = m == null ? null : m.get(kind);
        return t == null ? 0L : t;
    }

    private static void recordTeleportOfKind(UUID id, String kind)
    {
        lastTeleportByKind.computeIfAbsent(id, k -> new HashMap<>()).put(kind, System.currentTimeMillis());
    }

    // cooldown/warmup property for a kind, falling back to the global base when base.kind is unset/blank
    private static String kindProperty(UserIdent ident, String base, String kind)
    {
        if (kind != null && !kind.isEmpty())
        {
            String v = APIRegistry.perms.getUserPermissionProperty(ident, base + "." + kind);
            if (v != null && !v.isEmpty())
                return v;
        }
        return APIRegistry.perms.getUserPermissionProperty(ident, base);
    }

    // register the per-kind cooldown/warmup properties + bypass perm so they show in the perms GUI
    public static void registerCooldownPermissions()
    {
        APIRegistry.perms.registerPermission(TELEPORT_COOLDOWN_BYPASS,
                net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel.OP,
                "Bypass all teleport cooldowns and warmups");
        APIRegistry.perms.registerPermission(TELEPORT_EXACT,
                net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel.OP,
                "Land exactly at the teleport destination, entering flight when it is not a safe standing spot");
        APIRegistry.perms.registerPermissionProperty(TELEPORT_COOLDOWN, "0",
                "Default teleport cooldown in seconds (fallback for every teleport kind)");
        APIRegistry.perms.registerPermissionProperty(TELEPORT_WARMUP, "0",
                "Default teleport warmup in seconds - stand still this long before teleporting (fallback for every kind)");
        for (String kind : KINDS)
        {
            APIRegistry.perms.registerPermissionProperty(TELEPORT_COOLDOWN + "." + kind, "",
                    "Cooldown in seconds for /" + kind + " (blank = use the default cooldown)");
            APIRegistry.perms.registerPermissionProperty(TELEPORT_WARMUP + "." + kind, "",
                    "Warmup in seconds for /" + kind + " (blank = use the default warmup)");
        }
    }

    /**
     * Run the per-kind cooldown check for a teleport that does NOT go through {@link #teleport}, and start the
     * cooldown when it passes. True means go ahead; false means the player has already been told how long is left.
     *
     * <h2>Why this is exposed</h2>
     * A cross-server teleport cannot use {@link #teleport}: there is no local {@code ServerLevel} to move the player
     * to, the move is a proxy handoff and the placement happens on the far side. Without this the hop would be the
     * one teleport in the suite with no cooldown at all, and a free, repeatable shard hop is the exact shape of the
     * reconnect storm documented in {@code ShardDimensions}. The same per-kind property and the same bypass
     * permission are used, so an operator configures {@code su.teleport.cooldown.back} once and it covers both the
     * local and the cross-server case.
     *
     * <p>The bypass is evaluated against the player themselves, which is correct for every current caller (a
     * self-initiated command). A future caller that teleports somebody ELSE across servers should use the initiator
     * here, as {@link #teleport} does.
     */
    public static boolean startCooldown(Player player, String kind)
    {
        UserIdent ident = UserIdent.get(player);
        String kindKey = (kind == null || kind.isEmpty()) ? "teleport" : kind;
        if (APIRegistry.perms.checkPermission(player, TELEPORT_COOLDOWN_BYPASS))
            return true;
        int teleportCooldown = ServerUtil.parseIntDefault(kindProperty(ident, TELEPORT_COOLDOWN, kind), 0) * 1000;
        if (teleportCooldown <= 0)
            return true;
        long remaining = (lastTeleportOfKind(player.getUUID(), kindKey) + teleportCooldown)
                - System.currentTimeMillis();
        if (remaining > 0)
        {
            ChatOutputHandler.chatNotification(player, "Cooldown still active. %s to go.",
                    net.shurui.shuruisutilities.util.StringUtil.formatDuration(remaining / 1000 + 1));
            return false;
        }
        recordTeleportOfKind(player.getUUID(), kindKey);
        return true;
    }

    public static void teleport(Player player, WarpPoint point) throws CommandSyntaxException
    {
        teleport(player, point, null);
    }

    public static void teleport(Player player, WarpPoint point, String kind) throws CommandSyntaxException
    {
        teleport(player, point, kind, false);
    }

    // `force` is retained for signature stability but no longer changes behaviour: every teleport now resolves to
    // the nearest safe spot (force-loading the chunk) instead of refusing for obstruction. Permission, cooldown,
    // cross-dimension and the upstream jail/combat checks still apply. The initiator defaults to the moved player
    // (a self-initiated teleport), so the cooldown/warmup bypass is checked against the player themselves.
    public static void teleport(Player player, WarpPoint point, String kind, boolean force) throws CommandSyntaxException
    {
        teleport(player, player, point, kind, force);
    }

    // Overload that threads the INITIATOR (whoever ORDERED the teleport) separately from the moved player. The
    // cooldown/warmup bypass permission is evaluated against the initiator, so a moderator with the bypass moving an
    // ordinary player does not make that player serve their own cooldown or warmup. Everything else (permission,
    // cross-dimension, and the upstream jail/combat checks) is still evaluated against the moved player, because
    // those decide where THEY are allowed to go.
    public static void teleport(Player player, Player initiator, WarpPoint point, String kind, boolean force) throws CommandSyntaxException
    {
        if (initiator == null)
            initiator = player;
        if (point.getWorld() == null)
        {
            ChatOutputHandler.chatError(player,
                    "Unable to teleport! Target dimension does not exist");
            return;
        }

        // Check permissions
        UserIdent ident = UserIdent.get(player);
        if (!APIRegistry.perms.checkPermission(player, TELEPORT_FROM))
        {
            ChatOutputHandler.chatError(player, "You are not allowed to teleport from here.");
            return;
        }
        if (!APIRegistry.perms.checkUserPermission(ident, point.toWorldPoint(), TELEPORT_TO))
        {
            ChatOutputHandler.chatError(player, "You are not allowed to teleport to that location.");
            return;
        }
        if (!player.level().dimension().location().toString().equals(point.getDimension()))
        {
            if (!APIRegistry.perms.checkPermission(player, TELEPORT_CROSSDIM_FROM))
            {
                ChatOutputHandler.chatError(player, "You are not allowed to teleport from this dimension.");
                return;
            }
            if (!APIRegistry.perms.checkUserPermission(ident, point.toWorldPoint(), TELEPORT_CROSSDIM_TO))
            {
                ChatOutputHandler.chatError(player, "You are not allowed to teleport to that dimension.");
                return;
            }
        }

        // per-kind cooldown + timer (home/warp/back/tpa/...); bypass perm skips both the cooldown AND the warmup.
        // The bypass is checked against the INITIATOR (whoever ordered the teleport), not the moved player, so a
        // moderator's bypass applies when they teleport someone else.
        String kindKey = (kind == null || kind.isEmpty()) ? "teleport" : kind;
        boolean cooldownBypass = APIRegistry.perms.checkPermission(initiator, TELEPORT_COOLDOWN_BYPASS);
        int teleportCooldown = ServerUtil.parseIntDefault(kindProperty(ident, TELEPORT_COOLDOWN, kind), 0) * 1000;
        if (!cooldownBypass && teleportCooldown > 0)
        {
            long cooldownDuration = (lastTeleportOfKind(player.getUUID(), kindKey) + teleportCooldown)
                    - System.currentTimeMillis();
            if (cooldownDuration > 0)
            {
                ChatOutputHandler.chatNotification(player,
                        "Cooldown still active. %s to go.", net.shurui.shuruisutilities.util.StringUtil.formatDuration(cooldownDuration / 1000 + 1));
                return;
            }
        }
        // start this kind's cooldown now, covering the warmup wait too so it can't be re-triggered mid-warmup
        if (!cooldownBypass && teleportCooldown > 0)
            recordTeleportOfKind(player.getUUID(), kindKey);

        // Get and check teleport warmup. The bypass skips it too: an initiator with the bypass teleports instantly
        // with no stand-still wait (the owner asked for "the warm up and or cooldown" to be bypassed).
        int teleportWarmup = ServerUtil.parseIntDefault(kindProperty(ident, TELEPORT_WARMUP, kind), 0);

        // Time the teleport to the DEPART cosmetic animation: if the moved player has a TELEPORT animation equipped,
        // hold the actual move until its departing half has played, so the effect reads as the player leaving rather
        // than snapping away before it is seen. The animation delay is folded into the SAME stand-still warmup that
        // already carries the cooldown, the move-cancel and the safe-spot resolve, so it inherits all of that safety
        // for free (including cancelling if the player is knocked or walks off mid-animation). Zero when nothing is
        // equipped, so a player with no animation teleports exactly as before. Skipped entirely by the warmup bypass.
        int departTicks = player instanceof ServerPlayer sp0
                ? net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticAnimations.departDelayTicks(sp0) : 0;
        int animWarmupMs = cooldownBypass ? 0 : departTicks * 50;
        int warmupMs = Math.max(cooldownBypass ? 0 : teleportWarmup * 1000, animWarmupMs);

        if (warmupMs <= 0)
        {
            checkedTeleport(player, point, force);
            return;
        }

        // No pre-warmup obstruction refusal any more: the actual safe-spot resolve + relocate happens when the
        // teleport fires in checkedTeleport (reached via TeleportInfo.check below), so a warmup teleport never
        // gets refused for obstruction here either.

        // Play the departing half NOW, at the origin, where the player still stands; the move fires when the warmup
        // (at least as long as this animation) elapses, and doTeleport then plays the arriving half at the landing.
        if (animWarmupMs > 0 && player instanceof ServerPlayer sp1
                && sp1.level() instanceof net.minecraft.server.level.ServerLevel sl1)
        {
            net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticAnimations.beginTimedDepart(sp1, sl1,
                    sp1.position());
        }

        // Setup timed teleport
        tpInfos.put(player.getGameProfile().getId(), new TeleportInfo(player, point, warmupMs, force));
        ChatOutputHandler.chatNotification(player, "Teleporting. Please stand still for %s.",
                net.shurui.shuruisutilities.util.StringUtil.formatDuration((warmupMs + 999) / 1000));
    }

    // No longer used to gate teleports (SafeSpotResolver relocates instead of refusing); kept as a public probe
    // for any external caller that wants a simple "is this exact spot stand-able" boolean.
    public static boolean canTeleportTo(WarpPoint point)
    {
        // 1.18+ worlds go below Y=0 (down to getMinBuildHeight(), e.g. -64). the old `< 0` check falsely
        // called every deepslate/deep-cave destination "obstructed"; reject only Ys outside the build column.
        if (point.getY() < point.getWorld().getMinBuildHeight() || point.getY() >= point.getWorld().getMaxBuildHeight())
            return false;
        BlockPos blockPos1 = point.getBlockPos();
        BlockPos blockPos2 = new BlockPos(point.getBlockX(), point.getBlockY() + 1, point.getBlockZ());
        net.minecraft.world.level.block.state.BlockState state1 = point.getWorld().getBlockState(blockPos1);
        net.minecraft.world.level.block.state.BlockState state2 = point.getWorld().getBlockState(blockPos2);
        Block block1 = state1.getBlock();
        Block block2 = state2.getBlock();
        // AxisAlignedBB blockBounds1 =
        // block1.getBlockSupportShape(block1.defaultBlockState(), point.getWorld(),
        // blockPos1).bounds();
        // AxisAlignedBB blockBounds2 =
        // block2.getBlockSupportShape(block2.defaultBlockState(), point.getWorld(),
        // blockPos2).bounds();
        boolean block1Free = block1.isPossibleToRespawnInThis(state1);// || blockBounds1 == null || blockBounds1.maxX < 1 ||
                                                                // blockBounds1.maxY > 0;
        boolean block2Free = block2.isPossibleToRespawnInThis(state2);// || blockBounds2 == null || blockBounds2.maxX < 1 ||
                                                                // blockBounds2.maxY > 0;
        return block1Free && block2Free;
    }

    public static void checkedTeleport(Player player, WarpPoint point)
    {
        checkedTeleport(player, point, false);
    }

    public static void checkedTeleport(Player player, WarpPoint point, boolean force)
    {
        resolveLanding(player, point);

        PlayerInfo pi = PlayerInfo.get(player);
        WarpPoint old = new WarpPoint(player);
        pi.setLastTeleportOrigin(old);
        pi.setLastTeleportTime(System.currentTimeMillis());
        pi.setLastDeathLocation(null);

        doTeleport(player, point);
        postZoneChange(player, old, point);
    }

    /**
     * Move a player the GAME decided to move, with the same landing resolution as {@link #checkedTeleport} and none
     * of the gates that belong to a player-initiated teleport.
     *
     * <h2>Why this exists</h2>
     * {@link #teleport} is the pipeline for a teleport somebody ASKED for: it consults teleport permissions (including
     * the cross-dimension pair), the per-kind cooldown, and the stand-still warmup, and any of those can silently
     * decline the move. That is right for {@code /spawn} and wrong for an INVOLUNTARY, system-initiated placement:
     * a dungeon eject, a spawn-on-arrival placement, a rescue off an unhosted dimension. The warmup is the sharpest
     * edge, because {@code TeleportInfo.check} cancels as soon as the player moves 0.2 blocks and a player being
     * ejected from a dungeon is, by definition, being knocked about. The result was a player who was told nothing and
     * went nowhere.
     *
     * <p>So this keeps what the destination needs (the safe-spot resolve, or exact landing plus flight for those
     * entitled to it) and the zone-change event protection relies on, and drops the consent checks: there is no
     * consent to ask for. It also leaves the {@code /back} record ALONE, which {@link #checkedTeleport} overwrites,
     * because an involuntary move is not a place the player chose to leave, and on a cross-server hop the record it
     * would overwrite is the one that just travelled in the payload.
     */
    public static void forcedTeleport(Player player, WarpPoint point)
    {
        resolveLanding(player, point);
        WarpPoint old = new WarpPoint(player);
        doTeleport(player, point);
        postZoneChange(player, old, point);
    }

    // Post the zone transition for a completed teleport, so protection and zone listeners see the move. Shared by
    // the checked and the forced paths, which differ only in what they are allowed to refuse and what they record.
    private static void postZoneChange(Player player, WarpPoint old, WarpPoint point)
    {
        Zone before = APIRegistry.perms.getServerZone().getZonesAt(old.toWorldPoint()).get(0);
        Zone after = APIRegistry.perms.getServerZone().getZonesAt(point.toWorldPoint()).get(0);
        MinecraftForge.EVENT_BUS.post(new PlayerChangedZone(player, before, after, old, point));
    }

    // Decide where the player actually lands, mutating `point` in place. Extracted from checkedTeleport so the
    // forced path lands by exactly the same rules.
    private static void resolveLanding(Player player, WarpPoint point)
    {
        // Never refuse for obstruction or an unloaded destination. Two landing paths:
        //  - Players entitled to exact landing (the su.teleport.exact perm, or those who can already fly) land at the
        //    requested coordinate exactly, and are put into flight when it is not a safe standing spot, so moderation
        //    arrives where it asked or mid-air. A void destination still falls through to the spawn fallback.
        //  - Everyone else goes through SafeSpotResolver, which force-loads the target chunk and relocates the point
        //    to the nearest safe standing spot (see its javadoc). If the requested spot is already safe it is left
        //    exactly as asked. The caller's `force` flag no longer gates anything here.
        // Non-obstruction refusals (permissions, cooldowns, cross-dim, jail, combat) are all enforced upstream in
        // teleport()/the commands and are untouched.
        if (point.getWorld() != null)
        {
            ServerLevel world = point.getWorld();
            boolean withinColumn = point.getY() >= world.getMinBuildHeight()
                    && point.getY() < world.getMaxBuildHeight();
            // Entitled players (the su.teleport.exact perm, or anyone who can already fly) land EXACTLY where asked.
            // If that spot is not a safe standing position they are put into flight rather than relocated, so a
            // moderator arrives at the requested coordinate or mid-air instead of being dumped nearby. A destination
            // outside the build column (the void) has nothing to stand on or fly at, so it still falls through to the
            // resolver's spawn fallback below.
            if (entitledToExactLanding(player) && withinColumn)
            {
                if (!SafeSpotResolver.isStandable(world, point.getX(), point.getY(), point.getZ()))
                {
                    enterFlight(player);
                    ChatOutputHandler.chatNotification(player,
                            "Landed you at the exact destination and enabled flight.");
                }
                // else: the exact spot is already stand-able, so it is left as asked and no flight is needed.
            }
            else
            {
                SafeSpotResolver.Result safe = SafeSpotResolver.resolve(world, point.getX(), point.getY(),
                        point.getZ());
                point.setX(safe.x);
                point.setY(safe.y);
                point.setZ(safe.z);
                if (safe.fellBackToSpawn)
                {
                    ChatOutputHandler.chatNotification(player,
                            "No clear spot near your destination, so we sent you to spawn instead.");
                }
                else if (safe.moved)
                {
                    ChatOutputHandler.chatNotification(player, "Moved you to the nearest clear spot.");
                }
            }
        }
    }

    // A player is entitled to exact-landing-plus-flight if they hold TELEPORT_EXACT, OR they can ALREADY fly
    // (mayfly). The second clause means an admin already in flight is not dropped to the ground on arrival, and it
    // is not an exploit grant because they already had flight. Ordinary players qualify for neither and keep the
    // relocate behaviour.
    private static boolean entitledToExactLanding(Player player)
    {
        if (player.getAbilities().mayfly)
            return true;
        return APIRegistry.perms.checkPermission(player, TELEPORT_EXACT);
    }

    // Put the player into flight the same way CommandFly does (the repo's established mechanism): set vanilla
    // mayfly + flying and resync. DMZ's own ki flight is separate and does not fight this: its FlyStatusHandler only
    // clears vanilla mayfly/flying when the player's DMZ "fly" skill is active and their ki drains to zero, so a
    // player who is not mid ki-flight keeps the abilities set here.
    private static void enterFlight(Player player)
    {
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
    }

    public static void doTeleport(Player player, WarpPoint point)
    {
        if (point.getWorld() == null)
        {
            LoggingHandler.sulog.warn(
                    "Error teleporting player '{}': target dimension '{}' is not resolvable (world is NULL) - "
                            + "player left at current position",
                    player.getGameProfile().getName(), point.getDimension());
            return;
        }
        // TODO: Handle teleportation of mounted entity
        // Capture the origin BEFORE the move so a cosmetic TELEPORT animation can play its departing half where the
        // player vanishes from. This is the ONE same-server teleport chokepoint (checkedTeleport and forcedTeleport
        // both reach it), so hooking it here covers /home, /spawn, /tp, /back, /rtp, warps and every other SU
        // teleport without touching each command. A cross-shard hop does not come through here; it plays its pair
        // from ShardTransfer.connect instead.
        boolean isServerPlayer = player instanceof ServerPlayer;
        net.minecraft.server.level.ServerLevel fromLevel =
                isServerPlayer && player.level() instanceof net.minecraft.server.level.ServerLevel sl ? sl : null;
        net.minecraft.world.phys.Vec3 fromPos = isServerPlayer ? player.position() : null;
        player.stopRiding();
        ChunkPos chunkpos = new ChunkPos(point.getBlockPos());
        point.getWorld().getChunkSource().addRegionTicket(TicketType.POST_TELEPORT, chunkpos, 1, player.getId());
        if (!player.level().dimension().location().toString().equals(point.getDimension()))
        {
            // SimpleTeleporter teleporter = new SimpleTeleporter(point.getWorld());
            // player.changeDimension(point.getWorld());//, teleporter);
            ((ServerPlayer) player).teleportTo(point.getWorld(), point.getX(), point.getY(), point.getZ(),
                    point.getYaw(), point.getPitch());

        }else {
            ((ServerPlayer) player).connection.teleport(point.getX(), point.getY(), point.getZ(), point.getYaw(),
                    point.getPitch());
        }
        if (isServerPlayer && fromLevel != null)
        {
            // A no-op when the player has nothing equipped in the TELEPORT slot or animations are off, decided
            // inside the facade. On a cross-dimension teleport, PlayerChangedDimensionEvent also fires its arrive
            // half; the per-subject debounce swallows whichever lands second.
            net.minecraft.server.level.ServerLevel toLevel =
                    player.level() instanceof net.minecraft.server.level.ServerLevel tl ? tl : fromLevel;
            net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticAnimations.teleport((ServerPlayer) player,
                    fromLevel, fromPos, toLevel, player.position());
        }
    }

    public static void doTeleportEntity(Entity entity, WarpPoint point)
    {
        if (entity instanceof Player)
        {
            doTeleport((Player) entity, point);
            return;
        }
        if (!entity.level().dimension().location().toString().equals(point.getDimension()))
            entity.changeDimension(point.getWorld());
        entity.absMoveTo(point.getX(), point.getY(), point.getZ(), point.getYaw(), point.getPitch());
    }

    @SubscribeEvent
    public void serverTickEvent(TickEvent.ServerTickEvent e)
    {
        if (e.phase == TickEvent.Phase.START)
        {
            for (Iterator<TeleportInfo> it = tpInfos.values().iterator(); it.hasNext();)
            {
                TeleportInfo tpInfo = it.next();
                if (tpInfo.check())
                {
                    it.remove();
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void entityPortalEvent(EntityPortalEvent e)
    {
        UserIdent ident = null;
        if (e.getEntity() instanceof Player)
            ident = UserIdent.get((Player) e.getEntity());
        else if (e.getEntity() instanceof LivingEntity)
            ident = APIRegistry.IDENT_NPC;
        WorldPoint pointFrom = new WorldPoint(e.worldFrom, e.posFrom);
        WorldPoint pointTo = new WorldPoint(e.targetDimension, e.targetPos);
        if (!APIRegistry.perms.checkUserPermission(ident, pointFrom, TELEPORT_PORTALFROM))
            e.setCanceled(true);
        if (!APIRegistry.perms.checkUserPermission(ident, pointTo, TELEPORT_PORTALTO))
            e.setCanceled(true);
        if (!e.worldFrom.dimension().location().toString().equals(e.targetDimension.dimension().location().toString()))
        {
            if (!APIRegistry.perms.checkUserPermission(ident, pointFrom, TELEPORT_CROSSDIM_PORTALFROM))
                e.setCanceled(true);
            if (!APIRegistry.perms.checkUserPermission(ident, pointTo, TELEPORT_CROSSDIM_PORTALTO))
                e.setCanceled(true);
        }
    }

}
