package net.shurui.shuruisutilities.spacepod;

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

import com.dragonminez.common.init.MainEntities;
import com.dragonminez.common.init.MainItems;
import com.dragonminez.common.init.entities.SpacePodEntity;

import net.shurui.shuruisutilities.hoverbike.HoverbikeItems;
import net.shurui.shuruisutilities.hoverbike.PacketHoverbikeToggle;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

// deploy/recall/return machinery for DMZ's SpacePodEntity, driven from the SHARED hoverbike toggle keybind and the
// SHARED "hoverbike" curios slot. dispatched from PacketHoverbikeToggle when the slotted chip is a PodChipItem.
//
// why a separate class instead of extending HoverbikeEntity's baked-in owner/remove logic: SpacePodEntity is DMZ's
// own class, we cannot subclass it or add fields, so it has no ownerUUID and no remove()-returns-item hook. we mark
// ownership with a UUID in the pod's Forge persistent data (survives save/restart) and handle the destroy-return
// out-of-band in PodDropInterceptor.
//
// dragonminez is a MANDATORY dependency, so SpacePodEntity is always present (existing SU space code imports it
// directly too). the spawn is still wrapped in a Throwable guard because it reaches into DMZ 2.1.3 internals: a
// future DMZ change should degrade to "couldn't deploy" instead of crashing the toggle.
public final class SpacePodDeploy
{
    private SpacePodDeploy() {}

    static final String SLOT = "hoverbike";        // shared curios slot, registered by the hoverbike datapack
    // UUID key in the pod's Forge persistent data. PUBLIC so it is the ONE source of truth: PodDropInterceptor (same
    // package) and SpaceTravelModule (the space package, which stamps a fresh pod when a pilot enters space aboard
    // one) both read THIS constant instead of a hand-copied literal that could silently drift out of agreement.
    public static final String OWNER_TAG = "su_pod_owner";

    // mirror of PacketHoverbikeToggle.recall: find the pod across ALL levels (death and space travel can strand it in
    // another dim), eject riders, strip our owner marker, and discard it, then clear the record. The chip NEVER
    // leaves the curios slot (see deploy), so recall puts nothing back: despawning the entity is the whole job.
    // discard() uses RemovalReason.DISCARDED, which does NOT trigger DMZ's hurt()-drop, so no NAVE_SAIYAN_ITEM leaks
    // here and PodDropInterceptor never fires.
    public static void recall(ServerPlayer player)
    {
        UUID uuid = PodDeployData.deployedUUID(player);
        SpacePodEntity pod = uuid != null ? findAcrossLevels(player.getServer(), uuid) : null;
        if (pod != null)
        {
            pod.ejectPassengers();
            pod.getPersistentData().remove(OWNER_TAG);
            pod.discard();
            PodDeployData.clear(player);
            ChatOutputHandler.chatConfirmation(player, "Space pod recalled.");
            return;
        }

        // Not in ANY loaded level. This does NOT mean the pod was destroyed: getEntity only finds LOADED entities, and
        // entering a planet crosses dimensions (findAcrossLevels only scans THIS server, so a pod left on another
        // shard is invisible here). The chip is safe in the slot the whole time, so a lost pod costs nothing: clear
        // the stale record and let the player deploy again. We do NOT recurse into deploy(), and we create no chip.
        LoggingHandler.sulog.info("[SpacePod] {} recalled a pod whose entity ({}) is not in any loaded level on this "
                + "server (unloaded chunk or another shard); clearing the record. The chip stayed in the slot, so a "
                + "new deploy is free.", player.getGameProfile().getName(), uuid);
        PodDeployData.clear(player);
        ChatOutputHandler.chatConfirmation(player, "Space pod recalled.");
    }

    // scan every loaded level for a pod with uuid; null if server unavailable or not found
    public static SpacePodEntity findAcrossLevels(MinecraftServer server, UUID uuid)
    {
        if (server == null || uuid == null)
            return null;
        for (ServerLevel level : server.getAllLevels())
        {
            Entity found = level.getEntity(uuid);
            if (found instanceof SpacePodEntity pod)
                return pod;
        }
        return null;
    }

    // Is this the player's OWN pod, judged from the pod rather than from the per-player record?
    //
    // The record alone is the wrong question anywhere a RIDDEN pod is being handled. It is written only by our
    // deploy, so a pilot flying DMZ's own saiyan ship (spawned from NAVE_SAIYAN_ITEM, which carries neither a record
    // nor an owner marker) has none, and a player riding somebody else's pod while their own sits parked has one
    // that names the WRONG pod. Both answers are read off the entity in front of us instead.
    public static boolean ownedBy(SpacePodEntity pod, ServerPlayer player)
    {
        if (pod == null || player == null)
            return false;
        if (pod.getUUID().equals(PodDeployData.deployedUUID(player)))
            return true;
        return pod.getPersistentData().hasUUID(OWNER_TAG)
                && player.getUUID().equals(pod.getPersistentData().getUUID(OWNER_TAG));
    }

    // Take a ridden pod out of the world cleanly: eject, strip the marker, discard, and drop the deploy record
    // only if it is THIS pod that the record names (a pod parked elsewhere keeps its own).
    //
    // The marker has to go BEFORE the discard or PodDropInterceptor reads the removal as a destroy and hands out a
    // second chip.
    public static void removeRidden(ServerPlayer player, SpacePodEntity pod)
    {
        if (pod == null)
            return;
        pod.ejectPassengers();
        pod.getPersistentData().remove(OWNER_TAG);
        pod.discard();
        if (pod.getUUID().equals(PodDeployData.deployedUUID(player)))
            PodDeployData.clear(player);
    }

    // Hand back the ITEM a pod came out of. Last-resort path for a trip that could not rebuild the pod at its
    // destination: the pod is already gone by then, so the player must not also be out an item.
    //
    //   ours     -> NOTHING. OUR chip never leaves the curios slot while the pod is out, so it is still sitting there
    //               and the player just deploys again. Handing one over would DUPLICATE it.
    //   not ours -> DMZ's NAVE_SAIYAN_ITEM. A player flying DMZ's OWN saiyan ship has no chip in any slot, so if the
    //               trip could not rebuild the ship they must get DMZ's item back or the ship is lost. Handing over
    //               our chip instead would quietly convert somebody's DMZ ship into SU content.
    public static void handBackPodItem(ServerPlayer player, boolean ours)
    {
        // The player still holding the pod chip in the shared slot is the whole no-loss guarantee for an SU pod: the
        // entity can go without the chip going, so a second item here would DUPLICATE the one already in the slot.
        if (chipInSlot(player))
            return;
        // No chip in the slot, so the slot cannot re-deploy the pod and the entity is already gone. Hand an item back
        // so the ship is never lost: OUR pod (its chip somehow not in the slot, e.g. an SU pod converted from a DMZ
        // ship on a space entry) gets a fresh chip, a DMZ saiyan ship gets DMZ's own item.
        giveBack(player, ours ? chipStack() : dmzShipStack());
    }

    // Is this DMZ's OWN saiyan ship (spawned from NAVE_SAIYAN_ITEM) rather than a pod that came out of an SU chip?
    // Read off the entity: an SU pod always carries the owner marker (deploy, and every rebuild of one, stamps it), and
    // a DMZ ship never does, so "no marker at all and not the pod this player's deploy record names" is a DMZ ship.
    // Space travel keeps it that way on every rebuild (it never stamps a DMZ ship), so the ship stays a ship end to end:
    // DMZ's own destroy drop, recall and death rules keep applying to it, and a hand-back returns DMZ's item, never a
    // chip. A pod marked for SOMEBODY ELSE is not a DMZ ship either (its chip is in its owner's slot).
    public static boolean dmzShip(SpacePodEntity pod, ServerPlayer player)
    {
        if (pod == null)
            return false;
        return !pod.getPersistentData().hasUUID(OWNER_TAG) && !ownedBy(pod, player);
    }

    // Hand back DMZ's saiyan ship item for a DMZ ship that could not be carried or rebuilt. Unlike handBackPodItem this
    // ignores a pod chip in the shared slot: that chip is a separate item the player owns, not this ship, so skipping
    // the ship because of it would lose the ship. The entity is always gone before this runs, so it never duplicates.
    public static void handBackShip(ServerPlayer player)
    {
        giveBack(player, dmzShipStack());
    }

    // True when the player is holding a pod chip in the shared curios slot. That is the case for every SU pod while
    // its entity is deployed (the chip never leaves the slot), so it is the exact test for whether handing a pod item
    // back would duplicate the chip they already hold, and for whether a discarded pod can be rebuilt from the slot on
    // the far side of a cross-server hop or must instead have its item returned here.
    public static boolean chipInSlot(ServerPlayer player)
    {
        ItemStack slotStack = CuriosApi.getCuriosInventory(player)
                .map(h -> h.findCurio(SLOT, 0).map(SlotResult::stack).orElse(ItemStack.EMPTY))
                .orElse(ItemStack.EMPTY);
        return slotStack.getItem() instanceof PodChipItem;
    }

    // DMZ's own saiyan ship item. dragonminez is mandatory so this resolves, but it is guarded like every other
    // DMZ-internal touch in this class. If it ever cannot be resolved we hand over OUR chip rather than nothing:
    // giving the wrong pod item back is a support ticket, giving none is a lost ship.
    private static ItemStack dmzShipStack()
    {
        try
        {
            return new ItemStack(MainItems.NAVE_SAIYAN_ITEM.get());
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[SpacePod] could not resolve DMZ's saiyan ship item; returning an SU pod chip instead.");
            return chipStack();
        }
    }

    // if the shared slot holds a PodChipItem, spawn DMZ's real SpacePodEntity (so every existing
    // `instanceof SpacePodEntity` guard in the space code keeps working), mark owner, record the deploy and empty
    // the slot. else tell the player they have none equipped.
    public static void deploy(ServerPlayer player)
    {
        ItemStack slotStack = CuriosApi.getCuriosInventory(player)
                .map(h -> h.findCurio(SLOT, 0).map(SlotResult::stack).orElse(ItemStack.EMPTY))
                .orElse(ItemStack.EMPTY);

        if (!(slotStack.getItem() instanceof PodChipItem))
        {
            ChatOutputHandler.chatError(player, "No space pod equipped.");
            return;
        }

        Level level = player.level();
        SpacePodEntity pod = trySpawn(level, player);
        if (pod == null)
        {
            ChatOutputHandler.chatError(player, "Not enough room to deploy the space pod here.");
            return;
        }

        // mark ownership in the pod's Forge persistent data: it survives save + restart, so a destroy long after
        // deploy still returns OUR chip (PodDropInterceptor reads this) with no in-memory server map to lose.
        pod.getPersistentData().putUUID(OWNER_TAG, player.getUUID());
        level.addFreshEntity(pod);
        level.gameEvent(player, GameEvent.ENTITY_PLACE, pod.position());

        // seat the pilot immediately, so spawning the pod puts them straight into it. Our deploy path mounts DIRECTLY
        // via startRiding, which bypasses DMZ's two-click mobInteract flow (click 1 opens the door and sets IS_OPEN
        // true, click 2 closes the door and seats you). Investigating that flow settled the open-state worry in the
        // brief: a normally SEATED pilot always has the door SHUT, because click 2 seats you AND sets IS_OPEN back to
        // false. The pod's IS_OPEN entity-data defaults to false too, so a freshly spawned pod is already closed, and
        // seating on it reproduces DMZ's real seated state exactly. We therefore do NOT touch the open state: forcing
        // it open would leave the door hanging open while flying, which is the abnormal look. Throwable-guarded so a
        // DMZ mount-shape shift degrades to "deployed but standing" rather than crashing the toggle; recall still
        // works from there (the record is set below regardless). NimbusDeploy already seats the same way.
        try
        {
            player.startRiding(pod, true);
        }
        catch (Throwable t)
        {
            // DMZ/vehicle internals shifted: leave the pilot standing beside the deployed pod. The deploy record is
            // still written below, so the toggle keybind can recall it and the chip is never lost.
        }

        PodDeployData.set(player, pod.getUUID());
        // The chip STAYS in the curios slot while the pod is out. This is the item-loss fix: if the chip never leaves
        // the slot, losing the entity (unloaded chunk, a shard hop into a planet, a stray destroy) can never lose the
        // chip. The deploy record + the pod's owner marker are enough to find and recall it; the slot is untouched.

        ChatOutputHandler.chatConfirmation(player, "Space pod deployed.");
    }

    // same placement search as the hoverbike (feet, +/-1, then ahead along yaw), reusing its block-only clear test
    // so the deploying player's own body can't block the spawn. wrapped in a Throwable guard: constructing DMZ's
    // SpacePodEntity is the one DMZ-internal touch here, and a DMZ change must degrade, not crash the toggle.
    private static SpacePodEntity trySpawn(Level level, ServerPlayer player)
    {
        try
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
                SpacePodEntity pod = new SpacePodEntity(MainEntities.SPACE_POD.get(), level);
                pod.moveTo(c[0], c[1], c[2], player.getYRot(), 0.0F);
                if (PacketHoverbikeToggle.blocksClear(level, pod))
                    return pod;
            }
            return null;
        }
        catch (Throwable t)
        {
            // DMZ internals shifted (or SPACE_POD unavailable): report a clean failure, don't crash the toggle.
            return null;
        }
    }

    // death/respawn handler: despawn a pod that is still out in the world so respawn does not leave an orphan, and
    // clear the record so the next toggle deploys fresh. It does NOT put a chip back: the chip never left the curios
    // slot, so on death Curios either keeps it (keepInventory) or drops it as an ordinary death drop the player picks
    // up. Handing one over here would DUPLICATE the chip the player still has. Scans all levels because the pod may be
    // stranded in the dim the player died in. No-op if none deployed.
    public static void despawnDeployedOnRespawn(ServerPlayer player)
    {
        if (!PodDeployData.hasDeployed(player))
            return;

        UUID uuid = PodDeployData.deployedUUID(player);
        SpacePodEntity pod = findAcrossLevels(player.getServer(), uuid);
        if (pod != null)
        {
            pod.ejectPassengers();
            // strip the owner marker so the discard can't be mistaken for a destroy by PodDropInterceptor.
            pod.getPersistentData().remove(OWNER_TAG);
            pod.discard();
        }
        PodDeployData.clear(player);
    }

    static ItemStack chipStack()
    {
        return new ItemStack(HoverbikeItems.POD_CHIP.get());
    }

    // Hand a pod item back: curios slot (chip only), then the inventory, then the floor.
    //
    // The floor is LAST and it is deferred by one server task, which is the point of this method existing.
    // recall() used to drop straight to the ground the instant the slot was occupied, and every landing calls this
    // immediately BEFORE a cross-dimension teleport, so the item landed in the dimension the player was leaving:
    // a chip dropped in the space dimension is as lost as the pod was. server.execute runs at the end of the
    // current tick, by which time the teleport has happened and the drop lands at the player's feet on the planet.
    private static void giveBack(ServerPlayer player, ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
            return;

        if (stack.getItem() instanceof PodChipItem && curioSlotEmpty(player))
        {
            CuriosApi.getCuriosInventory(player).ifPresent(h -> h.setEquippedCurio(SLOT, 0, stack));
            return;
        }

        // add() shrinks the stack as it fills slots, so it is empty afterwards only if all of it fit.
        player.getInventory().add(stack);
        if (stack.isEmpty())
            return;

        ItemStack remainder = stack.copy();
        MinecraftServer server = player.getServer();
        if (server != null)
            server.execute(() -> player.drop(remainder, false));
        else
            player.drop(remainder, false);
    }

    private static boolean curioSlotEmpty(ServerPlayer player)
    {
        ItemStack slotStack = CuriosApi.getCuriosInventory(player)
                .map(h -> h.findCurio(SLOT, 0).map(SlotResult::stack).orElse(ItemStack.EMPTY))
                .orElse(ItemStack.EMPTY);
        return slotStack.isEmpty();
    }
}
