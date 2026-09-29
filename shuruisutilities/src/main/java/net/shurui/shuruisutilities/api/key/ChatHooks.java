package net.shurui.shuruisutilities.api.key;

import java.util.UUID;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE chat module (logic in the Ragnarok Key, {@code dmz_ragnarok_key}): the chat header,
 * nicknames, mutes, channels (staff, admin, social spy), private messages, group messages, timed messages, the
 * censor, scoreboard colours and mail. Core keeps {@code chat.ChatDelivery} and its {@code Handler} interface (the
 * separate translator mod reflects that FQN), {@code chat.ChatConfig} (the Chat.toml spec), the persisted mail
 * records ({@code chat.Mails}, DataManager folder "Mails", and {@code chat.Mail}), {@code ChatOutputHandler}, and
 * {@code chat.ChatChannels} as a facade over this hook for the shard layer and the guild chat mirror.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false, channel lines go nowhere,
 * reply pairings are not recorded, and chat replacements leave a message as it is. Plain game chat is vanilla,
 * decorated only by the public supporter crown ({@code patreon.PatreonChatCrown}, which draws while no "Chat" module
 * is loaded). The Chat.toml and the Mails records are never read or written keyless.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class ChatHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "chat";

    private ChatHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the chat module's logic is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** Deliver a line to a channel here and publish it to the network. Keyless: dropped. */
        default void send(String channel, Component line)
        {
        }

        /** Deliver a line to everyone on this server who may read the channel. Keyless: dropped. */
        default void deliverLocal(String channel, Component line)
        {
        }

        /** The social spy line for a private message. Keyless: an empty component (it goes nowhere anyway). */
        default Component formatSpyMessage(String from, String to, Component text)
        {
            return Component.empty();
        }

        /** The social spy line for a guild or ally chat line. Keyless: an empty component. */
        default Component formatSpyGuild(String guildName, String from, String text)
        {
            return Component.empty();
        }

        /** Remember who {@code local} last wrote to or heard from, for /reply. Keyless: not recorded. */
        default void recordCorrespondent(Player local, UUID other, String otherName)
        {
        }

        /** Expand chat replacements (colour tokens, constants, script arguments). Keyless: the message unchanged. */
        default String processChatReplacements(CommandSourceStack sender, String message)
        {
            return message;
        }
    }

    /** The keyless default until the key installs its own. Never null. */
    private static volatile Impl impl = new Impl() {};

    /** Install the key's implementation and mark the feature. Called once from {@code RagnarokKeyMod}. */
    public static void install(Impl i)
    {
        if (i == null)
            return;
        impl = i;
        KeyFeatures.mark(FEATURE_ID);
    }

    /** The live implementation (never null: the keyless default until the key installs). */
    public static Impl get()
    {
        return impl;
    }

    /** Whether the chat module's logic is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
