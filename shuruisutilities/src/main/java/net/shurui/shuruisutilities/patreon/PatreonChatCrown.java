package net.shurui.shuruisutilities.patreon;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.core.moduleLauncher.ModuleLauncher;

/**
 * The supporter crown in plain game chat when the private Chat module is not running (no Ragnarok Key, or Chat
 * switched off): {@code <crown> | Name} on every server running the mods.
 *
 * <h2>Never doubled, keyed output unchanged</h2>
 * With the Chat module up, its own header draws the crown ({@code ModuleChat.getChatHeader}) and the line never
 * reaches vanilla's chat broadcast at all: ModuleChat cancels the {@code ServerChatEvent} and delivers the line
 * itself. This path is the vanilla broadcast ({@code PlayerList.broadcastChatMessage} for a player sender, reached
 * through {@code MixinPlayerListChatCrown}), and it also refuses to act while the Chat module is loaded, so the two
 * can never both decorate one line.
 *
 * <p>Only the sender's NAME in the chat type binding changes. The message body, its signature and the chat type are
 * untouched, so secure chat, reporting and every other listener see exactly what they saw before. Deliberately
 * not the Forge {@code NameFormat} hook, which would put the glyph into every display-name string on the server
 * (death messages, announcements, and about sixty {@code getDisplayName().getString()} reads).
 *
 * <p>A disguised sender shows the TARGET's crown, or none when the target has none, never their own, the same
 * rule the Chat module follows ({@code DisguiseView.crownCodepoint}).
 */
public final class PatreonChatCrown
{
    private PatreonChatCrown()
    {
    }

    private static final String CHAT_MODULE = "Chat";
    private static final String PATREON_MODULE = "Patreon";

    /** Whether this path decorates chat here: the public Patreon module is loaded and the private Chat module is not. */
    public static boolean active()
    {
        try
        {
            return ModuleLauncher.getModuleContainer(CHAT_MODULE) == null
                    && ModuleLauncher.getModuleContainer(PATREON_MODULE) != null
                    && net.shurui.shuruisutilities.core.config.PublicContent.moduleEntitled(PATREON_MODULE);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** The crown this sender shows in chat: a disguise's target crown when disguised, else their own. 0 for none. */
    public static int crownOf(ServerPlayer sender)
    {
        if (sender == null)
            return 0;
        int disguised = net.shurui.shuruisutilities.disguise.DisguiseState.visibleCrownCodepoint(sender.getUUID());
        if (disguised >= 0)
            return PatreonCrowns.isCrown(disguised) ? disguised : 0;
        return PatreonCrowns.codepointFor(sender.getUUID());
    }

    /**
     * The chat binding with the crown in front of the sender's name, or {@code bound} unchanged when this path is
     * off here, the sender shows no crown, or the name already carries one.
     */
    public static ChatType.Bound decorate(ServerPlayer sender, ChatType.Bound bound)
    {
        if (bound == null || sender == null || !active())
            return bound;
        int cp = crownOf(sender);
        if (cp <= 0 || startsWithCrown(bound.name()))
            return bound;
        Component name = PatreonCrowns.decorate(cp, bound.name());
        return new ChatType.Bound(bound.chatType(), name, bound.targetName());
    }

    /** True when the component's first visible character is already a crown glyph (a second decorator ran first). */
    private static boolean startsWithCrown(Component name)
    {
        String s = name == null ? "" : name.getString();
        return !s.isEmpty() && PatreonCrowns.isCrown(s.charAt(0));
    }
}
