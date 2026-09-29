package net.shurui.shuruisutilities.shard;

import java.util.Locale;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The short label that says which server somebody is on: OW1, OW2, SMP.
 *
 * <h2>Why a label and not the server id</h2>
 * The ids are configuration ({@code open-1}, {@code open-2}, {@code smp}) and they are too long to sit next to
 * every name in a tab list without pushing the rows out. The label is what a player reads, so it is derived from
 * the id by a rule every server applies identically: strip anything that is not a letter or a digit, uppercase it,
 * and shorten a leading {@code OPEN} to {@code OW}. {@code open-1} becomes OW1, {@code smp} becomes SMP,
 * {@code hub} becomes HUB.
 *
 * <p>Deriving rather than configuring matters more than it looks. Every server has to label the OTHER servers'
 * players, not just its own, so a configured table would have to be identical on every shard or the same player
 * would carry different labels depending on who was looking. A rule cannot drift.
 * {@link ShardConfig.Values#serverTags} is available on top for a name the rule would not produce, and an operator
 * using it does have to copy it around, which is the trade they are choosing to make.
 *
 * <h2>Where it is used</h2>
 * Three places, all reading from here so a change to the rule reaches all of them: the chat header
 * ({@code ModuleChat.getChatHeader}), the tab list rows for local players ({@code ShardTabName}), and the tab list
 * rows synthesised for players on other servers ({@code ShardTabList.addPacket}).
 */
public final class ShardTags
{
    private ShardTags() {}

    /** Applied only when the shard layer is live: a single server has nothing to distinguish. */
    public static boolean enabled()
    {
        try
        {
            return ShardSync.active() && ShardConfig.get().showServerTags;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** This server's own label, for decorating players connected here. */
    public static String selfTag()
    {
        return tagFor(ShardConfig.get().serverId);
    }

    /** The label for a server id, empty when there is nothing sensible to show. */
    public static String tagFor(String serverId)
    {
        if (serverId == null || serverId.isBlank())
            return "";
        Map<String, String> overrides = ShardConfig.get().serverTags;
        if (overrides != null)
        {
            for (Map.Entry<String, String> e : overrides.entrySet())
            {
                if (serverId.equalsIgnoreCase(e.getKey()) && e.getValue() != null && !e.getValue().isBlank())
                    return e.getValue().trim();
            }
        }
        String derived = serverId.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (derived.startsWith("OPEN") && derived.length() > 4)
            derived = "OW" + derived.substring(4);
        return derived;
    }

    /**
     * The bracketed suffix for a server id, or null when tags are off or the id yields no label.
     *
     * <p>Dark grey on purpose: it has to be readable at a glance and then ignorable, because it appears on every
     * line of chat and every row of the tab list. A colour that competes with rank badges and prestige name
     * colours would make both harder to read.
     */
    public static Component suffix(String serverId)
    {
        if (!enabled())
            return null;
        String tag = tagFor(serverId);
        if (tag.isEmpty())
            return null;
        return Component.literal(" [" + tag + "]").withStyle(ChatFormatting.DARK_GRAY);
    }

    /**
     * A name with its server label appended, or the name unchanged when there is nothing to add.
     *
     * <p>Always builds a new component rather than mutating the one passed in: the callers hand this display
     * names and chat headers that other code is still holding, and appending in place would decorate them twice
     * on the next call.
     */
    public static Component decorate(Component name, String serverId)
    {
        Component suffix = suffix(serverId);
        if (suffix == null)
            return name;
        MutableComponent out = Component.empty();
        if (name != null)
            out.append(name);
        return out.append(suffix);
    }
}
