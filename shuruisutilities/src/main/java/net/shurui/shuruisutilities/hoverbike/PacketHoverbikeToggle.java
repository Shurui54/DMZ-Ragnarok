package net.shurui.shuruisutilities.hoverbike;

import java.util.UUID;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.core.config.PublicContent;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkEvent;

import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

// client->server, empty payload: the toggle-hoverbike keybind. recalls a deployed bike into the slot, or spawns
// the slotted bike at the player if none deployed. state is server-side (HoverbikeDeployData + the curios slot).
public class PacketHoverbikeToggle implements ISUPacket
{
    private static final String SLOT = "hoverbike"; // curios slot, registered by datapack

    public PacketHoverbikeToggle() {}

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        // no payload: the packet is the toggle signal
    }

    public static PacketHoverbikeToggle decode(FriendlyByteBuf buf)
    {
        return new PacketHoverbikeToggle();
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
            return;

        // While racing, this toggle must never deploy or recall a personal bike (the racer is on a race bike the
        // engine owns). Keyless the hook default answers false, so a non-racing server is unaffected.
        if (net.shurui.shuruisutilities.api.key.RaceHooks.get().isRacing(player))
            return;

        // A summoned cosmetic mount stands in for a vehicle (see deploy), so the same toggle recalls it. Checked
        // first, before the three vehicle deploy records, because a replaced summon set none of them: the mount is
        // the only thing out. Also catches a mount summoned by /cosmetic, keeping one vehicle-or-mount per player.
        if (net.shurui.shuruisutilities.cosmetics.wardrobe.mount.CosmeticMountManager.isActive(player))
        {
            net.shurui.shuruisutilities.cosmetics.wardrobe.mount.CosmeticMountManager.recall(player);
            return;
        }

        // No single top gate any more. The shared slot can hold a hoverbike, a space pod chip or a nimbus chip, and
        // the three no longer share one licence question: hoverbikes stay key-gated, the pod and the nimbus went
        // public on 2026-08-30. A single gate that asked only about hoverbikes would refuse a keyless player their
        // now-public pod, and (once hoverbikes are switched off by an operator) would also refuse a pod holder for a
        // reason that has nothing to do with the pod. So each branch below asks about the thing it actually drives.
        //
        // the "hoverbike" slot is shared, so this one toggle drives whichever vehicle is deployed/equipped. recall
        // whatever is out first; the three deploy records are mutually exclusive because deploying always empties the
        // shared slot, so at most one of them is ever set.
        if (HoverbikeDeployData.hasDeployed(player))
        {
            if (!hoverbikeAllowed())
                return;
            recall(player);
            return;
        }
        if (net.shurui.shuruisutilities.spacepod.PodDeployData.hasDeployed(player))
        {
            if (!spacePodAllowed())
                return;
            net.shurui.shuruisutilities.spacepod.SpacePodDeploy.recall(player);
            return;
        }
        if (net.shurui.shuruisutilities.nimbus.NimbusDeployData.hasDeployed(player))
        {
            if (!nimbusAllowed())
                return;
            net.shurui.shuruisutilities.nimbus.NimbusDeploy.recall(player);
            return;
        }
        if (net.shurui.shuruisutilities.timemachine.TimeMachineDeployData.hasDeployed(player))
        {
            if (!net.shurui.shuruisutilities.timemachine.TimeMachineDeploy.allowed())
                return;
            net.shurui.shuruisutilities.timemachine.TimeMachineDeploy.recall(player);
            return;
        }
        deploy(player);
    }

    // Hoverbikes are public, and the operator switchboard was removed in batch M, so they are always allowed.
    private static boolean hoverbikeAllowed()
    {
        return true;
    }

    // The space pod went public on 2026-08-30, so it asks PublicContent for its named feature (true on every tier)
    // rather than the key gate. The operator switchboard was removed in batch M, so that is the only gate now.
    private static boolean spacePodAllowed()
    {
        return PublicContent.allows(PublicContent.FEATURE_SPACE_POD);
    }

    // The flying nimbus went public alongside the pod; same public feature gate.
    private static boolean nimbusAllowed()
    {
        return PublicContent.allows(PublicContent.FEATURE_NIMBUS);
    }

    // look up the deployed bike across ALL levels; if found, eject and discard it, then clear the record. The item
    // NEVER leaves the curios slot (see deploy), so recall does not put anything back: despawning the entity is the
    // whole job. all-levels scan stays because SU's respawn and space travel can strand the bike in a dim other than
    // the one the player is standing in.
    // public so the private racing feature (Ragnarok Key) can recall a racer's personal bike at staging.
    public static void recall(ServerPlayer player)
    {
        UUID uuid = HoverbikeDeployData.deployedUUID(player);

        HoverbikeEntity bike = uuid != null ? findAcrossLevels(player.getServer(), uuid) : null;
        if (bike != null)
        {
            bike.ejectPassengers();
            bike.discard();
            HoverbikeDeployData.clear(player);
            ChatOutputHandler.chatConfirmation(player, "Hoverbike recalled.");
            return;
        }

        // Not in ANY loaded level. This does NOT mean the bike was destroyed: getEntity only finds LOADED entities,
        // so a bike in an unloaded chunk (or on another shard after a hop) is indistinguishable from a gone one. The
        // item is safe in the slot the whole time, so a lost entity costs nothing: clear the stale record and let the
        // player deploy again with the next keypress. We do NOT recurse into deploy() here, and we create no item.
        LoggingHandler.sulog.info("[Hoverbike] {} recalled a bike whose entity ({}) is not in any loaded level "
                + "(unloaded chunk or another shard); clearing the record. The item stayed in the slot, so a new "
                + "deploy is free.", player.getGameProfile().getName(), uuid);
        HoverbikeDeployData.clear(player);
        ChatOutputHandler.chatConfirmation(player, "Hoverbike recalled.");
    }

    // scan every loaded level for a bike with uuid; null if server unavailable or not found
    static HoverbikeEntity findAcrossLevels(net.minecraft.server.MinecraftServer server, UUID uuid)
    {
        if (server == null || uuid == null)
            return null;
        for (ServerLevel level : server.getAllLevels())
        {
            Entity found = level.getEntity(uuid);
            if (found instanceof HoverbikeEntity bike)
                return bike;
        }
        return null;
    }

    // if the "hoverbike" slot holds a HoverbikeItem, spawn a bike, mark owner, record the deploy and clear the
    // slot (the item is now the entity). else tell the player they have none equipped.
    private void deploy(ServerPlayer player)
    {
        ItemStack slotStack = CuriosApi.getCuriosInventory(player)
                .map(h -> h.findCurio(SLOT, 0).map(SlotResult::stack).orElse(ItemStack.EMPTY))
                .orElse(ItemStack.EMPTY);

        // A player with a cosmetic mount equipped rides the mount in place of a TRANSPORT vehicle of the MATCHING
        // KIND, per the owner's request, using the mount's own movement and sounds: a GROUND mount replaces the
        // hoverbike, a FLYING mount replaces the flying nimbus. A kind mismatch (or no usable mount) falls through and
        // deploys the real vehicle unchanged, so a player never loses their vehicle. The replacing mount inherits the
        // vehicle's speed: for the hoverbike that is its per-variant config speed and sprint multiplier; the nimbus is
        // a DMZ mob with no scalar speed, so a flying mount keeps its own configured speed there. The SPACE POD is
        // deliberately NOT here: its purpose is space travel, which a cosmetic mount cannot perform (SpaceTravelModule
        // stamps and rebuilds a real SpacePodEntity and planet entry crosses dimensions in it), so replacing it would
        // strand the pilot. The pod therefore keeps its function and its own visuals and sounds.
        if (slotStack.getItem() instanceof HoverbikeItem hoverChip)
        {
            int variant = Mth.clamp(hoverChip.getVariant(), 1, 4);
            if (net.shurui.shuruisutilities.cosmetics.wardrobe.mount.CosmeticMountManager.summonAsReplacement(
                    player, false, (float) ConfigHoverbikes.speed[variant], (float) ConfigHoverbikes.sprintMultiplier))
                return;
        }
        else if (slotStack.getItem() instanceof net.shurui.shuruisutilities.nimbus.NimbusChipItem)
        {
            if (net.shurui.shuruisutilities.cosmetics.wardrobe.mount.CosmeticMountManager.summonAsReplacement(
                    player, true, 0.0F, 0.0F))
                return;
        }

        // shared slot: a pod chip routes to the pod deploy path instead of the bike path. Gated on the pod's own
        // question so it is never refused because hoverbikes happen to be off.
        if (slotStack.getItem() instanceof net.shurui.shuruisutilities.spacepod.PodChipItem)
        {
            if (!spacePodAllowed())
                return;
            net.shurui.shuruisutilities.spacepod.SpacePodDeploy.deploy(player);
            return;
        }
        // shared slot: a nimbus chip routes to the nimbus deploy path (flying/black chosen by alignment there). Gated
        // on the nimbus's own question, same reason as the pod.
        if (slotStack.getItem() instanceof net.shurui.shuruisutilities.nimbus.NimbusChipItem)
        {
            if (!nimbusAllowed())
                return;
            net.shurui.shuruisutilities.nimbus.NimbusDeploy.deploy(player);
            return;
        }
        // shared slot: a time machine chip routes to the time machine deploy path. Gated on the time machine's own
        // question (public feature + operator switch), same reason as the pod and nimbus.
        if (slotStack.getItem() instanceof net.shurui.shuruisutilities.timemachine.TimeMachineChipItem)
        {
            if (!net.shurui.shuruisutilities.timemachine.TimeMachineDeploy.allowed())
                return;
            net.shurui.shuruisutilities.timemachine.TimeMachineDeploy.deploy(player);
            return;
        }

        if (!(slotStack.getItem() instanceof HoverbikeItem item))
        {
            ChatOutputHandler.chatError(player, "No hoverbike equipped.");
            return;
        }

        // The bike itself is still key-only.
        if (!hoverbikeAllowed())
            return;

        int variant = item.getVariant();
        Level level = player.level();

        HoverbikeEntity bike = trySpawn(level, player, variant);
        if (bike == null)
        {
            ChatOutputHandler.chatError(player, "Not enough room to deploy the hoverbike here.");
            return;
        }

        bike.setOwnerUUID(player.getUUID());
        level.addFreshEntity(bike);
        level.gameEvent(player, GameEvent.ENTITY_PLACE, bike.position());

        HoverbikeDeployData.set(player, bike.getUUID(), variant);
        // The item STAYS in the curios slot while the bike is out. This is the whole item-loss fix: if the item never
        // leaves the slot, losing the entity (unloaded chunk, shard hop, a stray /kill) can never lose the item. The
        // deploy record + the entity's owner marker are enough to find and recall it; the slot is left untouched.

        ChatOutputHandler.chatConfirmation(player, "Hoverbike deployed.");
    }

    // build a variant bike facing the player's yaw, return the first candidate pos clear of terrain, else null.
    // spawn point is the player's feet where they're standing, so we test BLOCK collisions only (blocksClear):
    // noCollision(entity, box) counts hard-collision entities so the rider's own body would block a feet-centred
    // box, which is why the keybind kept reporting "not enough room" on open ground.
    // tries: feet, +1, -1, then 1-2 blocks ahead along look yaw so a rider flush against a wall can still deploy.
    private HoverbikeEntity trySpawn(Level level, ServerPlayer player, int variant)
    {
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        // yaw-only look dir for the in-front fallbacks
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
            HoverbikeEntity bike = new HoverbikeEntity(level, c[0], c[1], c[2]);
            bike.setVariant(variant);
            bike.setYRot(player.getYRot());
            if (blocksClear(level, bike))
                return bike;
        }
        return null;
    }

    // true if the box (deflated to tolerate resting flush on ground) hits no solid block shapes. ignores entity
    // collisions on purpose: the deploying player must not block their own bike. still denies enclosed 1x1 spaces.
    // public + Entity-typed so the space-pod deploy path reuses the exact same placement test (same rationale).
    public static boolean blocksClear(Level level, Entity entity)
    {
        AABB box = entity.getBoundingBox().deflate(1.0E-3D);
        for (VoxelShape shape : level.getBlockCollisions(entity, box))
        {
            if (!shape.isEmpty())
                return false;
        }
        return true;
    }

    // death/respawn handler: despawn a bike that is still out in the world so respawn does not leave an orphan, and
    // clear the record so the next toggle deploys fresh. It does NOT put an item back: the item never left the curios
    // slot, so on death Curios either keeps it (keepInventory) or drops it as an ordinary death drop the player picks
    // up. Handing one over here would DUPLICATE the chip the player still has. Scans all levels because the bike may
    // be stranded in the dim the player died in. No-op if nothing is deployed.
    static void despawnDeployedOnRespawn(ServerPlayer player)
    {
        if (!HoverbikeDeployData.hasDeployed(player))
            return;

        UUID uuid = HoverbikeDeployData.deployedUUID(player);
        HoverbikeEntity bike = findAcrossLevels(player.getServer(), uuid);
        if (bike != null)
        {
            bike.ejectPassengers();
            bike.discard();
        }
        HoverbikeDeployData.clear(player);
    }

    public static void handler(final PacketHoverbikeToggle message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
