package net.shurui.shuruisutilities.core.mixin.entity;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.shurui.shuruisutilities.teleport.SmpNetherTeleporter;
import net.shurui.shuruisutilities.util.events.entity.EntityPortalEvent;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.common.MinecraftForge;

/**
 * Routes nether-portal travel between SU's survival dim (shuruisutilities:smp) and the REAL Nether, and back,
 * leaving the overworld and every other dim on stock vanilla behaviour.
 *
 * lives in Entity.handleNetherPortal, NOT NetherPortalBlock.entityInside: entityInside never teleports, it just
 * arms the ~80-tick portal-wait timer; travel happens later in handleNetherPortal once the timer elapses. The
 * old SU version hijacked entityInside at HEAD and teleported immediately, bypassing the timer so portals fired
 * instantly in every gamemode. Redirecting the changeDimension call inside handleNetherPortal runs strictly
 * after the timer, so survival still gets the normal ~4s stand.
 *
 * destination: at the redirect point vanilla already resolved dest = Nether-source ? overworld : Nether. An
 * entity in a portal in smp (not the Nether) therefore already resolves to the Nether, so smp->Nether needs no
 * dest change, only the su_smp_origin tag. The only dest we actively redirect is Nether->smp for tagged
 * entities (vanilla would send them to the overworld).
 *
 * 1:1 vs 8:1: Nether-side arrival keeps the vanilla PortalForcer (8:1 is correct for the Nether). smp-side uses
 * SmpNetherTeleporter (1:1) because smp is overworld-type (scale 1.0); the 8:1 forcer would drop players in terrain.
 */
@Mixin(Entity.class)
public abstract class MixinEntityNetherPortal
{
    // Asked of SmpWorld, never spelled out: the hardcoded "shuruisutilities:smp" this replaced was left behind by
    // the multiworld namespace rename, so on a migrated server none of the checks below matched the smp world and
    // portal routing quietly reverted to vanilla. SmpWorld accepts both spellings.

    // entity persistent-data key: entered the Nether from smp, so the return portal sends it back to smp not
    // the overworld. absent/other = normal overworld origin.
    private static final String SU_SMP_ORIGIN_KEY = "su_smp_origin";

    // An origin tag written by ANY build counts: one written before the namespace rename names the legacy id, and
    // an entity that walked into the Nether then relogged after an update must still be sent home to smp.
    private static boolean su$isSmpOrigin(String originId)
    {
        return net.shurui.shuruisutilities.multiworld.v2.SmpWorld.id().toString().equals(originId)
                || net.shurui.shuruisutilities.multiworld.v2.SmpWorld.legacyId().toString().equals(originId);
    }

    // warn-once when the Nether dim doesn't exist on this server (e.g. dev runtime)
    private static final AtomicBoolean SU_WARNED_NO_NETHER = new AtomicBoolean(false);

    // portal block the entity is standing in; vanilla findDimensionEntryPoint reads the axis off it, we do the
    // same to orient a freshly built smp-side exit portal.
    @Shadow
    protected BlockPos portalEntrancePos;

    // redirect the single travel call inside handleNetherPortal; fires only after the portal-wait timer, so the
    // timer is untouched. vanillaDest is vanilla's resolved dest (Nether for non-Nether source, overworld for Nether).
    @Redirect(method = "handleNetherPortal",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;changeDimension(Lnet/minecraft/server/level/ServerLevel;)Lnet/minecraft/world/entity/Entity;"))
    public Entity suRouteNetherPortal(Entity self, ServerLevel vanillaDest)
    {
        // handleNetherPortal only runs on ServerLevel but guard anyway; non-server/missing source = vanilla
        if (!(self.level() instanceof ServerLevel fromLevel))
            return self.changeDimension(vanillaDest);

        // mount guard: carriers/mounts don't portal-travel independently
        if (self.isPassenger() || self.isVehicle() || !self.canChangeDimensions())
            return self.changeDimension(vanillaDest);

        ResourceLocation currentDim = fromLevel.dimension().location();
        boolean fromSmp = net.shurui.shuruisutilities.multiworld.v2.SmpWorld.is(currentDim);
        boolean fromNether = fromLevel.dimension() == Level.NETHER;

        // is this a trip we take over, and where to? anything else stays vanilla.
        ServerLevel destination;
        if (fromSmp)
        {
            // smp -> real Nether. vanilla already resolved vanillaDest to the Nether, so keep it and just tag
            // for the return trip.
            destination = vanillaDest;
            if (destination == null || destination.dimension() != Level.NETHER)
            {
                // Nether missing/disabled: warn once and let vanilla handle it
                if (SU_WARNED_NO_NETHER.compareAndSet(false, true))
                    LoggingHandler.sulog.warn("[Portal] nether portal used in smp but the Nether dimension is not "
                            + "present on this server; leaving vanilla travel in place.");
                return self.changeDimension(vanillaDest);
            }
        }
        else if (fromNether)
        {
            // Nether -> smp, but ONLY for entities that came from smp; others go to overworld via vanilla
            String originId = self.getPersistentData().getString(SU_SMP_ORIGIN_KEY);
            if (originId == null || !su$isSmpOrigin(originId))
                return self.changeDimension(vanillaDest);
            destination = resolveSmp(fromLevel);
            if (destination == null)
                // smp gone (shouldn't happen if they came from it); let vanilla send them to overworld
                return self.changeDimension(vanillaDest);
        }
        else
        {
            // overworld (and anything else): fully vanilla, no redirect
            return self.changeDimension(vanillaDest);
        }

        // fire SU's veto/admin-portal query with the ACTUAL dest. if a listener (admin portal region,
        // TeleportHelper perm check) cancels, it has claimed this portal: abort travel, do NOT redirect.
        if (MinecraftForge.EVENT_BUS.post(new EntityPortalEvent(self, fromLevel, self.blockPosition(), destination,
                new BlockPos(0, 0, 0), true)))
            return null;

        // tag/clear the smp-return marker
        if (fromSmp)
            self.getPersistentData().putString(SU_SMP_ORIGIN_KEY,
                    net.shurui.shuruisutilities.multiworld.v2.SmpWorld.id().toString());
        else
            self.getPersistentData().remove(SU_SMP_ORIGIN_KEY);

        if (destination.dimension() == Level.NETHER)
            // Nether-side: vanilla 8:1 forcer is correct
            return self.changeDimension(destination);

        // smp-side: overworld-type (scale 1.0), 1:1 placement so players don't land in terrain. orient any new
        // exit portal on the entrance axis.
        return self.changeDimension(destination, new SmpNetherTeleporter(readEntranceAxis(fromLevel)));
    }

    // horizontal axis of the portal block the entity stands in, defaulting to X
    private Direction.Axis readEntranceAxis(ServerLevel fromLevel)
    {
        if (this.portalEntrancePos == null)
            return Direction.Axis.X;
        BlockState state = fromLevel.getBlockState(this.portalEntrancePos);
        return state.getOptionalValue(BlockStateProperties.HORIZONTAL_AXIS).orElse(Direction.Axis.X);
    }

    // resolve the smp ServerLevel; null if this server has no smp world. SmpWorld already tries the live world map
    // first, then loads through the multiworld manager, and does both under each namespace spelling, so the
    // fallback that used to live here is now inside it.
    private static ServerLevel resolveSmp(ServerLevel from)
    {
        return net.shurui.shuruisutilities.multiworld.v2.SmpWorld.level(from.getServer());
    }
}
