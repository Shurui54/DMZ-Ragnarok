package net.shurui.dev.shuruis_raid_bosses.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.shurui.dev.shuruis_raid_bosses.Shuruis_raid_bosses;

/** Forge {@link SimpleChannel} for the raid addon: opens the sign-up/editor GUIs (S2C) and relays actions (C2S). */
public final class RaidNet {
    private static final String PROTOCOL = "1";
    private static SimpleChannel channel;
    private static int packetId = 0;

    private RaidNet() {}

    public static void register() {
        channel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(Shuruis_raid_bosses.MODID, "raid_main"),
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
        channel.registerMessage(packetId++, RequestSignupPacket.class,
                RequestSignupPacket::encode, RequestSignupPacket::decode, RequestSignupPacket::handle);
        channel.registerMessage(packetId++, ZSoulInvestC2S.class,
                ZSoulInvestC2S::encode, ZSoulInvestC2S::decode, ZSoulInvestC2S::handle);
        channel.registerMessage(packetId++, RaidHudPacket.class,
                RaidHudPacket::encode, RaidHudPacket::decode, RaidHudPacket::handle);
        // Rift editor. Appended, never inserted: ids are positional, so inserting above renumbers
        // everything after and an old-order client decodes the wrong packet.
        channel.registerMessage(packetId++, OpenRiftEditorPacket.class,
                OpenRiftEditorPacket::encode, OpenRiftEditorPacket::decode, OpenRiftEditorPacket::handle);
        channel.registerMessage(packetId++, SaveRiftPacket.class,
                SaveRiftPacket::encode, SaveRiftPacket::decode, SaveRiftPacket::handle);
        channel.registerMessage(packetId++, DeleteRiftPacket.class,
                DeleteRiftPacket::encode, DeleteRiftPacket::decode, DeleteRiftPacket::handle);
        // Boss music (S2C start/stop). Appended, never inserted: ids are positional (see the note above).
        channel.registerMessage(packetId++, BossMusicPacket.class,
                BossMusicPacket::encode, BossMusicPacket::decode, BossMusicPacket::handle);
    }

    public static void sendToPlayer(Object packet, ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendToServer(Object packet) {
        channel.sendToServer(packet);
    }
}
