package net.shurui.shuruisutilities.ritual;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

/**
 * A server-wide moment: one line in chat and a title across everybody's screen.
 *
 * <p>The same shape the shadow dragon event uses when the balls are defiled, because these are the same KIND of thing:
 * something rare enough that the whole server should look up, not a message for the one player it happened to. Sharing
 * the treatment is the point, so a player who has seen the malice announcement recognises this as a sibling of it.
 *
 * <p>Colour comes from {@link ChatFormatting} on the component rather than from ampersand codes inside the string,
 * because these are translatable and the code would be baked into a lang value where every translator would have to
 * copy it correctly and the server could not format it anyway.
 */
public final class RitualAnnouncer
{
    private RitualAnnouncer() {}

    // Fade in, hold, fade out. Matches the defiled-balls announcement exactly.
    private static final int FADE_IN = 10;
    private static final int STAY = 70;
    private static final int FADE_OUT = 20;

    /**
     * Announce to everyone online.
     *
     * @param chatKey     lang key for the chat line, given the player's name
     * @param titleKey    lang key for the big line
     * @param subtitleKey lang key for the small line, given the player's name
     * @param colour      the accent the whole announcement is drawn in
     */
    public static void announce(MinecraftServer server, ServerPlayer actor, String chatKey, String titleKey,
            String subtitleKey, ChatFormatting colour)
    {
        if (server == null || actor == null)
            return;
        try
        {
            Component who = actor.getDisplayName();
            ChatOutputHandler.broadcast(Component.translatable(chatKey, who).withStyle(colour));

            Component title = Component.translatable(titleKey).withStyle(colour, ChatFormatting.BOLD);
            Component subtitle = Component.translatable(subtitleKey, who).withStyle(ChatFormatting.GRAY);
            for (ServerPlayer viewer : server.getPlayerList().getPlayers())
            {
                // Per viewer, not per broadcast: one player opting out of SU's on-screen announcements must not
                // silence the title for everybody else. The chat line above is not gated and reaches everyone.
                if (!net.shurui.shuruisutilities.announce.AnnouncementPrefs.shown(viewer))
                    continue;
                viewer.connection.send(new ClientboundSetTitlesAnimationPacket(FADE_IN, STAY, FADE_OUT));
                viewer.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
                viewer.connection.send(new ClientboundSetTitleTextPacket(title));
            }
        }
        catch (Throwable ignored)
        {
            // An announcement is a flourish on top of the thing that already happened. It must never be able to
            // undo a granted form or a restored ball set.
        }
    }

    /**
     * Announce a server wide moment that has NO player actor, substituting a supplied component (for instance a
     * dragon ball set's name) wherever the actor's name would go. Same title treatment and chat broadcast as
     * {@link #announce}, so a timed event (the ball dormancy wake) reads as a sibling of the player driven ones.
     *
     * <p>The chat line is broadcast LOCALLY here; a caller that also wants the rest of the network to see it should
     * additionally publish it with {@code ShardChat.announceRemote} (which does not echo locally), so the line
     * shows exactly once per shard.
     *
     * @param nameArg the component substituted into the chat and subtitle lang keys
     */
    public static void announceServerWide(MinecraftServer server, String chatKey, String titleKey,
            String subtitleKey, Component nameArg, ChatFormatting colour)
    {
        if (server == null)
            return;
        try
        {
            ChatOutputHandler.broadcast(Component.translatable(chatKey, nameArg).withStyle(colour));

            Component title = Component.translatable(titleKey).withStyle(colour, ChatFormatting.BOLD);
            Component subtitle = Component.translatable(subtitleKey, nameArg).withStyle(ChatFormatting.GRAY);
            for (ServerPlayer viewer : server.getPlayerList().getPlayers())
            {
                if (!net.shurui.shuruisutilities.announce.AnnouncementPrefs.shown(viewer))
                    continue;
                viewer.connection.send(new ClientboundSetTitlesAnimationPacket(FADE_IN, STAY, FADE_OUT));
                viewer.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
                viewer.connection.send(new ClientboundSetTitleTextPacket(title));
            }
        }
        catch (Throwable ignored)
        {
            // An announcement is a flourish; it must never be able to undo the wake that already happened.
        }
    }
}
