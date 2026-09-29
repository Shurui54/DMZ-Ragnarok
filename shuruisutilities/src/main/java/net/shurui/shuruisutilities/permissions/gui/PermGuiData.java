package net.shurui.shuruisutilities.permissions.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;

// side-agnostic snapshot for the permissions editor. a server can have tens of thousands of nodes (way more
// than one 1MB payload), so the node list is NOT sent up front:
//   MODE_LIST  = the group list
//   MODE_EDIT  = one group's properties + the category names as tabs, no nodes
//   MODE_NODES = a bounded slice of nodes (one category tab or a search) with state (0 unset, 1 allow,
//                2 deny), tagged with the context that requested them
public class PermGuiData
{
    public static final int MODE_LIST = 0;
    public static final int MODE_EDIT = 1;
    public static final int MODE_NODES = 2;

    public int mode;
    public List<String> groups = new ArrayList<>();

    public String group = "";
    public String prefix = "";
    public String suffix = "";
    public String priority = "";
    public String parents = "";
    public String rank = "";

    // MODE_EDIT: category names shown as tabs
    public List<String> categories = new ArrayList<>();

    // MODE_NODES: which view these belong to (e.g. cat:teleport or search:foo)
    public String context = "";
    public List<String[]> perms = new ArrayList<>(); // {node, "0"|"1"|"2", description}

    public void write(FriendlyByteBuf buf)
    {
        buf.writeVarInt(mode);
        if (mode == MODE_LIST)
        {
            buf.writeVarInt(groups.size());
            for (String g : groups)
                buf.writeUtf(g);
            return;
        }
        if (mode == MODE_EDIT)
        {
            buf.writeUtf(group);
            buf.writeUtf(prefix);
            buf.writeUtf(suffix);
            buf.writeUtf(priority);
            buf.writeUtf(parents);
            buf.writeUtf(rank);
            buf.writeVarInt(categories.size());
            for (String c : categories)
                buf.writeUtf(c);
            return;
        }
        // MODE_NODES
        buf.writeUtf(group);
        buf.writeUtf(context);
        buf.writeVarInt(perms.size());
        for (String[] p : perms)
        {
            buf.writeUtf(p[0]);
            buf.writeUtf(p[1]);
            buf.writeUtf(p.length > 2 && p[2] != null ? p[2] : "");
        }
    }

    public static PermGuiData read(FriendlyByteBuf buf)
    {
        PermGuiData d = new PermGuiData();
        d.mode = buf.readVarInt();
        if (d.mode == MODE_LIST)
        {
            int n = buf.readVarInt();
            for (int i = 0; i < n; i++)
                d.groups.add(buf.readUtf());
            return d;
        }
        if (d.mode == MODE_EDIT)
        {
            d.group = buf.readUtf();
            d.prefix = buf.readUtf();
            d.suffix = buf.readUtf();
            d.priority = buf.readUtf();
            d.parents = buf.readUtf();
            d.rank = buf.readUtf();
            int n = buf.readVarInt();
            for (int i = 0; i < n; i++)
                d.categories.add(buf.readUtf());
            return d;
        }
        // MODE_NODES
        d.group = buf.readUtf();
        d.context = buf.readUtf();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            d.perms.add(new String[] { buf.readUtf(), buf.readUtf(), buf.readUtf() });
        return d;
    }
}
