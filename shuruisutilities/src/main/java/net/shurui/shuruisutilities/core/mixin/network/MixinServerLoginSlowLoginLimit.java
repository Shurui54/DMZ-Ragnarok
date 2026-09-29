package net.shurui.shuruisutilities.core.mixin.network;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import net.minecraft.server.network.ServerLoginPacketListenerImpl;

/**
 * Gives a joining client two minutes, not thirty seconds, to finish logging in.
 *
 * <p>Vanilla kicks with {@code multiplayer.disconnect.slow_login} when {@code tick} counts to 600 (30 seconds) before
 * the login completes. Forge runs its whole handshake inside that window: the mod list, every registry snapshot and
 * the config sync, each of which the CLIENT has to process and answer before the server can move on. With this pack
 * that is a lot of work, and on a slower machine, a first join, or a Velocity server switch (which repeats the
 * handshake) it can run past 30 seconds while the server itself is idle. On 2026-09-13 that dropped a brand new player
 * twice before their third try got in, and dropped three players switching to smp at once, with the server's own tick
 * never over 134 ms.
 *
 * <p>Only the constant compared in {@code tick} changes. A client that never answers is still dropped, just later.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class MixinServerLoginSlowLoginLimit
{
    /**
     * 2400 ticks = 120 seconds. This governs ONLY the login completion tick budget: how long the server waits for the
     * client to finish the handshake before kicking with {@code multiplayer.disconnect.slow_login}. It is NOT the netty
     * read timeout (idle-socket cut) that surfaced the 30 s cross-shard drops: that is Forge's {@code forge.readTimeout},
     * pinned to 120 s to match this budget in the dmz_ragnarok @Mod constructor (DmzRagnarok). Keep the two in step.
     */
    private static final int LOGIN_TICK_LIMIT = 2400;

    @ModifyConstant(method = "tick", constant = @Constant(intValue = 600))
    private int su$slowLoginLimit(int original)
    {
        return LOGIN_TICK_LIMIT;
    }
}
