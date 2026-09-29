package net.shurui.shuruisutilities.api.permissions;

import net.shurui.shuruisutilities.api.UserIdent;

import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;

// parent of all permission events; sub-events are the nested classes below
public class PermissionEvent extends Event
{

    public ServerZone serverZone;

    public PermissionEvent(ServerZone serverZone)
    {
        this.serverZone = serverZone;
    }

    // new permission-tree (ServerZone) init; hook for setting up internal groups / default perms
    public static class Initialize extends PermissionEvent
    {
        public Initialize(ServerZone serverZone)
        {
            super(serverZone);
        }
    }

    // after permissions reloaded
    public static class AfterLoad extends PermissionEvent
    {
        public AfterLoad(ServerZone serverZone)
        {
            super(serverZone);
        }
    }

    // before permissions saved
    public static class BeforeSave extends PermissionEvent
    {
        public BeforeSave(ServerZone serverZone)
        {
            super(serverZone);
        }
    }

    // user-related events
    public static class User extends PermissionEvent
    {

        public UserIdent ident;

        public User(ServerZone serverZone, UserIdent ident)
        {
            super(serverZone);
            this.ident = ident;
        }

        @net.minecraftforge.eventbus.api.Cancelable
        public static class ModifyPermission extends User
        {

            public net.shurui.shuruisutilities.api.permissions.Zone zone;
            public String permissionNode;
            public String value;

            public ModifyPermission(ServerZone serverZone, UserIdent ident,
                    net.shurui.shuruisutilities.api.permissions.Zone zone, String permissionNode, String value)
            {
                super(serverZone, ident);
                this.zone = zone;
                this.permissionNode = permissionNode;
                this.value = value;
            }
        }

        @Cancelable
        public static class ModifyGroups extends User
        {

            public static enum Action
            {
                ADD, REMOVE
            }

            public Action action;
            public String group;

            public ModifyGroups(ServerZone serverZone, UserIdent ident, Action action, String group)
            {
                super(serverZone, ident);
                this.action = action;
                this.group = group;
            }
        }

    }

    // group-related events
    public static class Group extends PermissionEvent
    {

        public String group;

        public Group(ServerZone serverZone, String group)
        {
            super(serverZone);
            this.group = group;
        }

        @Cancelable
        public static class ModifyPermission extends Group
        {

            public net.shurui.shuruisutilities.api.permissions.Zone zone;
            public String permissionNode;
            public String value;

            public ModifyPermission(ServerZone serverZone, String group, net.shurui.shuruisutilities.api.permissions.Zone zone,
                    String permissionNode, String value)
            {
                super(serverZone, group);
                this.zone = zone;
                this.permissionNode = permissionNode;
                this.value = value;
            }
        }

        @Cancelable
        public static class Create extends Group
        {
            public Create(ServerZone serverZone, String group)
            {
                super(serverZone, group);
            }
        }

        @Cancelable
        public static class Delete extends Group
        {
            public Delete(ServerZone serverZone, String group)
            {
                super(serverZone, group);
            }
        }

    }

    // zone-related events
    public static class Zone extends PermissionEvent
    {

        public net.shurui.shuruisutilities.api.permissions.Zone zone;

        public Zone(ServerZone serverZone, net.shurui.shuruisutilities.api.permissions.Zone zone)
        {
            super(serverZone);
            this.zone = zone;
        }

        @Cancelable
        public static class Create extends Zone
        {
            public Create(ServerZone serverZone, net.shurui.shuruisutilities.api.permissions.Zone zone)
            {
                super(serverZone, zone);
            }
        }

        @Cancelable
        public static class Delete extends Zone
        {
            public Delete(ServerZone serverZone, net.shurui.shuruisutilities.api.permissions.Zone zone)
            {
                super(serverZone, zone);
            }
        }

    }

}