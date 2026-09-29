package net.shurui.dev.shuruis_raid_bosses.util;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.shurui.dev.shuruis_raid_bosses.Config;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef;

/**
 * Server-wide announcement helper: chat line, optional big on-screen title, and an optional sound,
 * all driven by the config toggles.
 */
public final class Announcer {
    private Announcer() {}

    /**
     * Mirror one announcement to every OTHER shard, so a raid opening or starting is seen network-wide. No
     * local echo (the caller already showed it), inert on a single server. Only the big announcements route
     * here; per-participant and diagnostic broadcasts stay local.
     */
    private static void mirror(Component line) {
        if (line != null) {
            net.shurui.shuruisutilities.shard.ShardChat.announceRemote(line);
        }
    }

    /** Broadcast a chat message to everyone, parsing &amp; color codes. */
    public static void broadcast(MinecraftServer server, String rawMessage) {
        broadcast(server, TextUtil.color(rawMessage));
    }

    /** Broadcast an already-built {@link Component} to everyone. */
    public static void broadcast(MinecraftServer server, Component msg) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(msg);
        }
    }

    /** Chat + optional title + sound, both already-built {@link Component}s. {@code titleText} null = no title. */
    public static void announce(MinecraftServer server, Component chatMessage, Component titleText, RaidBossDef def) {
        broadcast(server, chatMessage);
        mirror(chatMessage);
        sendTitleAndSound(server, titleText,
                def == null ? Config.USE_TITLES.get() : def.useTitles,
                def == null ? Config.ANNOUNCE_SOUND.get() : def.announceSound);
    }

    /** Component form of {@link #announce(MinecraftServer, String, String)} for global-config announcements. */
    public static void announce(MinecraftServer server, Component chatMessage, Component titleText) {
        broadcast(server, chatMessage);
        mirror(chatMessage);
        sendTitleAndSound(server, titleText, Config.USE_TITLES.get(), Config.ANNOUNCE_SOUND.get());
    }

    /** Broadcast chat + (optionally) an on-screen title + sound, using a raid's own settings. */
    public static void announce(MinecraftServer server, String chatMessage, String titleText, RaidBossDef def) {
        announce(server, chatMessage, titleText,
                def == null ? Config.USE_TITLES.get() : def.useTitles,
                def == null ? Config.ANNOUNCE_SOUND.get() : def.announceSound);
    }

    /**
     * Raw ({@code &}-coded) config-template chat line plus an already-translated title {@link Component}
     * from the language file. {@code titleText} may be {@code null}.
     */
    public static void announce(MinecraftServer server, String chatMessage, Component titleText, RaidBossDef def) {
        broadcast(server, chatMessage);
        mirror(TextUtil.color(chatMessage));
        sendTitleAndSound(server, titleText,
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
        sendTitleAndSound(server, titleText == null || titleText.isEmpty() ? null : TextUtil.color(titleText),
                useTitles, soundId);
    }

    /** Participant-scoped chat broadcast (Component). Audience = the given UUIDs plus online ops (level 2+). */
    public static void broadcastTo(MinecraftServer server, java.util.Collection<java.util.UUID> audience, Component msg) {
        broadcastTo(server, audience, msg, true);
    }

    /**
     * Participant-scoped chat broadcast, op fan-out optional. Ops watching a public raid is the default;
     * a solo tear run passes {@code includeOps = false} so its once-a-second line is not noise to every op.
     */
    public static void broadcastTo(MinecraftServer server, java.util.Collection<java.util.UUID> audience,
                                   Component msg, boolean includeOps) {
        for (ServerPlayer p : recipients(server, audience, includeOps)) {
            p.sendSystemMessage(msg);
        }
    }

    /** Participant-scoped: raw &-coded chat template + already-translated title Component + raid settings. */
    public static void announceTo(MinecraftServer server, java.util.Collection<java.util.UUID> audience,
                                  String chatMessage, Component titleText, RaidBossDef def) {
        announceTo(server, audience, TextUtil.color(chatMessage), titleText, def);
    }

    /** Participant-scoped: already-built chat Component + title Component + raid settings. */
    public static void announceTo(MinecraftServer server, java.util.Collection<java.util.UUID> audience,
                                  Component chatMessage, Component titleText, RaidBossDef def) {
        java.util.List<ServerPlayer> recipients = recipients(server, audience);
        for (ServerPlayer p : recipients) {
            p.sendSystemMessage(chatMessage);
        }
        sendTitleAndSoundTo(recipients, titleText,
                def == null ? Config.USE_TITLES.get() : def.useTitles,
                def == null ? Config.ANNOUNCE_SOUND.get() : def.announceSound);
    }

    /** Online members of {@code audience} plus online ops (level 2+) so staff can monitor a running raid. */
    private static java.util.List<ServerPlayer> recipients(MinecraftServer server, java.util.Collection<java.util.UUID> audience) {
        return recipients(server, audience, true);
    }

    private static java.util.List<ServerPlayer> recipients(MinecraftServer server,
                                                           java.util.Collection<java.util.UUID> audience,
                                                           boolean includeOps) {
        java.util.List<ServerPlayer> out = new java.util.ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if ((audience != null && audience.contains(p.getUUID())) || (includeOps && p.hasPermissions(2))) {
                out.add(p);
            }
        }
        return out;
    }

    private static void sendTitleAndSoundTo(java.util.List<ServerPlayer> recipients, Component titleText,
                                            boolean useTitles, String soundId) {
        SoundEvent sound = resolveSound(soundId);
        for (ServerPlayer p : recipients) {
            if (useTitles && titleText != null && titlesWanted(p)) {
                p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
                p.connection.send(new ClientboundSetTitleTextPacket(titleText));
            }
            if (sound != null) {
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                        BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
                        SoundSource.MASTER, p.getX(), p.getY(), p.getZ(), 1.0f, 1.0f, p.getRandom().nextLong()));
            }
        }
    }

    /**
     * Sign-up announcement: appends a clickable {@code [JOIN]} button running {@code joinCommand}. Title +
     * sound follow the raid's own settings.
     */
    public static void announceSignup(MinecraftServer server, String chatMessage, String titleText,
                                      RaidBossDef def, String joinCommand) {
        boolean useTitles = def == null ? Config.USE_TITLES.get() : def.useTitles;
        String soundId = def == null ? Config.ANNOUNCE_SOUND.get() : def.announceSound;
        Component line = Component.empty()
                .append(TextUtil.color(chatMessage))
                .append(joinButton(joinCommand));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(line);
        }
        // The clickable line crosses the network: the Join command survives serialization and works on
        // every server because joining is network-aware.
        mirror(line);
        sendTitleAndSound(server, titleText == null || titleText.isEmpty() ? null : TextUtil.color(titleText),
                useTitles, soundId);
    }

    private static Component joinButton(String joinCommand) {
        return Component.literal("  ").append(Component.translatable("announce.dmz_ragnarok.raid.join_button")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, joinCommand))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("announce.dmz_ragnarok.raid.join_button_hover")))));
    }

    /**
     * Whether this player still wants the suite's own on-screen announcements. The Suite Announcements switch in
     * DragonMineZ's settings menu is a per player preference the client states to the server, so the check has to
     * happen here, per recipient, rather than at the call sites which decide for everybody at once.
     *
     * <p>Only the TITLE is gated, never the chat line and never the sound, which matches how the same preference
     * is honoured for regions, the corrupted event and the ritual announcer. Fails open: a player the server has
     * heard nothing from is shown the title.
     */
    private static boolean titlesWanted(ServerPlayer p) {
        return net.shurui.shuruisutilities.announce.AnnouncementPrefs.shown(p);
    }

    private static void sendTitleAndSound(MinecraftServer server, Component titleText,
                                          boolean useTitles, String soundId) {
        SoundEvent sound = resolveSound(soundId);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (useTitles && titleText != null && titlesWanted(p)) {
                p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
                p.connection.send(new ClientboundSetTitleTextPacket(titleText));
            }
            if (sound != null) {
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                        BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
                        SoundSource.MASTER, p.getX(), p.getY(), p.getZ(), 1.0f, 1.0f, p.getRandom().nextLong()));
            }
        }
    }

    private static SoundEvent resolveSound(String id) {
        if (id == null || id.isBlank()) return null;
        ResourceLocation loc = ResourceLocation.tryParse(id);
        return loc == null ? null : BuiltInRegistries.SOUND_EVENT.get(loc);
    }
}
