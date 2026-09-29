package net.shurui.shuruisutilities.guilds.client;

import net.shurui.shuruisutilities.guilds.network.PacketGuildGui;

import net.minecraft.client.Minecraft;

/**
 * Client-only entry point that opens the right guild screen from a received {@link PacketGuildGui}.
 * Referenced only through {@code DistExecutor} so it never loads on a dedicated server.
 */
public final class GuildGuiClient
{
    private GuildGuiClient() {}

    public static void open(PacketGuildGui packet)
    {
        Minecraft mc = Minecraft.getInstance();
        if (packet.mode == PacketGuildGui.MODE_ADMIN)
            mc.setScreen(new GuildAdminScreen(packet.summaries, packet.salvageConfig));
        else
            mc.setScreen(new GuildScreen(packet.view));
    }

    /** Runs a server command from a GUI button (unsigned; guild commands have no signable args). */
    public static void run(String command)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null)
            mc.getConnection().sendCommand(command);
    }
}
