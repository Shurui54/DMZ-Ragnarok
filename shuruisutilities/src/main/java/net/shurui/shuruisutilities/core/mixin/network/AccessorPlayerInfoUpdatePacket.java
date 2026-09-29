package net.shurui.shuruisutilities.core.mixin.network;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;

/**
 * Accessor for the private final {@code entries} list of {@link ClientboundPlayerInfoUpdatePacket}. The vanish
 * tab-list filter ({@link MixinServerPlayNetHandlerVanish}) uses this to overwrite a freshly built packet's
 * entry list with the (immutable, snapshot-preserving) original entries minus any vanished players, so we keep
 * the exact latency / gamemode / display-name each entry carried instead of rebuilding them from live players.
 */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface AccessorPlayerInfoUpdatePacket
{
    @Accessor("entries")
    @Mutable
    void su$setEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
