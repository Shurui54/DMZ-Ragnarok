package net.shurui.dev.sdu.client;

import net.minecraft.client.Minecraft;

/**
 * Client-only handlers for addon packets. Isolated in its own class so the dedicated server never
 * classloads GUI types (it's only referenced through {@code DistExecutor}).
 */
public final class ClientPacketHandler {

    private ClientPacketHandler() {
    }

}
