package net.shurui.shuruisutilities.hoverbike;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.dragonminez.common.init.entities.BlackNimbusEntity;
import com.dragonminez.common.init.entities.FlyingNimbusEntity;
import com.dragonminez.common.init.entities.SpacePodEntity;

import net.shurui.shuruisutilities.nimbus.NimbusDeploy;
import net.shurui.shuruisutilities.nimbus.NimbusDeployData;
import net.shurui.shuruisutilities.spacepod.PodDeployData;
import net.shurui.shuruisutilities.spacepod.SpacePodDeploy;
import net.shurui.shuruisutilities.timemachine.TimeMachineDeploy;
import net.shurui.shuruisutilities.timemachine.TimeMachineDeployData;
import net.shurui.shuruisutilities.timemachine.TimeMachineEntity;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Recalls a deployed vehicle (hoverbike, space pod, nimbus, time machine) once its owner goes too far from it.
 *
 * <p>Once a second per online player: find the vehicle their record names and, if they are not riding it and it is
 * more than {@link ConfigHoverbikes#recallDistance} blocks away or in another dimension, run that vehicle's own
 * recall, the same one the toggle key uses. The chip never leaves the shared slot while a vehicle is out, so a recall
 * only removes the entity and clears the record; nothing is handed back and nothing can be duplicated.
 *
 * <p>The condition has to hold for {@link #STRIKES} checks in a row. A space pod landing and a cross dimension carry
 * both move the vehicle and the rider in separate steps, and a single check that happened to fall between them must
 * not throw the pod away mid trip.
 *
 * <p>A vehicle whose chunk has unloaded (the owner teleported or warped far away) is not in memory to discard. If the
 * owner is far from where it was last seen, the record is cleared anyway so they can deploy again, and the leftover is
 * queued in {@link VehicleRecallStore} to be removed when its chunk next loads. Force loading it instead would be a
 * blocking chunk load on the tick thread.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class VehicleRecallWatch
{
    private VehicleRecallWatch() {}

    private static final int CHECK_INTERVAL_TICKS = 20;
    private static final int STRIKES = 3;

    /** Per online player: consecutive "too far" checks and where their vehicle was last seen loaded. */
    private static final Map<UUID, Watch> WATCHES = new HashMap<>();

    private static final class Watch
    {
        UUID vehicle;
        int strikes;
        ResourceKey<Level> lastDimension;
        Vec3 lastPosition;
    }

    private static int tick;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || ++tick % CHECK_INTERVAL_TICKS != 0)
            return;
        int distance = ConfigHoverbikes.recallDistance;
        MinecraftServer server = event.getServer();
        if (distance <= 0 || server == null)
        {
            WATCHES.clear();
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            try
            {
                check(server, player, (double) distance * distance, distance);
            }
            catch (Throwable t)
            {
                LoggingHandler.sulog.debug("[VehicleRecall] check failed for {}: {}",
                        player.getGameProfile().getName(), t.toString());
            }
        }
    }

    private static void check(MinecraftServer server, ServerPlayer player, double maxDistanceSq, int distance)
    {
        UUID vehicleId;
        Entity vehicle;
        String name;
        if (HoverbikeDeployData.hasDeployed(player))
        {
            vehicleId = HoverbikeDeployData.deployedUUID(player);
            vehicle = PacketHoverbikeToggle.findAcrossLevels(server, vehicleId);
            name = "hoverbike";
        }
        else if (PodDeployData.hasDeployed(player))
        {
            vehicleId = PodDeployData.deployedUUID(player);
            vehicle = SpacePodDeploy.findAcrossLevels(server, vehicleId);
            name = "space pod";
        }
        else if (NimbusDeployData.hasDeployed(player))
        {
            vehicleId = NimbusDeployData.deployedUUID(player);
            vehicle = NimbusDeploy.findAcrossLevels(server, vehicleId);
            name = "nimbus";
        }
        else if (TimeMachineDeployData.hasDeployed(player))
        {
            vehicleId = TimeMachineDeployData.deployedUUID(player);
            vehicle = TimeMachineDeploy.findAcrossLevels(server, vehicleId);
            name = "time machine";
        }
        else
        {
            WATCHES.remove(player.getUUID());
            return;
        }
        if (vehicleId == null)
            return;

        Watch watch = WATCHES.computeIfAbsent(player.getUUID(), k -> new Watch());
        if (!vehicleId.equals(watch.vehicle))
        {
            // A different deploy than last check: start its history fresh.
            watch.vehicle = vehicleId;
            watch.strikes = 0;
            watch.lastDimension = null;
            watch.lastPosition = null;
        }

        boolean tooFar;
        if (vehicle != null)
        {
            watch.lastDimension = vehicle.level().dimension();
            watch.lastPosition = vehicle.position();
            if (player.getVehicle() == vehicle || vehicle.hasPassenger(player))
            {
                watch.strikes = 0;
                return;
            }
            tooFar = vehicle.level() != player.level() || vehicle.distanceToSqr(player) > maxDistanceSq;
        }
        else
        {
            // Not loaded anywhere on this server. Only judge it against a sighting from this session: with none (just
            // logged in, just arrived from another shard) there is nothing to measure from, so leave it alone.
            if (watch.lastDimension == null || watch.lastPosition == null)
                return;
            tooFar = !player.level().dimension().equals(watch.lastDimension)
                    || player.position().distanceToSqr(watch.lastPosition) > maxDistanceSq;
        }

        if (!tooFar)
        {
            watch.strikes = 0;
            return;
        }
        if (++watch.strikes < STRIKES)
            return;

        if (vehicle == null)
        {
            VehicleRecallStore store = VehicleRecallStore.get(server);
            if (store != null)
                store.add(vehicleId);
        }
        switch (name)
        {
            case "hoverbike" -> PacketHoverbikeToggle.recall(player);
            case "space pod" -> SpacePodDeploy.recall(player);
            case "nimbus" -> NimbusDeploy.recall(player);
            default -> TimeMachineDeploy.recall(player);
        }
        WATCHES.remove(player.getUUID());
        player.displayClientMessage(Component.literal("Your " + name + " was recalled: you went more than "
                + distance + " blocks from it."), true);
    }

    /**
     * Remove a vehicle recalled while unloaded, as its chunk brings it back in.
     *
     * <p>The owner link is stripped before the discard. Every one of these vehicles treats its own removal as "clear my
     * owner's deploy record", and by now that record may name a NEW vehicle the owner has deployed since; clearing it
     * would orphan the one they are actually using. The discard is deferred one task because a level must not have
     * entities removed from inside its own add.
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event)
    {
        if (!VehicleRecallStore.anyPending || event.getLevel().isClientSide())
            return;
        Entity entity = event.getEntity();
        if (!(entity instanceof HoverbikeEntity) && !(entity instanceof SpacePodEntity)
                && !(entity instanceof TimeMachineEntity) && !(entity instanceof FlyingNimbusEntity)
                && !(entity instanceof BlackNimbusEntity))
            return;
        MinecraftServer server = entity.getServer();
        VehicleRecallStore store = server == null ? null : VehicleRecallStore.get(server);
        if (store == null || !store.take(entity.getUUID()))
            return;
        if (entity instanceof HoverbikeEntity bike)
            bike.setOwnerUUID(null);
        if (entity instanceof TimeMachineEntity machine)
            machine.setOwnerUUID(null);
        entity.getPersistentData().remove(SpacePodDeploy.OWNER_TAG);
        entity.getPersistentData().remove(NimbusDeploy.OWNER_TAG);
        server.execute(() ->
        {
            entity.ejectPassengers();
            entity.discard();
        });
        LoggingHandler.sulog.info("[VehicleRecall] Removed a vehicle ({}) that was recalled while its chunk was unloaded.",
                entity.getUUID());
    }

    /** Load the queue at start, so {@link VehicleRecallStore#anyPending} is right before the first chunk loads a leftover. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event)
    {
        VehicleRecallStore.anyPending = false;
        VehicleRecallStore.get(event.getServer());
        // A deployed vehicle cannot follow its owner to another shard, but the RECORD of it can: it lives in the
        // player's persistent NBT, which the vault carries. Recall it before the hop instead. See onShardHandoff.
        net.shurui.shuruisutilities.shard.PreHopTeardown.register("vehicles", VehicleRecallWatch::onShardHandoff);
    }

    /**
     * A player is about to be handed to another shard: take their deployed vehicle in first.
     *
     * <p>Recall is the right answer of the three on offer (recall, clear the record, hand an item back) because all
     * four vehicles keep their curios item in the slot for the whole time they are out. So despawning the entity and
     * dropping the record costs nothing at all: the player arrives with the item still equipped and deploys again
     * with the same keypress. Clearing the record alone would strand an ownerless vehicle here, and handing an item
     * back would DUPLICATE the one still in their slot.
     *
     * <p>It has to happen before {@code ShardSync.handOff} captures them, because the capture is what decides which
     * copy of the record the destination loads. Left to the logout handler (which only ever dropped the in-memory
     * watch) nothing clears the record at all: the entity stays on this shard, the record travels, and on the far
     * side the watch deliberately stands aside because it has no sighting of its own to measure from. That is
     * ticket #767, a vehicle the owner can see and ride but can never put away.
     *
     * <p>Not done for the pod or the time machine when the SPACE path has already claimed them: that path discards
     * the entity here and rebuilds a fresh one under the player on arrival ({@code SpaceHandoff.pod} /
     * {@code timeMachine}), which is a better outcome than arriving on foot, and it stamps its own record on the far
     * side. Every other hop (a {@code /server}, a route, a teleport, a fallback) has no such arrangement.
     *
     * <p>If the hop then fails and the player is reclaimed here, they are standing where they were with their
     * vehicle put away and its item in the slot. That is the mildest of the four stalled-hop outcomes and needs one
     * keypress to undo.
     */
    public static void onShardHandoff(ServerPlayer player)
    {
        WATCHES.remove(player.getUUID());
        if (HoverbikeDeployData.hasDeployed(player))
        {
            PacketHoverbikeToggle.recall(player);
        }
        else if (PodDeployData.hasDeployed(player))
        {
            if (spaceIsCarrying(player, true))
                return;
            SpacePodDeploy.recall(player);
        }
        else if (NimbusDeployData.hasDeployed(player))
        {
            NimbusDeploy.recall(player);
        }
        else if (TimeMachineDeployData.hasDeployed(player))
        {
            if (spaceIsCarrying(player, false))
                return;
            TimeMachineDeploy.recall(player);
        }
        else
        {
            return;
        }
        player.displayClientMessage(Component.literal(
                "Your vehicle was put away for the server change. Deploy it again when you arrive."), true);
    }

    // True when a space handoff on this player has already taken charge of this vehicle and will rebuild it on the
    // far side. Read through SpaceHandoff so there is one source of truth for what the space path promised.
    private static boolean spaceIsCarrying(ServerPlayer player, boolean pod)
    {
        if (!net.shurui.shuruisutilities.world.space.SpaceHandoff.isPending(player))
            return false;
        // a carried DMZ saiyan ship is not the deployed SU pod this record names, so it does not excuse its recall.
        return pod
                ? net.shurui.shuruisutilities.world.space.SpaceHandoff.pod(player)
                        && !net.shurui.shuruisutilities.world.space.SpaceHandoff.podShip(player)
                : net.shurui.shuruisutilities.world.space.SpaceHandoff.timeMachine(player);
    }

    /**
     * Arrival correction: drop a deploy record that was written on a DIFFERENT server.
     *
     * <p>The pre-hop recall above covers every departure that goes through {@code ShardTransfer.connect}. A timeout,
     * a kick and a proxy fallback do not, and they cannot be told apart from a quit while they are happening, so the
     * record still travels in those cases. Here it can be answered from the other end: a record stamped with another
     * server's id is about a vehicle that is definitely not in this world, which is the one thing
     * {@link #check} can never conclude on its own ("not loaded" also means "unloaded chunk"). Clearing it frees the
     * toggle keybind; the item never left the player's slot, so nothing is lost.
     *
     * <p>Unstamped records (deployed before this shipped, or on a server with no shard id) are left exactly as they
     * were.
     */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        String name = null;
        if (HoverbikeDeployData.deployedElsewhere(player))
        {
            HoverbikeDeployData.clear(player);
            name = "hoverbike";
        }
        else if (PodDeployData.deployedElsewhere(player))
        {
            PodDeployData.clear(player);
            name = "space pod";
        }
        else if (NimbusDeployData.deployedElsewhere(player))
        {
            NimbusDeployData.clear(player);
            name = "nimbus";
        }
        else if (TimeMachineDeployData.deployedElsewhere(player))
        {
            TimeMachineDeployData.clear(player);
            name = "time machine";
        }
        if (name == null)
            return;
        WATCHES.remove(player.getUUID());
        LoggingHandler.sulog.info("[VehicleRecall] Cleared {}'s {} deploy record: it was written on another server, "
                + "so the vehicle it names cannot be here. Their item never left the slot.",
                player.getGameProfile().getName(), name);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event)
    {
        WATCHES.remove(event.getEntity().getUUID());
    }
}
