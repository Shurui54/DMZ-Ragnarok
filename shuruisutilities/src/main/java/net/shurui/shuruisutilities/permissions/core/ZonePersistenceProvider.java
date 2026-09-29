package net.shurui.shuruisutilities.permissions.core;

import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.api.permissions.SUPermissions;
import net.shurui.shuruisutilities.api.permissions.ServerZone;

public abstract class ZonePersistenceProvider
{

    // outcome of the last load(), the perm system uses it to decide if writing back is safe.
    // LOADED = zone parsed ok; ABSENT = no data yet (first run, seeding/saving fine); FAILED = data present
    // but unreadable (malformed), saving MUST be blocked so we don't clobber the on-disk copy with an empty zone.
    public enum LoadOutcome
    {
        LOADED, ABSENT, FAILED
    }

    // defaults to ABSENT until a load runs
    protected LoadOutcome lastLoadOutcome = LoadOutcome.ABSENT;

    public LoadOutcome getLastLoadOutcome()
    {
        return lastLoadOutcome;
    }

    public abstract void save(ServerZone serverZone);

    public abstract ServerZone load();

    public static void writeUserGroupPermissions(ServerZone serverZone)
    {
        // Clear groups from players (leftovers, if player was removed from all groups)
        for (UserIdent ident : serverZone.getPlayerPermissions().keySet())
            serverZone.clearPlayerPermission(ident, SUPermissions.PLAYER_GROUPS);

        // Add groups to players
        for (Entry<UserIdent, Set<String>> entry : serverZone.getPlayerGroups().entrySet())
            serverZone.setPlayerPermissionProperty(entry.getKey(), SUPermissions.PLAYER_GROUPS,
                    StringUtils.join(entry.getValue(), ","));
    }

    public static void readUserGroupPermissions(ServerZone serverZone)
    {
        for (UserIdent ident : serverZone.getPlayerPermissions().keySet())
        {
            serverZone.registerPlayer(ident);
            String groupList = serverZone.getPlayerPermission(ident, SUPermissions.PLAYER_GROUPS);
            serverZone.clearPlayerPermission(ident, SUPermissions.PLAYER_GROUPS);
            if (groupList == null)
                continue;
            for (String group : groupList.replace(" ", "").split(","))
            {
                // A player in no group is written as an empty list, and "".split(",") is [""], so without this every
                // load put that player in a group named "" and the saved membership flipped between [] and [""].
                if (group.isEmpty())
                    continue;
                serverZone.addPlayerToGroup(ident, group);
            }
        }
    }

}
