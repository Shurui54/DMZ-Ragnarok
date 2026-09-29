package net.shurui.shuruisutilities.util.events;

import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerLifecycleEvent;

public class SUModuleEvent extends Event
{

    protected ServerLifecycleEvent event;

    public ServerLifecycleEvent getServerLifecycleEvent()
    {
        return event;
    }

    public static class SUModuleServerAboutToStartEvent extends SUModuleEvent
    {
        public SUModuleServerAboutToStartEvent(ServerAboutToStartEvent event)
        {
            this.event = event;
        }
    }

    public static class SUModuleServerStartingEvent extends SUModuleEvent
    {
        public SUModuleServerStartingEvent(ServerStartingEvent event)
        {
            this.event = event;
        }
    }

    public static class SUModuleServerStartedEvent extends SUModuleEvent
    {
        public SUModuleServerStartedEvent(ServerStartedEvent event)
        {
            this.event = event;
        }
    }

    public static class SUModuleServerStoppingEvent extends SUModuleEvent
    {
        public SUModuleServerStoppingEvent(ServerStoppingEvent event)
        {
            this.event = event;
        }
    }

    public static class SUModuleServerStoppedEvent extends SUModuleEvent
    {
        public SUModuleServerStoppedEvent(ServerStoppedEvent event)
        {
            this.event = event;
        }
    }

}
