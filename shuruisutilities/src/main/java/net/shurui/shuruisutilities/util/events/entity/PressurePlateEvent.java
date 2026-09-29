package net.shurui.shuruisutilities.util.events.entity;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.eventbus.api.Cancelable;

// entity stepped on a pressure plate / trip wire; cancel to block activation.
// SU NOTE: candidate to PR straight into net.minecraftforge.event.entity.EntityEvent
@Cancelable
public class PressurePlateEvent extends EntityEvent
{

    public PressurePlateEvent(Entity entity)
    {
        super(entity);
    }

}
