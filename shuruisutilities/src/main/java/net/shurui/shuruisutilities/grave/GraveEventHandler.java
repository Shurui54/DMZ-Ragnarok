package net.shurui.shuruisutilities.grave;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.server.MinecraftServer;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.compat.DmzBridge;
import net.shurui.shuruisutilities.dragonballbag.DragonBallTotem;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Forge-bus driver for {@link KeepPartialInventory}. Server-side, no-op when the gamerule is off.
 *
 * <p>Event flow:
 * <ul>
 *   <li>LivingDeathEvent (HIGH): this MC/Forge version has no PlayerDropsEvent, and the inventory is dropped
 *       directly by Inventory#dropAll in vanilla death handling (never LivingDropsEvent). LivingDeathEvent
 *       fires from LivingEntity#die(), only AFTER totem protection aborted a survivable "death", so a totem
 *       proc never reaches here. At HIGH (before vanilla listeners) we read the DMZ level, snapshot what's
 *       kept onto persistent data, move main-inv 9-35 + XP into a grave (level>=100), then CLEAR those slots
 *       so vanilla's dropAll finds nothing. NOT cancelled (that would prevent the death).</li>
 *   <li>LivingExperienceDropEvent: cancelled while the rule is on; we handle the kept/grave XP.</li>
 *   <li>PlayerEvent.Clone (wasDeath): restore the snapshot from the OLD player onto the clone. Snapshot lives
 *       on persistent data so it survives logout-before-respawn; consumed here.</li>
 *   <li>PlayerLoggedInEvent (LOWEST) + the delayed revive check: the OTHER way a snapshot can be brought back to
 *       life. A cross-shard arrival can be REVIVED rather than respawned (see {@link #consumeSnapshot}), and a
 *       revive fires no Clone, so the respawn path above never runs and the snapshot would sit unconsumed on a
 *       living, empty-handed player. Both halves go through the same consumer.</li>
 * </ul>
 *
 * <p>The invariant across all of it: <b>a keep-inventory snapshot is consumed exactly once, by whoever brings the
 * player back to life</b>, respawn or revive. {@link #consumeSnapshot} removes the tag BEFORE it moves a single
 * item, so no two callers can ever apply the same snapshot.
 * Right-click a grave fence/head opens its GUI; breaking either block drops contents + XP and tears it down.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class GraveEventHandler
{
    private GraveEventHandler() {}

    // persistent-data key carrying the death snapshot from death to clone. Public because the cross-server vault
    // (ShardPayload) must strip it off a LIVING player before writing them to the vault: it is a within-life,
    // within-server handoff and delivering a stale copy to another shard poisons the next death (see onDeath).
    public static final String SNAPSHOT_TAG = "su_keepinv_snapshot";
    private static final int MODE_FULL = 0;    // full keep-inventory (level < threshold)
    private static final int MODE_PARTIAL = 1; // hotbar/offhand/armor kept, main inv + XP to grave

    private static final int SWEEP_INTERVAL_TICKS = 100; // every 5s; the despawn timer needs no finer precision

    // How long after a login / arrival the revive check waits before it decides. It MUST outlast DragonMineZ's
    // FORCED_KILL_GRACE_TICKS (40, read off TickHandler), because that window is precisely where "did this player
    // come back alive or are they about to really die" is still undecided: ShardSync.grantArrivalDeathGrace hands an
    // arriving player that grace so DMZ's regen gets two passes (REGEN_INTERVAL is 20) to lift them above zero
    // health. Checking inside it would either restore items into a player who is about to die anyway, or read a
    // still-zero health as "dead" and skip a player the very next regen tick revives. 60 ticks (3s) clears it with
    // room to spare and is still far too short for anyone to notice an empty inventory and log off over it.
    private static final int REVIVE_CHECK_DELAY_TICKS = 60;

    /**
     * How many times one login's revive check may be asked before it gives up for this session.
     *
     * <p>A single reading is not enough, because the question the check asks ({@code isDeadOrDying}) has a THIRD
     * answer live: "at zero health right now, and about to be lifted off it". DragonMineZ parks a player at zero
     * health for a tick or more in several places, the loudest being a fusion teardown, which strips the leader's
     * {@code FusionBonus} bonus stats and so collapses their max health in one step (ShardSync grants DMZ's
     * force-kill grace around exactly that window for the same reason). A player caught by one of those at tick 60
     * reads as dead, is skipped, and the old one-shot check then dropped its entry for good, leaving them walking
     * around empty handed until their next login even though they were never dead at all.
     *
     * <p>Ten attempts at {@link #REVIVE_CHECK_DELAY_TICKS} apart is thirty seconds, which covers every transient
     * dip and still ends. Retrying is safe by construction: {@link #tryConsumeOnRevive} only asks to be asked again
     * on the paths where it consumed NOTHING, and a player who respawns in the meantime has their snapshot taken by
     * {@link #onClone}, after which every remaining attempt finds no tag and stops.
     */
    private static final int MAX_REVIVE_CHECKS = 10;

    /** One player's outstanding revive check: the tick it is due on, and which attempt it is. */
    private static final class ReviveCheck
    {
        final long dueTick;
        final int attempt;

        ReviveCheck(long dueTick, int attempt)
        {
            this.dueTick = dueTick;
            this.attempt = attempt;
        }
    }

    // Players whose carried snapshot is waiting on a delayed revive check, mapped to when it is due and how many
    // times it has been asked. Holding an entry IS the right to run the check: the draining tick removes it first
    // and only then consumes, so a check cannot run twice, and a death or a respawn that takes the snapshot cancels
    // the entry outright. An entry is only ever put BACK by a check that consumed nothing.
    private static final Map<UUID, ReviveCheck> PENDING_REVIVE_CHECK = new ConcurrentHashMap<>();

    // Periodic expiry sweep (server thread): every SWEEP_INTERVAL_TICKS, remove graves older than the operator-set
    // lifetime (KeepPartialInventory.graveDespawnMinutes(), read live) across every loaded level. Contents + XP
    // DISCARDED (despawn, not a break). Expired positions collected first, then removed, so the grave map isn't
    // mutated mid-iteration.
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        MinecraftServer server = event.getServer();
        if (server == null)
            return;

        // drain deferred marker removals EVERY tick (not just on the sweep interval): a grave that expired in an
        // unloaded chunk can't kill its marker the same tick (1.17+ loads the entity section async). drainPending
        // retries here at tick END, after every ServerLevel ticked + drained its async loads. Guarded internally.
        GraveManager.drainPending(server);

        // Also every tick, for the same reason: a revive check is due on one exact tick and must not wait for the
        // five second grave sweep to come round. Cheap, it returns on an empty map, which is the normal state.
        drainReviveChecks(server);

        if (server.getTickCount() % SWEEP_INTERVAL_TICKS != 0)
            return;

        // Read the lifetime live every sweep: it is operator-settable (/rg npc totem time) and shard-synced, so a
        // cached copy would ignore a mid-session change. minutes -> ticks here; the store owns clamping.
        int lifetimeMinutes = KeepPartialInventory.graveDespawnMinutes();
        long graveMaxAgeTicks = lifetimeMinutes * 60L * 20L;

        for (ServerLevel level : server.getAllLevels())
        {
            if (level == null)
                continue;
            long now = level.getGameTime();
            GraveStorage storage = GraveStorage.get(level);
            List<BlockPos> expired = storage.positionsOlderThan(now, graveMaxAgeTicks);
            for (BlockPos pos : expired)
            {
                // Skip a grave whose marker removal is backing off. Without this the sweep re-queues the same
                // unfindable grave every five seconds forever, which is what filled the log with millions of
                // "not found before deadline" lines. GraveManager gives up for real after a few attempts.
                if (!GraveManager.markerRetryDue(level, pos, server.getTickCount()))
                    continue;
                // A grave still holding a dragon ball is never swept. Expiry DISCARDS contents, and letting a
                // ten-minute timer destroy part of a seven-ball set, with nobody there to stop it, is a much worse
                // outcome than a fence standing a while longer. Emptying it still removes it, through the
                // is-empty path in GraveManager.open, so this makes a totem untimed rather than permanent.
                GraveData data = storage.get(pos);
                if (data != null && DragonBallTotem.holdsDragonBall(data.container()))
                    continue;
                GraveManager.removeGrave(level, pos);
            }
        }
    }

    /* Death: decide + snapshot + build grave                        */

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDeath(LivingDeathEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        // BEFORE the gamerule gate, deliberately. KeepPartialInventory.isEnabled reads the gamerule of the level the
        // player is dying IN, so gating this first made "does an earlier life's items come back" depend on which
        // dimension somebody happened to die in: the rule is off in the Otherworld and on in the overworld, and live
        // that is the difference between a stranded player getting everything back and getting nothing. A carried
        // snapshot is handled identically in both now.
        handleCarriedSnapshotAtDeath(player);
        if (!KeepPartialInventory.isEnabled(player))
            return;

        // Take the dragon balls off the player BEFORE anything is snapshotted. They must never be kept through a
        // death, and they belong in a totem rather than on the floor, so from here they are ours to place: folded
        // into the grave below when there is one, given a ball-only totem when there is not. Balls in the equipped
        // bag come out too, which the grave portion alone would never have covered.
        List<ItemStack> balls = DragonBallTotem.extract(player);

        int level = DmzBridge.level(player);
        boolean partial = level >= KeepPartialInventory.FULL_KEEP_LEVEL_THRESHOLD;

        // A grave is only ever worth building where its owner can walk back to it, and one case reliably fails that
        // test: see fusionTeardownOnTheWayOut.
        if (partial && fusionTeardownOnTheWayOut(player))
        {
            LoggingHandler.sulog.warn("[Grave] {} died while disconnecting and still fused: keeping their whole "
                    + "inventory on the snapshot instead of splitting it into a grave they have already left "
                    + "behind. Their dragon balls still come off them into a totem.",
                    player.getGameProfile().getName());
            partial = false;
        }

        if (partial)
        {
            snapshotPartial(player, balls);
        }
        else
        {
            // full keep-inventory: no loot grave is built at all, so the balls get a totem of their own.
            snapshotFull(player);
            DragonBallTotem.entomb(player, balls);
        }
    }

    /**
     * True when this death is DragonMineZ tearing a fusion down on a player who is already on their way off this
     * server, which is the one death whose grave its owner is least likely ever to reach.
     *
     * <h2>What produces it</h2>
     * DMZ's {@code ForgeCommonEvents.endFusionIfNeeded} is mutually recursive across a fused pair: it kills the
     * OTHER half, and that half's death handler runs the same method back, which kills the first. It is called from
     * {@code PlayerLoggedOutEvent}, and Forge fires that as the FIRST statement of {@code PlayerList.remove}, before
     * the save and before the leaving player is taken out of the player list. So the partner lookup still resolves
     * the player who is leaving, and BOTH halves of the pair die inside the disconnect. Proven live on ow2,
     * 2026-09-19 02:57:37 UTC: {@code Peeky17 lost connection: Timed out} at .863, then {@code Peeky17 was killed}
     * and {@code TysonCreep was killed} at .867, the inner kill logging first exactly as the recursion predicts.
     *
     * <h2>Why a grave is the wrong answer for it</h2>
     * A hop is already handled: {@code ShardTransfer.connect} un-fuses before the handoff and
     * {@code ShardSync.onDepartingDeath} cancels the death outright while the player is marked transferring. A KICK
     * or a TIMEOUT carries neither guard, so the death lands, and the player then comes back through the vault,
     * frequently on a different shard, where the grave standing on the origin is unreachable and is discarded by the
     * expiry sweep minutes later. Keeping the whole inventory on the snapshot instead costs nothing and loses
     * nothing: {@code ShardPayload.capture} keeps the tag for a DYING player on purpose, so it travels with them,
     * and {@link #consumeSnapshot} hands it all back wherever they next come back to life. It also leaves one fewer
     * copy of their items behind on the origin, which is the duplication half of the same tracker cluster.
     *
     * <p>Deliberately narrow. It asks for BOTH a disconnect in progress and a live DMZ fusion, so an ordinary combat
     * logout kill still entombs exactly as it did: that grave is a punishment somebody chose, and this one is not.
     */
    private static boolean fusionTeardownOnTheWayOut(ServerPlayer player)
    {
        if (!player.hasDisconnected())
            return false;
        // Both halves of DMZ's status are read: the kill runs BEFORE the teardown clears them, so either being set
        // is enough, and neither is trusted to be the one still standing.
        return DmzBridge.isFused(player) || DmzBridge.fusionPartnerUUID(player) != null;
    }

    /**
     * Deal with a snapshot a player is ALREADY carrying when a death starts, before anything else in
     * {@link #onDeath} looks at them.
     *
     * <p>A snapshot present at the START of a death belongs to an EARLIER life. It is not a same-death double fire:
     * LivingEntity#die guards against re-entry, so a genuine death never reaches here twice. The only ways a player
     * still holds one are the cross-server vault (it copies the whole Forge persistent data, and the arrival
     * force-kill grace can revive a player captured mid-death, so they come back ALIVE with the tag set and no
     * respawn/Clone to consume it) and a death that lands inside the revive check's window, before that check is due.
     *
     * <p>It is never DISCARDED, whatever the gamerule says. Discarding is only harmless when the player still holds
     * the items the snapshot describes, and in the case that actually happens live they hold NOTHING: the snapshot is
     * the only copy of their inventory, so throwing it away is the permanent item loss itself, and the "fresh grave
     * for this death" it used to build was a grave full of nothing.
     *
     * <p>Two branches, both ending with the items back in the player's hands after they respawn:
     * <ul>
     *   <li>Rule ON: consume it NOW, so the items are on the player when the snapshot/grave for THIS death is taken
     *       a few lines later and ride forward into it. Whatever they were already carrying is kept alongside (see
     *       {@link #consumeSnapshot}), so this can neither lose the current life's items nor double the old ones.</li>
     *   <li>Rule OFF: leave it exactly where it is. SU builds no grave at all here, and Clone is NOT gated on the
     *       gamerule, so the respawn consumes it and hands everything back. Consuming it here instead would load
     *       the items into a corpse and let vanilla's dropAll scatter them at a death spot (the Otherworld, usually)
     *       that the player cannot return to.</li>
     * </ul>
     */
    private static void handleCarriedSnapshotAtDeath(ServerPlayer player)
    {
        if (!player.getPersistentData().contains(SNAPSHOT_TAG))
            return;
        // This death owns the snapshot from here on, by one route or the other, so the delayed revive check must
        // stand down. Without this it could fire while the player is on the respawn screen and be skipped anyway,
        // but cancelling it explicitly keeps "exactly one consumer" true by construction rather than by timing.
        PENDING_REVIVE_CHECK.remove(player.getUUID());

        if (!KeepPartialInventory.isEnabled(player))
        {
            LoggingHandler.sulog.info("[Grave] {} died carrying an earlier life's keep-inventory snapshot where "
                    + "KeepPartialInventory is off; leaving it for their respawn to restore.",
                    player.getGameProfile().getName());
            return;
        }

        int restored = consumeSnapshot(player, player.getPersistentData(), "carried into a new death");
        if (restored >= 0)
            LoggingHandler.sulog.warn("[Grave] {} died carrying an earlier life's keep-inventory snapshot ({} "
                    + "stacks). Put back on them first so this death's grave/snapshot carries it forward instead of "
                    + "voiding it.", player.getGameProfile().getName(), restored);
    }

    // full keep: record every slot + total XP, then CLEAR the live inventory so vanilla's death drop finds
    // nothing. Re-applied wholesale in Clone.
    private static void snapshotFull(ServerPlayer player)
    {
        Inventory inv = player.getInventory();
        CompoundTag snap = new CompoundTag();
        snap.putInt("mode", MODE_FULL);
        snap.put("inv", inv.save(new ListTag()));
        snap.putInt("xp", player.totalExperience);
        player.getPersistentData().put(SNAPSHOT_TAG, snap);
        clearAll(inv);
    }

    // empty every backing list so vanilla's post-death dropAll spawns nothing
    private static void clearAll(Inventory inv)
    {
        clearList(inv.items);
        clearList(inv.armor);
        clearList(inv.offhand);
    }

    private static void clearList(NonNullList<ItemStack> list)
    {
        for (int i = 0; i < list.size(); i++)
            list.set(i, ItemStack.EMPTY);
    }

    // partial keep (level >= threshold): keep hotbar 0-8, offhand, armor; move main-inv 9-35 + XP into a grave.
    // Kept slots re-applied in Clone; moved slots cleared here so the grave is sole owner and vanilla can't re-drop.
    private static void snapshotPartial(ServerPlayer player, List<ItemStack> balls)
    {
        Inventory inv = player.getInventory();

        // kept: hotbar 0-8 + armor + offhand, saved with vanilla slot ids so Clone restores verbatim via load()
        ListTag kept = new ListTag();
        for (int i = 0; i <= 8; i++)
            saveSlot(kept, inv.items, i, i);
        for (int i = 0; i < inv.armor.size(); i++)
            saveSlot(kept, inv.armor, i, 100 + i);
        for (int i = 0; i < inv.offhand.size(); i++)
            saveSlot(kept, inv.offhand, i, 150 + i);

        // grave portion: main inventory 9-35
        List<ItemStack> graveItems = new ArrayList<>();
        for (int i = 9; i < inv.items.size(); i++)
        {
            ItemStack stack = inv.items.get(i);
            if (!stack.isEmpty())
            {
                graveItems.add(stack.copy());
                inv.items.set(i, ItemStack.EMPTY); // grave owns it now
            }
        }

        // the balls ride along in the same grave, so one totem stands at the death spot rather than two
        graveItems.addAll(balls);

        int xp = player.totalExperience;
        ServerLevel level = player.serverLevel();
        BlockPos deathPos = player.blockPosition();
        try
        {
            GraveManager.createGrave(level, deathPos, player.getGameProfile(), graveItems, xp);
        }
        catch (Throwable t)
        {
            // grave placement must never crash the death: on failure hand items back to the player (restored in
            // Clone) instead of voiding them, and keep the XP too
            LoggingHandler.sulog.error("[Grave] Failed to place grave for {} at {} - keeping items on player: {}",
                    player.getGameProfile().getName(), deathPos, t.toString());
            // the balls are the exception: handing those back would let a death keep a set, so they go to their own
            // totem instead, which falls back to the ground on its own if that fails too.
            graveItems.removeAll(balls);
            for (int i = 9; i < inv.items.size() && !graveItems.isEmpty(); i++)
                if (inv.items.get(i).isEmpty())
                    inv.items.set(i, graveItems.remove(0));
            snapshotFull(player);
            DragonBallTotem.entomb(player, balls);
            return;
        }

        CompoundTag snap = new CompoundTag();
        snap.putInt("mode", MODE_PARTIAL);
        snap.put("inv", kept);
        // XP went to the grave, so it is NOT restored on the clone
        player.getPersistentData().put(SNAPSHOT_TAG, snap);

        // slots 9-35 already emptied; clear the kept slots too (safe in `kept`, restored in Clone) so vanilla's
        // dropAll spawns nothing
        clearAll(inv);
    }

    private static void saveSlot(ListTag out, NonNullList<ItemStack> list, int index, int slotId)
    {
        if (index < 0 || index >= list.size())
            return;
        ItemStack stack = list.get(index);
        if (stack.isEmpty())
            return;
        CompoundTag tag = new CompoundTag();
        tag.putByte("Slot", (byte) slotId);
        stack.save(tag);
        out.add(tag);
    }

    // cancel vanilla XP orbs while the rule is on; we keep/relocate XP ourselves
    @SubscribeEvent
    public static void onXpDrop(LivingExperienceDropEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player && KeepPartialInventory.isEnabled(player))
            event.setCanceled(true);
    }

    /* Back to life: the one consumer, and its two callers                */

    /**
     * Put a keep-inventory snapshot back on a player, and consume it. The ONE place a snapshot is ever applied.
     *
     * <p>Both routes back to life call this: {@link #onClone} for a respawn (source = the OLD player's persistent
     * data, target = the fresh clone), and {@link #tryConsumeOnRevive} for a REVIVE, where source and target are the
     * same living player. There is no third route, which is what makes "consumed exactly once" checkable.
     *
     * <h2>Why a revive needs this at all</h2>
     * A cross-shard hop taken while dead carries the snapshot to the destination on purpose (ShardPayload keeps the
     * tag for a dying player precisely so the destination's respawn can restore it). But the destination also grants
     * DMZ's force-kill grace on arrival (ShardSync.grantArrivalDeathGrace), and inside that window DMZ's regen can
     * lift the arriving player back above zero health. That is a REVIVE, not a respawn: no PlayerEvent.Clone fires,
     * so nothing consumed the snapshot, and the player is walking around alive holding an empty inventory while the
     * only copy of their items sits in a tag. 300 hops into the Otherworld in three days ran through that window.
     *
     * <h2>Why it cannot double</h2>
     * The tag is removed from the source (and from the target, when they differ) BEFORE a single item moves, so a
     * second caller finds nothing to apply. Items are never MERGED into the snapshot's own slots either: Inventory's
     * load() is given the snapshot verbatim.
     *
     * <h2>Why anything already held is kept</h2>
     * Inventory#load clears all three backing lists before it writes, so loading over a non-empty inventory would
     * DELETE whatever was in it. Both normal callers hand us an empty inventory (a fresh respawn clone; an arrival
     * whose inventory CharacterSlots.applyActive already cleared), so the list below is normally empty and this is
     * exactly the old load(). It stops being empty in the cases that matter: a revive where the player picked
     * something up in the seconds before the check, and a respawn under vanilla keepInventory, where the clone
     * inherits a full inventory that the bare load() used to wipe.
     *
     * @return the number of stacks restored from the snapshot, or -1 when there was no snapshot to consume.
     */
    public static int consumeSnapshot(ServerPlayer target, CompoundTag source, String reason)
    {
        if (target == null || source == null || !source.contains(SNAPSHOT_TAG))
            return -1;

        CompoundTag snap = source.getCompound(SNAPSHOT_TAG).copy();
        source.remove(SNAPSHOT_TAG);
        CompoundTag targetData = target.getPersistentData();
        if (targetData != source)
            targetData.remove(SNAPSHOT_TAG); // belt to the braces: the clone must not inherit a second copy

        ListTag saved = snap.getList("inv", 10); // 10 = CompoundTag
        Inventory inv = target.getInventory();

        List<ItemStack> alreadyHeld = new ArrayList<>();
        collectNonEmpty(inv.items, alreadyHeld);
        collectNonEmpty(inv.armor, alreadyHeld);
        collectNonEmpty(inv.offhand, alreadyHeld);

        inv.load(saved);

        for (ItemStack stack : alreadyHeld)
        {
            if (stack.isEmpty())
                continue;
            // add() mutates the stack and reports whether it took ALL of it; whatever would not fit goes on the
            // floor at the player's feet rather than being silently dropped from existence.
            if (!inv.add(stack) && !stack.isEmpty())
                target.drop(stack, false);
        }

        if (snap.getInt("mode") == MODE_FULL)
        {
            int xp = snap.getInt("xp");
            // A respawn clone arrives at zero experience (vanilla clears it on death), so the snapshot is the only
            // copy and is applied verbatim. A revived player still HAS the experience they died with (the vault
            // captured and restored it), and adding the snapshot's copy on top would double it, so only a player
            // sitting at zero is topped up.
            if (xp > 0 && target.totalExperience <= 0)
            {
                target.totalExperience = 0;
                target.experienceLevel = 0;
                target.experienceProgress = 0.0F;
                target.giveExperiencePoints(xp);
            }
        }
        // MODE_PARTIAL: main inv 9-35 stays empty and the XP is not restored; both live in the grave now.

        int restored = saved.size();
        LoggingHandler.sulog.debug("[Grave] Consumed the keep-inventory snapshot for {} ({}): {} stacks, {} kept "
                + "from what they were already holding.", target.getGameProfile().getName(), reason, restored,
                alreadyHeld.size());
        return restored;
    }

    private static void collectNonEmpty(NonNullList<ItemStack> list, List<ItemStack> out)
    {
        for (int i = 0; i < list.size(); i++)
            if (!list.get(i).isEmpty())
                out.add(list.get(i).copy());
    }

    /* Respawn: restore the snapshot                                 */

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event)
    {
        if (!event.isWasDeath())
            return;
        if (!(event.getEntity() instanceof ServerPlayer newPlayer))
            return;

        // The respawn half of the invariant. The tag lives on the ORIGINAL's persistent data: Forge carries only the
        // PlayerPersisted subtag across a death clone, not the root, so the snapshot has to be read from there.
        CompoundTag oldData = event.getOriginal().getPersistentData();
        if (!oldData.contains(SNAPSHOT_TAG))
            return;
        // A respawn has taken it, so the revive check has nothing left to do for this player.
        PENDING_REVIVE_CHECK.remove(newPlayer.getUUID());
        consumeSnapshot(newPlayer, oldData, "respawn");
    }

    /* Revive: the other half of the invariant                       */

    /**
     * Ask for a delayed revive check on a player who is carrying a snapshot.
     *
     * <p>Called from the login sweep below and from ShardSync the moment a cross-shard arrival has had its payload
     * applied and its force-kill grace granted. Idempotent on purpose: an arrival is seen by BOTH callers and must
     * still produce exactly one check, and a second request must not push the deadline further out.
     */
    public static void scheduleReviveCheck(ServerPlayer player)
    {
        if (player == null || !player.getPersistentData().contains(SNAPSHOT_TAG))
            return;
        MinecraftServer server = player.getServer();
        if (server == null)
            return;
        PENDING_REVIVE_CHECK.putIfAbsent(player.getUUID(),
                new ReviveCheck(server.getTickCount() + (long) REVIVE_CHECK_DELAY_TICKS, 1));
    }

    // Run every due check. The entry is taken BEFORE the consume, so a check that throws cannot be retried into a
    // second application, and a player who went offline in the meantime simply drops out: their tag rides along in
    // their saved data and the next login schedules a fresh check. A check that consumed NOTHING and has attempts
    // left is put back for another look, which is what covers a player who was only momentarily at zero health.
    private static void drainReviveChecks(MinecraftServer server)
    {
        if (PENDING_REVIVE_CHECK.isEmpty())
            return;
        long now = server.getTickCount();
        List<UUID> due = null;
        for (Map.Entry<UUID, ReviveCheck> entry : PENDING_REVIVE_CHECK.entrySet())
        {
            if (now < entry.getValue().dueTick)
                continue;
            if (due == null)
                due = new ArrayList<>();
            due.add(entry.getKey());
        }
        if (due == null)
            return;
        for (UUID id : due)
        {
            ReviveCheck check = PENDING_REVIVE_CHECK.remove(id);
            if (check == null)
                continue;
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null)
                continue;
            if (!tryConsumeOnRevive(player) && check.attempt < MAX_REVIVE_CHECKS)
                PENDING_REVIVE_CHECK.put(id,
                        new ReviveCheck(now + (long) REVIVE_CHECK_DELAY_TICKS, check.attempt + 1));
        }
    }

    /**
     * The revive decision, taken once the force-kill grace has had its say.
     *
     * <h2>The one case this must not touch</h2>
     * A player who is legitimately DEAD right now: on the respawn screen, or still inside a death this server has
     * not finished. Their snapshot belongs to {@link #onClone}, which fires the instant they respawn, so restoring
     * it into a corpse here would put the items in an inventory that the respawn is about to replace, and would take
     * the tag Clone needs with it. {@code isDeadOrDying()} is exactly that question (the dead flag, or health at or
     * below zero), and it is also still true for an arrival whose health the regen never managed to lift, which is
     * the case that SHOULD die. Either way the right answer is to stand aside: if they respawn, Clone consumes it,
     * and otherwise this is simply asked again (see {@link #MAX_REVIVE_CHECKS}), because "at zero health" is a
     * reading a perfectly alive player can give for a tick or two, a fused player mid teardown most of all.
     *
     * <h2>The other case this must not touch</h2>
     * A player object whose session is already over. During {@code PlayerList.remove} the leaving player is STILL in
     * the player list (Forge fires {@code PlayerLoggedOutEvent} as that method's first statement, before the save and
     * before the list removal), and the same is true of the stale half of a ghost login, the "you are already
     * connected to this proxy" state behind tracker #918. Consuming into such a body would delete the tag and hand
     * the items to an inventory nobody will ever see again. {@code hasDisconnected()} is set by
     * {@code ServerPlayer.disconnect()} one call before that event, so it is true for the whole window.
     *
     * @return true when there is nothing further to do for this player (consumed, already taken, or gone), false
     *         when the answer was "not yet" and the check is worth repeating.
     */
    private static boolean tryConsumeOnRevive(ServerPlayer player)
    {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(SNAPSHOT_TAG))
            return true; // a respawn got there first, which is the other half of the invariant doing its job
        if (player.isRemoved() || player.hasDisconnected())
            return true; // their tag rides along in their saved data; the next login schedules a fresh check
        if (player.isDeadOrDying())
            return false; // dead for now: Clone takes it if they respawn, and a transient zero heals by the retry

        int restored = consumeSnapshot(player, data, "revive");
        if (restored >= 0)
            LoggingHandler.sulog.warn("[Grave] Recovered {} item stacks for {}: they were revived (not respawned) "
                    + "while carrying a keep-inventory snapshot, so nothing had given their inventory back.",
                    restored, player.getGameProfile().getName());
        return true;
    }

    /**
     * Consume a snapshot from a LIVING player who is about to be written into the cross-server vault.
     *
     * <p>Called by ShardSync from every capture site, before the capture runs. {@code ShardPayload.capture} drops
     * the tag for any player who is not dead or dying, so without this a player revived on arrival who hops (or is
     * simply saved) before their revive check is due would have the only copy of their inventory dropped on the
     * floor of the vault. The test below is the exact inverse of ShardPayload's, so what it consumes is precisely
     * what that would have discarded, and a DYING player's snapshot is left alone to travel as designed.
     */
    public static void settleBeforeCapture(ServerPlayer player)
    {
        if (player == null || !player.getPersistentData().contains(SNAPSHOT_TAG))
            return;
        if (player.isDeadOrDying() || player.isRemoved())
            return;
        PENDING_REVIVE_CHECK.remove(player.getUUID());

        int restored = consumeSnapshot(player, player.getPersistentData(), "before a vault capture");
        if (restored >= 0)
            LoggingHandler.sulog.warn("[Grave] Recovered {} item stacks for {}: they were alive and still carrying a "
                    + "keep-inventory snapshot when the vault came to save them, which would have dropped it.",
                    restored, player.getGameProfile().getName());
    }

    /**
     * Login sweep: catch anybody who is ALREADY stranded.
     *
     * <p>A fix at the arrival does nothing for a player who was revived days ago and has been walking around empty
     * handed since: their arrival is long past and the only thing left of it is the snapshot still sitting in their
     * persistent data (the root Forge data is saved with the player, so it survives logouts indefinitely). Every
     * login schedules the same delayed check the arrival does, so one relog gives them everything back.
     *
     * <p>LOWEST on purpose: the cross-shard vault applies its payload at HIGHEST and that payload is what WRITES the
     * tag on an arrival, so running before it would see nothing on exactly the players this is for. The check itself
     * is delayed regardless, so the ordering only decides whether it gets scheduled at all.
     *
     * <p>A player who logged out DEAD (quit on the respawn screen) logs back in dead and carrying their snapshot,
     * which is legitimate and must not be consumed. Nothing special is done for them here: the check is scheduled,
     * and {@link #tryConsumeOnRevive} declines because they are still dead. Their respawn takes it through Clone.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
            scheduleReviveCheck(player);
    }

    /* Interaction: open on right-click, drop on break               */

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        if (!(player.level() instanceof ServerLevel level))
            return;

        BlockPos clicked = event.getPos();
        GraveStorage storage = GraveStorage.get(level);
        // may click the fence (grave pos) or the head one above it
        BlockPos gravePos = storage.has(clicked) ? clicked
                : (storage.has(clicked.below()) ? clicked.below() : null);
        if (gravePos == null)
            return;

        event.setCanceled(true); // don't let vanilla open/place anything on our grave blocks
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        GraveManager.open(player, level, gravePos);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event)
    {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        BlockPos pos = event.getPos();
        BlockState state = event.getState();
        // early-out: only fences/heads can be a grave block
        if (!state.is(Blocks.OAK_FENCE) && !state.is(Blocks.PLAYER_HEAD))
            return;

        GraveStorage storage = GraveStorage.get(level);
        boolean isGrave = storage.has(pos) || storage.has(pos.below());
        if (!isGrave)
            return;

        // Keep the totem UNbreakable inside spawn protection (or outside the world border). The "open at spawn"
        // exemption lives in core.mixin.server.MixinServerLevelGraveInteract, which makes ServerLevel#mayInteract
        // return true at a grave the player may open. That inject fires for every caller of mayInteract, including
        // ServerPlayerGameMode#handleBlockBreakAction, so without this guard an eligible player (owner, or anyone at a
        // dragon ball totem) could BREAK the fence / head at spawn, which vanilla spawn protection would otherwise stop
        // and which this handler would then happily clean up and let through. Re-check spawn protection directly (the
        // inject does not touch isUnderSpawnProtection) and cancel the break so only the OPEN is ever exempted.
        MinecraftServer server = level.getServer();
        if (server != null
                && (server.isUnderSpawnProtection(level, pos, event.getPlayer())
                    || !level.getWorldBorder().isWithinBounds(pos)))
        {
            event.setCanceled(true);
            return;
        }

        // drop contents + XP and clean up, then let the break proceed
        GraveManager.breakGrave(level, pos);
    }
}
