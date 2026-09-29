package net.shurui.shuruisutilities.util.events.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.eventbus.api.Cancelable;

@Cancelable
public class EntityPortalEvent extends EntityEvent
{

    public final Level worldFrom;

    public final BlockPos posFrom;

    public final BlockPos targetPos;

    public final Level targetDimension;

    // true = real vanilla portal block (nether/end) running its travel; false = SU portal-area teleport
    // query from PortalManager.playerMove. the suppression handler must only cancel the true case,
    // else it vetoes its own teleport.
    public final boolean fromVanillaPortalBlock;

    public EntityPortalEvent(Entity entity, Level worldFrom, BlockPos posFrom, Level worldDestination, BlockPos targetPos)
    {
        this(entity, worldFrom, posFrom, worldDestination, targetPos, false);
    }

    public EntityPortalEvent(Entity entity, Level worldFrom, BlockPos posFrom, Level worldDestination, BlockPos targetPos,
            boolean fromVanillaPortalBlock)
    {
        super(entity);
        this.worldFrom = worldFrom;
        this.posFrom = posFrom;
        this.targetPos = targetPos;
        this.targetDimension = worldDestination;
        this.fromVanillaPortalBlock = fromVanillaPortalBlock;
    }
}
