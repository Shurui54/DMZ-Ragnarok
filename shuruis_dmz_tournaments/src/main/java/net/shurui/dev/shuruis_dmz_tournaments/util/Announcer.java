package net.shurui.dev.shuruis_dmz_tournaments.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.shurui.dev.shuruis_dmz_tournaments.Config;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentDef;

/**
 * Server-wide announcement helper: chat line, optional big on-screen title, and an optional sound,
 * all driven by the config toggles.
 */
public final class Announcer {
    private Announcer() {}

    /**
     * Mirror one announcement to every OTHER server on a shard network, so a tournament opening or starting is
     * seen network-wide. No local echo (the host already showed it) and inert on a single server. Only the big
     * announcements route through here; per-participant and title-only broadcasts stay local.
     */
    private static void mirror(Component line) {
        if (line != null) {
            net.shurui.shuruisutilities.shard.ShardChat.announceRemote(line);
        }
    }

    /** Broadcast a chat message to everyone, parsing &amp; color codes. */
    public static void broadcast(MinecraftServer server, String rawMessage) {
        Component msg = TextUtil.color(rawMessage);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(msg);
        }
    }

    /**
     * Chat to players in {@code audience} plus any online operator (level 2+), so staff can monitor a running
     * event. For in-progress commentary that should not spam the whole server.
     */
    public static void broadcastTo(MinecraftServer server, java.util.Collection<java.util.UUID> audience, String rawMessage) {
        Component msg = TextUtil.color(rawMessage);
        for (ServerPlayer p : recipients(server, audience)) {
            p.sendSystemMessage(msg);
        }
    }

    /** Participant-scoped chat + optional title + sound, using a tournament's own settings. Audience rules match {@link #broadcastTo}. */
    public static void announceTo(MinecraftServer server, java.util.Collection<java.util.UUID> audience,
                                  String chatMessage, String titleText, TournamentDef def) {
        boolean useTitles = def == null ? Config.USE_TITLES.get() : def.useTitles;
        SoundEvent sound = resolveSound(def == null ? Config.ANNOUNCE_SOUND.get() : def.announceSound);
        Component msg = TextUtil.color(chatMessage);
        for (ServerPlayer p : recipients(server, audience)) {
            p.sendSystemMessage(msg);
            if (useTitles && titleText != null && !titleText.isEmpty() && titlesWanted(p)) {
                p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
                p.connection.send(new ClientboundSetTitleTextPacket(TextUtil.color(titleText)));
            }
            if (sound != null) {
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                        BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
                        SoundSource.MASTER, p.getX(), p.getY(), p.getZ(), 1.0f, 1.0f, p.getRandom().nextLong()));
            }
        }
    }

    /**
     * Whether this player still wants the suite's own on-screen announcements. The Suite Announcements switch in
     * DragonMineZ's settings menu is a per player preference the client states to the server, so the check has to
     * happen here, per recipient, rather than at the call sites which decide for everybody at once.
     *
     * <p>Only the TITLE and SUBTITLE are gated, never the chat line and never the sound, which matches how the
     * same preference is honoured for regions, the corrupted event and the ritual announcer. Fails open: a player
     * the server has heard nothing from is shown the title.
     */
    private static boolean titlesWanted(ServerPlayer p) {
        return net.shurui.shuruisutilities.announce.AnnouncementPrefs.shown(p);
    }

    /** Online members of {@code audience} plus any online operator (level 2+), who always see event commentary. */
    private static java.util.List<ServerPlayer> recipients(MinecraftServer server, java.util.Collection<java.util.UUID> audience) {
        java.util.List<ServerPlayer> out = new java.util.ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if ((audience != null && audience.contains(p.getUUID())) || p.hasPermissions(2)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Broadcast chat + (optionally) an on-screen title + sound, using a tournament's own settings. */
    public static void announce(MinecraftServer server, String chatMessage, String titleText,
                                net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentDef def) {
        announce(server, chatMessage, titleText,
                def == null ? Config.USE_TITLES.get() : def.useTitles,
                def == null ? Config.ANNOUNCE_SOUND.get() : def.announceSound);
    }

    /** Broadcast chat + (optionally) an on-screen title + sound. Used for the big moments. */
    public static void announce(MinecraftServer server, String chatMessage, String titleText) {
        announce(server, chatMessage, titleText, Config.USE_TITLES.get(), Config.ANNOUNCE_SOUND.get());
    }

    private static void announce(MinecraftServer server, String chatMessage, String titleText,
                                 boolean useTitles, String soundId) {
        broadcast(server, chatMessage);
        mirror(TextUtil.color(chatMessage));
        SoundEvent sound = resolveSound(soundId);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (useTitles && titleText != null && !titleText.isEmpty() && titlesWanted(p)) {
                p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
                p.connection.send(new ClientboundSetTitleTextPacket(TextUtil.color(titleText)));
            }
            if (sound != null) {
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                        BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
                        SoundSource.MASTER, p.getX(), p.getY(), p.getZ(), 1.0f, 1.0f, p.getRandom().nextLong()));
            }
        }
    }

    /**
     * Re-show the big "Sign-ups Open" title (plus sound) to everyone, no chat line. For the timed sign-up
     * reminder that nags players who haven't joined yet.
     */
    public static void title(MinecraftServer server, String titleText, String subtitleText, TournamentDef def) {
        boolean useTitles = def == null ? Config.USE_TITLES.get() : def.useTitles;
        SoundEvent sound = resolveSound(def == null ? Config.ANNOUNCE_SOUND.get() : def.announceSound);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (useTitles && titleText != null && !titleText.isEmpty() && titlesWanted(p)) {
                p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
                if (subtitleText != null && !subtitleText.isEmpty()) {
                    p.connection.send(new ClientboundSetSubtitleTextPacket(TextUtil.color(subtitleText)));
                }
                p.connection.send(new ClientboundSetTitleTextPacket(TextUtil.color(titleText)));
            }
            if (sound != null) {
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                        BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
                        SoundSource.MASTER, p.getX(), p.getY(), p.getZ(), 1.0f, 1.0f, p.getRandom().nextLong()));
            }
        }
    }

    /**
     * Chat line ending in clickable {@code [Join]}/{@code [Leave]} buttons wired to
     * {@code /rg tourney join|leave <name>}.
     */
    public static void broadcastJoinPrompt(MinecraftServer server, TournamentDef def, String rawPrefix) {
        Component line = Component.empty()
                .append(TextUtil.color(rawPrefix))
                .append(joinButton(def.name))
                .append(Component.literal(" "))
                .append(leaveButton(def.name));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(line);
        }
        // crosses the network; Join works everywhere because joining is network-aware and routes to the host
        mirror(line);
    }

    /** A green, bold, clickable {@code [Join]} button that runs {@code /rg tourney join <tournament name>}. */
    public static Component joinButton(String tournamentName) {
        return Component.literal("[Join]").withStyle(s -> s
                .withColor(ChatFormatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rg tourney join " + tournamentName))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        TextUtil.color("&aClick to sign up for &e" + tournamentName))));
    }

    /** A red, clickable {@code [Leave]} button that runs {@code /rg tourney leave <tournament name>}. */
    public static Component leaveButton(String tournamentName) {
        return Component.literal("[Leave]").withStyle(s -> s
                .withColor(ChatFormatting.RED)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rg tourney leave " + tournamentName))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        TextUtil.color("&cClick to withdraw from &e" + tournamentName))));
    }

    private static SoundEvent resolveSound(String id) {
        if (id == null || id.isBlank()) return null;
        ResourceLocation loc = ResourceLocation.tryParse(id);
        return loc == null ? null : BuiltInRegistries.SOUND_EVENT.get(loc);
    }
}
