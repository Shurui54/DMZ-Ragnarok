package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client. Opens the {@code OptionsOverviewScreen} carrying the server's current config values.
 * The addon's {@code Config} spec is a COMMON config (not synced), so the server pushes its live values
 * here rather than letting the client read its own (possibly different) config file.
 */
public class OpenOptionsPacket {

    private final boolean requireOpToEdit;
    private final int maxNpcHealth;
    private final boolean enableDmzIntegration;
    private final boolean enableAuraStacking;
    private final int shadowDummyCooldownSeconds;
    private final int shadowDummyMaxAlivePerParty;
    private final int partyTpFalloffThreshold;
    private final int partyTpFalloffStepPercent;
    private final int partyTpFalloffFloorPercent;

    public OpenOptionsPacket(boolean requireOpToEdit, int maxNpcHealth,
                             boolean enableDmzIntegration, boolean enableAuraStacking,
                             int shadowDummyCooldownSeconds, int shadowDummyMaxAlivePerParty,
                             int partyTpFalloffThreshold, int partyTpFalloffStepPercent,
                             int partyTpFalloffFloorPercent) {
        this.requireOpToEdit = requireOpToEdit;
        this.maxNpcHealth = maxNpcHealth;
        this.enableDmzIntegration = enableDmzIntegration;
        this.enableAuraStacking = enableAuraStacking;
        this.shadowDummyCooldownSeconds = shadowDummyCooldownSeconds;
        this.shadowDummyMaxAlivePerParty = shadowDummyMaxAlivePerParty;
        this.partyTpFalloffThreshold = partyTpFalloffThreshold;
        this.partyTpFalloffStepPercent = partyTpFalloffStepPercent;
        this.partyTpFalloffFloorPercent = partyTpFalloffFloorPercent;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(requireOpToEdit);
        buf.writeVarInt(maxNpcHealth);
        buf.writeBoolean(enableDmzIntegration);
        buf.writeBoolean(enableAuraStacking);
        buf.writeVarInt(shadowDummyCooldownSeconds);
        buf.writeVarInt(shadowDummyMaxAlivePerParty);
        buf.writeVarInt(partyTpFalloffThreshold);
        buf.writeVarInt(partyTpFalloffStepPercent);
        buf.writeVarInt(partyTpFalloffFloorPercent);
    }

    public static OpenOptionsPacket decode(FriendlyByteBuf buf) {
        return new OpenOptionsPacket(buf.readBoolean(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.dev.sdu.client.gui.OptionsOverviewScreen.open(
                        requireOpToEdit, maxNpcHealth, enableDmzIntegration, enableAuraStacking,
                        shadowDummyCooldownSeconds, shadowDummyMaxAlivePerParty,
                        partyTpFalloffThreshold, partyTpFalloffStepPercent, partyTpFalloffFloorPercent)));
        context.setPacketHandled(true);
    }
}
