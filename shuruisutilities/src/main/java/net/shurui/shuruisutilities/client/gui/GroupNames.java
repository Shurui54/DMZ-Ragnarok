package net.shurui.shuruisutilities.client.gui;

// friendly display names for the two internal groups. display only: _ALL_/_OPS_ ids still go to the server.
public final class GroupNames
{
    private GroupNames() {}

    public static String display(String id)
    {
        if ("_ALL_".equals(id))
            return "Default (everyone)";
        if ("_OPS_".equals(id))
            return "Operators";
        return id;
    }

    // client mirror of Zone.isSystemGroup (_NAME_ groups can't be renamed/deleted). duplicated so it's safe from client screens.
    public static boolean isSystem(String id)
    {
        return id != null && id.length() >= 2 && id.startsWith("_") && id.endsWith("_");
    }
}
