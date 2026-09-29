package net.shurui.shuruisutilities.teleport;

import java.util.Optional;
import java.util.function.Function;

import net.minecraft.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.PortalForcer;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.ITeleporter;

// ITeleporter driving nether-portal travel between the overworld and SU's smp dimension at 1:1 coords.
// vanilla findDimensionEntryPoint only builds an exit-portal PortalInfo when the source or dest dimension IS
// the vanilla Nether. smp's dimension type is minecraft:overworld (scale 1.0, has_nether_portal=false), so
// vanilla returns null for our overworld<->smp trips. this teleporter reimplements the exit-portal search:
// use the dest level's PortalForcer to findPortalAround an existing nether portal near the entity's arrival
// pos, else createPortal a new one, then build PortalInfo exactly like vanilla via PortalShape.createPortalInfo.
// the horizontal search centre is scaled by the source/dest coordinateScale ratio (see getPortalInfo): for
// overworld(1.0)->smp(1.0) that ratio is 1.0 (unchanged 1:1 placement), for Nether(8.0)->smp(1.0) it is 8.0,
// which reverses the 8:1 down-scaling vanilla applied on the outbound leg so the return search lands on the
// real smp-side portal instead of a point 8x too close to origin.
public final class SmpNetherTeleporter implements ITeleporter
{
    // nether-portal axis the entity entered through, read from the source-side portal block
    private final Direction.Axis entranceAxis;

    /**
     * @param entranceAxis the {@link BlockStateProperties#HORIZONTAL_AXIS} of the nether portal block the entity
     *                     is currently standing in (its entrance), used to orient a newly created exit portal.
     */
    public SmpNetherTeleporter(Direction.Axis entranceAxis)
    {
        this.entranceAxis = entranceAxis;
    }

    @Override
    public PortalInfo getPortalInfo(Entity entity, ServerLevel destWorld, Function<ServerLevel, PortalInfo> defaultPortalInfo)
    {
        WorldBorder border = destWorld.getWorldBorder();

        // Convert the entity's current horizontal position into destination coordinate space before searching.
        // The ratio is derived from the dimension types, not hardcoded: overworld(1.0)->smp(1.0) gives 1.0 so the
        // existing overworld<->smp path stays 1:1 and unchanged; Nether(8.0)->smp(1.0) gives 8.0, which undoes the
        // 8:1 down-scaling vanilla applied on the outbound leg (that compression happened inside vanilla and is
        // invisible here, so it is never otherwise reversed). Y is left untouched: only X and Z carry the scale.
        double horizontalScale = 1.0D;
        if (entity.level() instanceof ServerLevel sourceLevel)
            horizontalScale = sourceLevel.dimensionType().coordinateScale() / destWorld.dimensionType().coordinateScale();

        double searchX = entity.getX() * horizontalScale;
        double searchZ = entity.getZ() * horizontalScale;
        BlockPos searchCenter = border.clampToBounds(searchX, entity.getY(), searchZ);

        PortalForcer forcer = destWorld.getPortalForcer();
        // isNether=false: both overworld and smp are overworld-type, so use the wider 128-block search radius.
        Optional<BlockUtil.FoundRectangle> exit = forcer.findPortalAround(searchCenter, false, border);
        if (exit.isEmpty())
            exit = forcer.createPortal(searchCenter, entranceAxis);

        if (exit.isEmpty())
        {
            // Could not find or build an exit portal (e.g. no room). Drop the entity at the clamped centre so we
            // never return null (which would silently abort the transfer and leave the player in limbo).
            // searchCenter.getY() is the SOURCE dimension's Y (WorldBorder.clampToBounds only clamps X/Z), and smp
            // is independently generated, so reusing it usually buries the player inside terrain. Look up a safe
            // surface Y from the DESTINATION level's heightmap instead.
            int safeY = destWorld.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    searchCenter.getX(), searchCenter.getZ());
            safeY = Math.max(destWorld.getMinBuildHeight(), Math.min(destWorld.getMaxBuildHeight() - 1, safeY));
            return new PortalInfo(new Vec3(searchCenter.getX() + 0.5D, safeY, searchCenter.getZ() + 0.5D),
                    entity.getDeltaMovement(), entity.getYRot(), entity.getXRot());
        }

        BlockUtil.FoundRectangle rect = exit.get();
        BlockState exitState = destWorld.getBlockState(rect.minCorner);
        Direction.Axis exitAxis = exitState.getOptionalValue(BlockStateProperties.HORIZONTAL_AXIS)
                .orElse(Direction.Axis.X);
        EntityDimensions dims = entity.getDimensions(entity.getPose());
        Vec3 relative = PortalShape.getRelativePosition(rect, exitAxis, entity.position(), dims);

        return PortalShape.createPortalInfo(destWorld, rect, exitAxis, relative, entity, entity.getDeltaMovement(),
                entity.getYRot(), entity.getXRot());
    }
}
