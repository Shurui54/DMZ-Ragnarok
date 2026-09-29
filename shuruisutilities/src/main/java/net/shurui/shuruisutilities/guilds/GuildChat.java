package net.shurui.shuruisutilities.guilds;

import java.util.UUID;

import net.shurui.shuruisutilities.api.key.GuildHooks;
import net.shurui.shuruisutilities.guilds.model.Guild;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Guild chat channels. Each player has an active channel ({@link Channel}); messages typed in guild or
 * ally chat are delivered only to the relevant online players instead of global chat.
 *
 * <p>A FACADE since S15: the channel state and delivery live in the Ragnarok Key, reached through
 * {@link GuildHooks}. Keyless every player is on {@link Channel#PUBLIC} and nothing is delivered (there are no
 * guilds). The line formats stay here: they are presentation only.
 */
public final class GuildChat
{
    private GuildChat() {}

    public enum Channel
    {
        PUBLIC, GUILD, ALLY
    }

    public static Channel channelOf(UUID player)
    {
        return GuildHooks.get().chatChannel(player);
    }

    public static void setChannel(UUID player, Channel channel)
    {
        GuildHooks.get().setChatChannel(player, channel);
    }

    /** Sends a message to every online member of {@code guild}. */
    public static void sendToGuild(Guild guild, Component message)
    {
        GuildHooks.get().sendToGuild(guild, message);
    }

    /** Sends a message to the guild plus every allied guild's online members. */
    public static void sendToAllies(Guild guild, Component message)
    {
        GuildHooks.get().sendToAllies(guild, message);
    }

    public static Component formatGuild(String senderName, String text)
    {
        return Component.literal("[Guild] ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(senderName + ": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
    }

    public static Component formatAlly(String senderName, String text)
    {
        return Component.literal("[Ally] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(senderName + ": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
    }
}
