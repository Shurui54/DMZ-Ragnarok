package net.shurui.shuruisutilities.shard;

import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.api.key.ShardHooks;

/**
 * One chat stream across every server on the network: a FACADE over {@link ShardHooks}. The chat table, its pollers
 * and delivery are the key's (Sh1: {@code ShardChatBus}); core keeps the channel names and these entry points. Every
 * method is a no-op keyless, exactly as it was with the network off (in particular {@link #announce} does NOT show
 * the line locally then: the caller already did).
 */
public final class ShardChat
{
    private ShardChat() {}

    /**
     * The channel an ordinary chat line rides on, and the value a pre-channels row reads as. Everything else
     * ({@code staff}, {@code admin}, {@code spy}) is delivered by {@code ChatChannels} against the READING server's
     * own permissions. Stored in the chat table: never change it.
     */
    public static final String CHANNEL_PUBLIC = "public";

    /**
     * On-screen UI deliveries (a title, or a targeted warning) rather than a chat line: the row's payload is a small
     * JSON object, not a Component. Stored in the chat table: never change it.
     */
    public static final String CHANNEL_UI = "ui";

    /** Publish one already decorated public chat line to the rest of the network. Keyless: a no-op. */
    public static void publishChat(ServerPlayer sender, Component decoratedLine, boolean senderVanished,
            String rawBody, Component bodyPrefix)
    {
        ShardHooks.get().publishChat(sender, decoratedLine, senderVanished, rawBody, bodyPrefix);
    }

    /** Say something to the whole network: shown here AND published. Keyless: a no-op. */
    public static void announce(Component line)
    {
        ShardHooks.get().announce(line);
    }

    /** Publish a line to every OTHER server, without showing it here. Keyless: a no-op. */
    public static void announceRemote(Component line)
    {
        ShardHooks.get().announceRemote(line);
    }

    /** Publish one line on a NON-public channel (staff, admin, social spy). Keyless: a no-op. */
    public static void publishChannel(String channel, Component line)
    {
        ShardHooks.get().publishChannel(channel, line);
    }

    /** Show a title (and optional subtitle) on every OTHER server. Keyless: a no-op. */
    public static void announceTitleRemote(Component title, Component subtitle, int fadeIn, int stay, int fadeOut)
    {
        ShardHooks.get().announceTitleRemote(title, subtitle, fadeIn, stay, fadeOut);
    }

    /** Deliver an on-screen warning to ONE player wherever they are on the network. Keyless: a no-op. */
    public static void sendWarning(UUID targetId, Component chatLine, Component title, Component subtitle,
            int fadeIn, int stay, int fadeOut)
    {
        ShardHooks.get().sendWarning(targetId, chatLine, title, subtitle, fadeIn, stay, fadeOut);
    }
}
