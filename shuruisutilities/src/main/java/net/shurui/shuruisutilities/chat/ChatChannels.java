package net.shurui.shuruisutilities.chat;

import net.minecraft.network.chat.Component;

import net.shurui.shuruisutilities.api.key.ChatHooks;

/**
 * Facade for the chat channels (staff, admin, social spy), whose routing lives in the Ragnarok Key. Core's shard
 * layer (ShardChat, ShardMessage) and the guild chat mirror call these statics; each forwards to {@link ChatHooks},
 * whose keyless answer is "the line goes nowhere". The channel ids are the wire values in the shard chat table. No
 * logic here.
 */
public final class ChatChannels
{
    private ChatChannels() {}

    /** Channel ids as they travel on the wire and sit in the shard chat table. */
    public static final String CH_STAFF = "staff";
    public static final String CH_ADMIN = "admin";
    public static final String CH_SPY = "spy";

    /** Deliver a line to a channel on this server and publish it to the network. */
    public static void send(String channel, Component line)
    {
        ChatHooks.get().send(channel, line);
    }

    /** Hand a line to everyone on THIS server who may read that channel. */
    public static void deliverLocal(String channel, Component line)
    {
        ChatHooks.get().deliverLocal(channel, line);
    }

    /** A spied private message, both ends named. */
    public static Component formatSpyMessage(String from, String to, Component text)
    {
        return ChatHooks.get().formatSpyMessage(from, to, text);
    }

    /** A spied guild or ally line, named so a spy can tell which guild it came from. */
    public static Component formatSpyGuild(String guildName, String from, String text)
    {
        return ChatHooks.get().formatSpyGuild(guildName, from, text);
    }
}
