package net.shurui.shuruisutilities.timemachine;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import net.shurui.shuruisutilities.core.config.PublicContent;
import net.shurui.shuruisutilities.hoverbike.HoverbikeItems;
import net.shurui.shuruisutilities.hoverbike.PacketHoverbikeToggle;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Deploy / recall / space-carry machinery for the SU-owned {@link TimeMachineEntity}, driven from the SHARED
 * "hoverbike" curios slot and toggle keybind (dispatched by
 * {@link net.shurui.shuruisutilities.hoverbike.PacketHoverbikeToggle}). A close mirror of {@code SpacePodDeploy},
 * but for our own entity instead of DMZ's pod, so there is no Throwable-guarding of DMZ internals here.
 *
 * <h3>The curios rule (commit 98c02141), inherited exactly</h3>
 * The chip NEVER leaves the curios slot. Deploy spawns the entity and records it; recall despawns it; a lost entity
 * costs NOTHING because the chip is still equipped and the player simply deploys again. Every hand-back path is
 * therefore deliberately absent: there is no code anywhere that returns a chip to the player, because with the chip
 * staying put each such path would be a dupe source.
 *
 * <h3>Shard behaviour</h3>
 * The chip rides the player's curios in the vault, and {@link TimeMachineDeployData} rides the player's Forge
 * persistent data in the vault, so both cross a shard hop. The deployed ENTITY does not: it is a per-level thing on
 * one server. That is exactly the case the curios rule is built for: after a hop {@link #findAcrossLevels} cannot see
 * an entity that is on another shard (it scans only THIS server's loaded levels, indistinguishable from destroyed),
 * so recall's not-found branch just clears the stale record and the player re-deploys. No item is ever at risk.
 * Space travel that crosses shards is routed through the pod's own {@link net.shurui.shuruisutilities.world.space.SpaceHandoff}
 * (a time-machine flag on the same record), never a second mechanism.
 */
public final class TimeMachineDeploy
{
    private TimeMachineDeploy() {}

    static final String SLOT = "hoverbike"; // shared curios slot, registered by the hoverbike datapack

    // The time machine follows the space pod's public gate (see PublicContent.FEATURE_TIME_MACHINE). The operator
    // switchboard was removed in batch M, so that public feature is the only gate now.
    public static boolean allowed()
    {
        return PublicContent.allows(PublicContent.FEATURE_TIME_MACHINE);
    }

    public static ItemStack chipStack()
    {
        return new ItemStack(HoverbikeItems.TIME_MACHINE_CHIP.get());
    }

    // if the shared slot holds a TimeMachineChipItem, spawn our TimeMachineEntity, mark owner, record the deploy and
    // seat the pilot. The chip STAYS in the slot (the item-loss fix). Else tell the player they have none equipped.
    public static void deploy(ServerPlayer player)
    {
        ItemStack slotStack = CuriosApi.getCuriosInventory(player)
                .map(h -> h.findCurio(SLOT, 0).map(SlotResult::stack).orElse(ItemStack.EMPTY))
                .orElse(ItemStack.EMPTY);

        if (!(slotStack.getItem() instanceof TimeMachineChipItem))
        {
            ChatOutputHandler.chatError(player, "No time machine equipped.");
            return;
        }

        Level level = player.level();
        TimeMachineEntity machine = trySpawn(level, player);
        if (machine == null)
        {
            ChatOutputHandler.chatError(player, "Not enough room to deploy the time machine here.");
            return;
        }

        machine.setOwnerUUID(player.getUUID());
        level.addFreshEntity(machine);
        level.gameEvent(player, GameEvent.ENTITY_PLACE, machine.position());
        try
        {
            player.startRiding(machine, true);
        }
        catch (Throwable t)
        {
            // leave the pilot standing beside it; the record below still lets the toggle recall it.
        }
        TimeMachineDeployData.set(player, machine.getUUID());
        ChatOutputHandler.chatConfirmation(player, "Time machine deployed.");
    }

    // same placement search as the hoverbike/pod (feet, +/-1, then ahead along yaw), reusing the block-only clear
    // test so the deploying player's own body cannot block the spawn.
    private static TimeMachineEntity trySpawn(Level level, ServerPlayer player)
    {
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        float yawRad = player.getYRot() * ((float) Math.PI / 180F);
        double lookX = -Mth.sin(yawRad);
        double lookZ = Mth.cos(yawRad);
        double[][] candidates = {
                { px, py, pz },
                { px, py + 1.0D, pz },
                { px, py - 1.0D, pz },
                { px + lookX, py, pz + lookZ },
                { px + lookX * 2.0D, py, pz + lookZ * 2.0D },
        };
        for (double[] c : candidates)
        {
            TimeMachineEntity machine = new TimeMachineEntity(level, c[0], c[1], c[2]);
            machine.setYRot(player.getYRot());
            if (PacketHoverbikeToggle.blocksClear(level, machine))
                return machine;
        }
        return null;
    }

    // find the machine across ALL loaded levels; if found, eject and discard it, then clear the record. The chip
    // never leaves the slot, so recall puts nothing back. Not found means unloaded chunk OR another shard (see the
    // class note): clear the stale record and let the player deploy again; do NOT recurse into deploy.
    public static void recall(ServerPlayer player)
    {
        UUID uuid = TimeMachineDeployData.deployedUUID(player);
        TimeMachineEntity machine = uuid != null ? findAcrossLevels(player.getServer(), uuid) : null;
        if (machine != null)
        {
            machine.ejectPassengers();
            machine.discard();
            TimeMachineDeployData.clear(player);
            ChatOutputHandler.chatConfirmation(player, "Time machine recalled.");
            return;
        }
        LoggingHandler.sulog.info("[TimeMachine] {} recalled a machine whose entity ({}) is not in any loaded level "
                + "on this server (unloaded chunk or another shard); clearing the record. The chip stayed in the slot, "
                + "so a new deploy is free.", player.getGameProfile().getName(), uuid);
        TimeMachineDeployData.clear(player);
        ChatOutputHandler.chatConfirmation(player, "Time machine recalled.");
    }

    public static TimeMachineEntity findAcrossLevels(MinecraftServer server, UUID uuid)
    {
        if (server == null || uuid == null)
            return null;
        for (ServerLevel level : server.getAllLevels())
        {
            Entity found = level.getEntity(uuid);
            if (found instanceof TimeMachineEntity machine)
                return machine;
        }
        return null;
    }

    // death/respawn handler: despawn a machine still out in the world so respawn leaves no orphan, and clear the
    // record so the next toggle deploys fresh. Restores NO item (the chip never left the slot). No-op if none out.
    public static void despawnDeployedOnRespawn(ServerPlayer player)
    {
        if (!TimeMachineDeployData.hasDeployed(player))
            return;
        UUID uuid = TimeMachineDeployData.deployedUUID(player);
        TimeMachineEntity machine = findAcrossLevels(player.getServer(), uuid);
        if (machine != null)
        {
            machine.ejectPassengers();
            machine.discard();
        }
        TimeMachineDeployData.clear(player);
    }

    // the time machine the player is riding (directly or nested), or null. Walks the whole vehicle chain.
    public static TimeMachineEntity riding(ServerPlayer player)
    {
        for (Entity vehicle = player.getVehicle(); vehicle != null; vehicle = vehicle.getVehicle())
        {
            if (vehicle instanceof TimeMachineEntity machine)
                return machine;
        }
        return null;
    }

    // ENTERING space aboard the machine, called from SpaceTravelModule.enterSpace at the already-computed arrival
    // point. A cross-dimension teleport dismounts the rider and leaves the entity behind, so we dismount, teleport
    // the player, spawn a FRESH machine in space under them and reseat, repoint the record, and discard the origin
    // one. Because a lost machine costs nothing, a failure just leaves the pilot in space on foot to re-deploy.
    public static void enterSpaceAboard(ServerPlayer player, ServerLevel space, TimeMachineEntity oldMachine,
            double x, double y, double z, float yaw)
    {
        try
        {
            player.stopRiding();
            player.teleportTo(space, x, y, z, yaw, player.getXRot());
            TimeMachineEntity fresh = new TimeMachineEntity(space, x, y, z);
            fresh.setYRot(yaw);
            fresh.setOwnerUUID(player.getUUID());
            space.addFreshEntity(fresh);
            player.startRiding(fresh, true);
            TimeMachineDeployData.set(player, fresh.getUUID());
        }
        catch (Throwable t)
        {
            // arrived in space on foot; the chip is safe in the slot, so the toggle re-deploys.
        }
        finally
        {
            oldMachine.discard();
        }
    }

    // Bring the machine the player is RIDING through the cross-dimension teleport that is about to happen (landing or
    // return). Called at the TOP of SpaceTravelModule.carryPodThroughTeleport, which every space-exit path invokes
    // immediately BEFORE its teleport. No-op unless the player is riding a machine. The teleport has not run yet, so
    // the rebuild is queued for the tick's task drain, by which point the player stands at their destination.
    public static void carryThroughTeleport(ServerPlayer player)
    {
        TimeMachineEntity ridden = riding(player);
        if (ridden == null)
            return;
        ridden.ejectPassengers();
        ridden.discard();
        MinecraftServer server = player.getServer();
        if (server != null)
            server.execute(() -> redeployAfterTeleport(player));
    }

    // second half of carryThroughTeleport: build a machine where the player now stands and reseat them.
    private static void redeployAfterTeleport(ServerPlayer player)
    {
        if (player.isRemoved() || !(player.level() instanceof ServerLevel dest))
            return;
        // only if the player still owns a chip in the slot (a death between the two halves could have dropped it).
        ItemStack slotStack = CuriosApi.getCuriosInventory(player)
                .map(h -> h.findCurio(SLOT, 0).map(SlotResult::stack).orElse(ItemStack.EMPTY))
                .orElse(ItemStack.EMPTY);
        if (!(slotStack.getItem() instanceof TimeMachineChipItem))
        {
            TimeMachineDeployData.clear(player);
            return;
        }
        TimeMachineEntity fresh = new TimeMachineEntity(dest, player.getX(), player.getY(), player.getZ());
        fresh.setYRot(player.getYRot());
        fresh.setOwnerUUID(player.getUUID());
        dest.addFreshEntity(fresh);
        player.startRiding(fresh, true);
        TimeMachineDeployData.set(player, fresh.getUUID());
    }

    // CROSS-SHARD: remove the ridden machine on the ORIGIN side of a shard hop into (or out of) space, returning true
    // when one was removed so SpaceHandoff carries a flag and the arrival side rebuilds it (spawnAfterHandoff). The
    // record is left intact to travel in the vault; the arrival rebuild repoints it at the fresh machine.
    public static boolean discardForHandoff(ServerPlayer player, TimeMachineEntity machine)
    {
        if (machine == null)
            return false;
        try
        {
            player.stopRiding();
            machine.discard();
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    // ARRIVAL side of a shard hop: rebuild a fresh machine under a player who just transferred and reseat them,
    // mirroring spawnHandoffPod. The chip is in the slot regardless, so a failure just leaves them on foot.
    public static void spawnAfterHandoff(ServerPlayer player, ServerLevel level, double x, double y, double z,
            float yaw)
    {
        try
        {
            TimeMachineEntity fresh = new TimeMachineEntity(level, x, y, z);
            fresh.setYRot(yaw);
            fresh.setOwnerUUID(player.getUUID());
            level.addFreshEntity(fresh);
            player.startRiding(fresh, true);
            TimeMachineDeployData.set(player, fresh.getUUID());
        }
        catch (Throwable t)
        {
            // arrived on foot; the record + chip reconcile on the next toggle.
        }
    }
}
