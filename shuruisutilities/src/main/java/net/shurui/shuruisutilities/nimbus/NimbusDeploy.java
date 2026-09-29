package net.shurui.shuruisutilities.nimbus;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import com.dragonminez.common.init.MainEntities;
import com.dragonminez.common.init.entities.BlackNimbusEntity;
import com.dragonminez.common.init.entities.FlyingNimbusEntity;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;

import net.shurui.shuruisutilities.hoverbike.PacketHoverbikeToggle;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

// deploy/recall/return machinery for DMZ's flying nimbus, driven from the SHARED hoverbike toggle keybind and the
// SHARED "hoverbike" curios slot. dispatched from PacketHoverbikeToggle when the slotted chip is a NimbusChipItem.
//
// two entities, one chip: DMZ ships FlyingNimbusEntity and BlackNimbusEntity as SEPARATE Mob subclasses. our deploy
// path calls startRiding (via addFreshEntity + mount) directly and BYPASSES DMZ's mobInteract alignment gate, so
// per the locked decision we honour purity by CHOICE OF ENTITY: alignment >= 66 spawns the flying nimbus, below 66
// spawns the black nimbus, from whichever single chip is in the slot. nobody is ever refused.
//
// why a separate class instead of extending HoverbikeEntity's baked-in owner/remove logic: the nimbus entities are
// DMZ's own classes, we cannot subclass them or add fields, so they have no ownerUUID and no remove()-returns-item
// hook. we mark ownership with a UUID in the nimbus' Forge persistent data (survives save/restart) and handle the
// destroy-return out-of-band in NimbusDropInterceptor.
//
// dragonminez is a MANDATORY dependency, so these classes are always present. every DMZ touch point is still wrapped
// in a Throwable guard because it reaches into DMZ 2.1.3 internals: a future DMZ change should degrade to "couldn't
// deploy" instead of crashing the toggle.
public final class NimbusDeploy
{
    private NimbusDeploy() {}

    static final String SLOT = "hoverbike";           // shared curios slot, registered by the hoverbike datapack
    public static final String OWNER_TAG = "su_nimbus_owner"; // UUID key in the nimbus' Forge persistent data

    // at or above this alignment the pure flying nimbus spawns; below it, the black nimbus. DMZ alignment is an int
    // clamped 0..100 defaulting to 100, read server-side from StatsData.getResources().getAlignment().
    private static final int PURE_ALIGNMENT = 66;

    // mirror of PacketHoverbikeToggle.recall: find the nimbus across ALL levels (death and travel can strand it in
    // another dim), eject riders, strip our owner marker, and discard it, then clear the record. The chip NEVER
    // leaves the curios slot (see deploy), so recall puts nothing back: despawning the entity is the whole job.
    // discard() uses RemovalReason.DISCARDED, which does NOT trigger DMZ's hurt()-drop, so no nimbus item leaks here
    // and NimbusDropInterceptor never fires.
    public static void recall(ServerPlayer player)
    {
        UUID uuid = NimbusDeployData.deployedUUID(player);
        Mob nimbus = uuid != null ? findAcrossLevels(player.getServer(), uuid) : null;
        if (nimbus != null)
        {
            nimbus.ejectPassengers();
            nimbus.getPersistentData().remove(OWNER_TAG);
            nimbus.discard();
            NimbusDeployData.clear(player);
            ChatOutputHandler.chatConfirmation(player, "Nimbus recalled.");
            return;
        }

        // Not in ANY loaded level. This does NOT mean the nimbus was destroyed: getEntity only finds LOADED entities,
        // so a nimbus in an unloaded chunk (or on another shard after a hop) is indistinguishable from a gone one. The
        // chip is safe in the slot the whole time, so a lost nimbus costs nothing: clear the stale record and let the
        // player deploy again. We do NOT recurse into deploy(), and we create no chip.
        LoggingHandler.sulog.info("[Nimbus] {} recalled a nimbus whose entity ({}) is not in any loaded level "
                + "(unloaded chunk or another shard); clearing the record. The chip stayed in the slot, so a new "
                + "deploy is free.", player.getGameProfile().getName(), uuid);
        NimbusDeployData.clear(player);
        ChatOutputHandler.chatConfirmation(player, "Nimbus recalled.");
    }

    // scan every loaded level for an owned nimbus (either DMZ class) with uuid; null if server unavailable/not found
    public static Mob findAcrossLevels(MinecraftServer server, UUID uuid)
    {
        if (server == null || uuid == null)
            return null;
        for (ServerLevel level : server.getAllLevels())
        {
            Entity found = level.getEntity(uuid);
            if (found instanceof FlyingNimbusEntity flying)
                return flying;
            if (found instanceof BlackNimbusEntity black)
                return black;
        }
        return null;
    }

    // if the shared slot holds a NimbusChipItem, spawn DMZ's real nimbus entity (flying or black, chosen by the
    // deploying player's alignment), mark owner, record the deploy and empty the slot. else tell the player they
    // have none equipped.
    public static void deploy(ServerPlayer player)
    {
        ItemStack slotStack = CuriosApi.getCuriosInventory(player)
                .map(h -> h.findCurio(SLOT, 0).map(SlotResult::stack).orElse(ItemStack.EMPTY))
                .orElse(ItemStack.EMPTY);

        if (!(slotStack.getItem() instanceof NimbusChipItem))
        {
            ChatOutputHandler.chatError(player, "No nimbus equipped.");
            return;
        }

        Level level = player.level();
        boolean pure = isPure(player);
        Mob nimbus = trySpawn(level, player, pure);
        if (nimbus == null)
        {
            ChatOutputHandler.chatError(player, "Not enough room to deploy the nimbus here.");
            return;
        }

        // mark ownership in the nimbus' Forge persistent data: it survives save + restart, so a destroy long after
        // deploy still returns OUR chip (NimbusDropInterceptor reads this) with no in-memory server map to lose.
        nimbus.getPersistentData().putUUID(OWNER_TAG, player.getUUID());
        level.addFreshEntity(nimbus);
        level.gameEvent(player, GameEvent.ENTITY_PLACE, nimbus.position());

        // seat the summoner: our deploy path mounts directly, which is exactly what bypasses DMZ's mobInteract
        // purity gate. we already honoured purity by picking the entity above.
        player.startRiding(nimbus, true);

        NimbusDeployData.set(player, nimbus.getUUID());
        // The chip STAYS in the curios slot while the nimbus is out. This is the item-loss fix: if the chip never
        // leaves the slot, losing the entity (unloaded chunk, shard hop, a stray destroy) can never lose the chip. The
        // deploy record + the nimbus' owner marker are enough to find and recall it; the slot is left untouched.

        ChatOutputHandler.chatConfirmation(player, "Nimbus deployed.");
    }

    // read DMZ alignment server-side; >= 66 is pure -> flying nimbus. defaults to pure if the capability is missing
    // or DMZ internals shift, since alignment itself defaults to 100 (pure) in DMZ.
    private static boolean isPure(ServerPlayer player)
    {
        try
        {
            StatsData data = player.getCapability(StatsCapability.INSTANCE).resolve().orElse(null);
            if (data == null)
                return true;
            return data.getResources().getAlignment() >= PURE_ALIGNMENT;
        }
        catch (Throwable t)
        {
            return true;
        }
    }

    // same placement search as the hoverbike/pod (feet, +/-1, then ahead along yaw), reusing the block-only clear
    // test so the deploying player's own body can't block the spawn. wrapped in a Throwable guard: constructing the
    // DMZ nimbus is the one DMZ-internal touch here, and a DMZ change must degrade, not crash the toggle.
    private static Mob trySpawn(Level level, ServerPlayer player, boolean pure)
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
                Mob nimbus = pure
                        ? new FlyingNimbusEntity(MainEntities.FLYING_NIMBUS.get(), level)
                        : new BlackNimbusEntity(MainEntities.BLACK_NIMBUS.get(), level);
                nimbus.moveTo(c[0], c[1], c[2], player.getYRot(), 0.0F);
                if (PacketHoverbikeToggle.blocksClear(level, nimbus))
                    return nimbus;
            }
            return null;
        }
        catch (Throwable t)
        {
            // DMZ internals shifted (or the nimbus type is unavailable): clean failure, don't crash the toggle.
            return null;
        }
    }

    // death/respawn handler: despawn a nimbus that is still out in the world so respawn does not leave an orphan, and
    // clear the record so the next toggle deploys fresh. It does NOT put a chip back: the chip never left the curios
    // slot, so on death Curios either keeps it (keepInventory) or drops it as an ordinary death drop the player picks
    // up. Handing one over here would DUPLICATE the chip the player still has. Scans all levels because the nimbus may
    // be stranded in the dim the player died in. No-op if none out.
    public static void despawnDeployedOnRespawn(ServerPlayer player)
    {
        if (!NimbusDeployData.hasDeployed(player))
            return;

        UUID uuid = NimbusDeployData.deployedUUID(player);
        Mob nimbus = findAcrossLevels(player.getServer(), uuid);
        if (nimbus != null)
        {
            nimbus.ejectPassengers();
            // strip the owner marker so the discard can't be mistaken for a destroy by NimbusDropInterceptor.
            nimbus.getPersistentData().remove(OWNER_TAG);
            nimbus.discard();
        }
        NimbusDeployData.clear(player);
    }
}
