package net.shurui.dev.shuruis_dmz_tournaments.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.shurui.dev.shuruis_dmz_tournaments.Shuruis_dmz_tournaments;

/** Forge {@link SimpleChannel} for the tournament addon. */
public final class TournamentNet {
    private static final String PROTOCOL = "1";
    private static SimpleChannel channel;
    private static int packetId = 0;

    private TournamentNet() {}

    public static void register() {
        channel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(Shuruis_dmz_tournaments.MODID, "tournaments_main"),
                () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

        channel.registerMessage(packetId++, OpenSignupPacket.class,
                OpenSignupPacket::encode, OpenSignupPacket::decode, OpenSignupPacket::handle);
        channel.registerMessage(packetId++, SignupActionPacket.class,
                SignupActionPacket::encode, SignupActionPacket::decode, SignupActionPacket::handle);
        channel.registerMessage(packetId++, OpenEditorPacket.class,
                OpenEditorPacket::encode, OpenEditorPacket::decode, OpenEditorPacket::handle);
        channel.registerMessage(packetId++, SaveDefPacket.class,
                SaveDefPacket::encode, SaveDefPacket::decode, SaveDefPacket::handle);
        channel.registerMessage(packetId++, DeleteDefPacket.class,
                DeleteDefPacket::encode, DeleteDefPacket::decode, DeleteDefPacket::handle);
        channel.registerMessage(packetId++, SetBoundsPacket.class,
                SetBoundsPacket::encode, SetBoundsPacket::decode, SetBoundsPacket::handle);
        channel.registerMessage(packetId++, BoundsResultPacket.class,
                BoundsResultPacket::encode, BoundsResultPacket::decode, BoundsResultPacket::handle);
        channel.registerMessage(packetId++, OpenHubPacket.class,
                OpenHubPacket::encode, OpenHubPacket::decode, OpenHubPacket::handle);
        channel.registerMessage(packetId++, OpenEditorRequestPacket.class,
                OpenEditorRequestPacket::encode, OpenEditorRequestPacket::decode, OpenEditorRequestPacket::handle);
        channel.registerMessage(packetId++, OpenBrowserPacket.class,
                OpenBrowserPacket::encode, OpenBrowserPacket::decode, OpenBrowserPacket::handle);
        channel.registerMessage(packetId++, OpenSignupRequestPacket.class,
                OpenSignupRequestPacket::encode, OpenSignupRequestPacket::decode, OpenSignupRequestPacket::handle);
        channel.registerMessage(packetId++, OpenLoadoutPacket.class,
                OpenLoadoutPacket::encode, OpenLoadoutPacket::decode, OpenLoadoutPacket::handle);
        channel.registerMessage(packetId++, SaveLoadoutPacket.class,
                SaveLoadoutPacket::encode, SaveLoadoutPacket::decode, SaveLoadoutPacket::handle);
        channel.registerMessage(packetId++, FightersSyncPacket.class,
                FightersSyncPacket::encode, FightersSyncPacket::decode, FightersSyncPacket::handle);
        channel.registerMessage(packetId++, StatGemChoicePacket.class,
                StatGemChoicePacket::encode, StatGemChoicePacket::decode, StatGemChoicePacket::handle);
        channel.registerMessage(packetId++, TitleListSyncPacket.class,
                TitleListSyncPacket::encode, TitleListSyncPacket::decode, TitleListSyncPacket::handle);
    }

    /**
     * Build the current title id/display pair from the server's authoritative definitions. Source is
     * {@link net.shurui.dev.shuruis_dmz_tournaments.reward.TitleManager#definitions()} so the packet carries the config
     * rows AND the built-in role titles, the same set an admin may grant, in the format {@code id|Display|Tag}.
     */
    private static TitleListSyncPacket buildTitlePacket() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.List<String> displays = new java.util.ArrayList<>();
        for (String def : net.shurui.dev.shuruis_dmz_tournaments.reward.TitleManager.definitions()) {
            String[] parts = def.split("\\|", -1);
            String id = parts.length > 0 ? parts[0] : def;
            if (id.isEmpty()) continue;
            ids.add(id);
            // Display name is the second field; fall back to the id if a definition omits it.
            displays.add(parts.length > 1 && !parts[1].isEmpty() ? parts[1] : id);
        }
        return new TitleListSyncPacket(ids, displays);
    }

    /** Send the recognised title list to one client. Call on that player's login. */
    public static void sendTitlesTo(ServerPlayer player) {
        sendToPlayer(buildTitlePacket(), player);
    }

    /** Push the recognised title list to every online client. Call after the title definitions could have changed. */
    public static void broadcastTitles(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        TitleListSyncPacket packet = buildTitlePacket();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendToPlayer(packet, p);
        }
    }

    /** Push the tournament-fighter set to every online client. Call after it changes. */
    public static void broadcastFighters(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        FightersSyncPacket packet = new FightersSyncPacket(
                net.shurui.dev.shuruis_dmz_tournaments.character.TournamentFighters.serverSnapshot());
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendToPlayer(packet, p);
        }
    }

    /** Send the tournament-fighter set to one client. Call on that player's login. */
    public static void sendFightersTo(ServerPlayer player) {
        sendToPlayer(new FightersSyncPacket(
                net.shurui.dev.shuruis_dmz_tournaments.character.TournamentFighters.serverSnapshot()), player);
    }

    /** Open the attack picker to edit an existing loadout. */
    public static void openLoadout(ServerPlayer player) {
        sendLoadout(player, false);
    }

    /** Open character creation to build a fresh fighter. */
    public static void openCreation(ServerPlayer player) {
        sendLoadout(player, true);
    }

    private static void sendLoadout(ServerPlayer player, boolean creation) {
        // Offer only the moves this player could legitimately hold on a real character: honours race locks (the
        // shadow dragon kit) and title / unlock gates (hakai, the sphere of destruction, the mini clone). The same
        // gate re-runs server-side in SaveLoadoutPacket, so a crafted packet cannot pick anything hidden here.
        sendToPlayer(new OpenLoadoutPacket(
                net.shurui.dev.shuruis_dmz_tournaments.character.TournamentMoveAccess.filter(player,
                        net.shurui.dev.shuruis_dmz_tournaments.character.TournamentAttacks.kiIds()),
                net.shurui.dev.shuruis_dmz_tournaments.character.TournamentMoveAccess.filter(player,
                        net.shurui.dev.shuruis_dmz_tournaments.character.TournamentAttacks.strikeIds()),
                net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter.getLoadout(player),
                net.shurui.dev.shuruis_dmz_tournaments.character.TournamentAttacks.KI_SLOTS,
                net.shurui.dev.shuruis_dmz_tournaments.character.TournamentAttacks.STRIKE_SLOTS,
                creation), player);
    }

    public static void openHub(ServerPlayer player) {
        sendToPlayer(new OpenHubPacket(), player);
    }

    /** Gather every configured tournament and open the list editor. */
    public static void openTournamentEditor(ServerPlayer player) {
        java.util.List<net.minecraft.nbt.CompoundTag> defs = new java.util.ArrayList<>();
        for (var def : net.shurui.dev.shuruis_dmz_tournaments.data.TournamentData.get(player.getServer()).allDefs().values()) {
            defs.add(def.save());
        }
        sendToPlayer(new OpenEditorPacket(defs), player);
    }

    public static void sendToPlayer(Object packet, ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendToServer(Object packet) {
        channel.sendToServer(packet);
    }
}
