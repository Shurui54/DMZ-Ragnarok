package net.shurui.shuruisutilities.util.events.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;

@Cancelable
public class WorldPreLoadEvent extends Event
{

    public final ResourceKey<Level> dim;

    public WorldPreLoadEvent(ResourceKey<Level> dim)
    {
        this.dim = dim;
    }

}
