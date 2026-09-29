package net.shurui.shuruisutilities.util.events;

import net.shurui.shuruisutilities.util.events.SUModuleEvent.SUModuleServerAboutToStartEvent;
import net.shurui.shuruisutilities.util.events.SUModuleEvent.SUModuleServerStoppedEvent;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class ServerEventHandler
{

    private boolean registered = false;

    public ServerEventHandler()
    {
        MinecraftForge.EVENT_BUS.register(this);
        // APIRegistry.getSUEventBus().register(this);
    }

    public ServerEventHandler(boolean forceRegister)
    {
        this();
        if (forceRegister)
            register();
    }

    protected void register()
    {
        if (registered)
            return;
        registered = true;
        MinecraftForge.EVENT_BUS.register(this);
    }

    protected void unregister()
    {
        if (registered)
        {
            try
            {
                MinecraftForge.EVENT_BUS.unregister(this);
            }
            catch (NullPointerException ex)
            {
                // event handler was not registered to begin with
            }
            registered = false;
        }
    }

    @SubscribeEvent
    public void serverAboutToStart(SUModuleServerAboutToStartEvent e)
    {
        register();
    }

    @SubscribeEvent
    public void serverStopped(SUModuleServerStoppedEvent e)
    {
        unregister();
    }

}
