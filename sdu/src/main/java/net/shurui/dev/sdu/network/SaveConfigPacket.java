package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.Config;
import net.shurui.dev.sdu.util.SduPerms;

import java.util.function.Supplier;

/**
 * Client -> server. Applies the {@link Config} server options edited on the Options screen and saves
 * the config to disk. Op-gated with the same {@link SduPerms#canEdit} check the editor save packets use.
 */
public class SaveConfigPacket {

    private final boolean requireOpToEdit;
    private final int maxNpcHealth;
    private final boolean enableDmzIntegration;
    private final boolean enableAuraStacking;
    private final int shadowDummyCooldownSeconds;
    private final int shadowDummyMaxAlivePerParty;
    private final int partyTpFalloffThreshold;
    private final int partyTpFalloffStepPercent;
    private final int partyTpFalloffFloorPercent;

    public SaveConfigPacket(boolean requireOpToEdit, int maxNpcHealth,
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

    public static SaveConfigPacket decode(FriendlyByteBuf buf) {
        return new SaveConfigPacket(buf.readBoolean(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !SduPerms.canEdit(player)) {
                return;
            }
            Config.applyAndSave(requireOpToEdit, maxNpcHealth, enableDmzIntegration, enableAuraStacking,
                    shadowDummyCooldownSeconds, shadowDummyMaxAlivePerParty,
                    partyTpFalloffThreshold, partyTpFalloffStepPercent, partyTpFalloffFloorPercent);
            player.displayClientMessage(Component.translatable("gui.dmz_ragnarok.npc.options.saved"), false);
        });
        context.setPacketHandled(true);
    }
}
