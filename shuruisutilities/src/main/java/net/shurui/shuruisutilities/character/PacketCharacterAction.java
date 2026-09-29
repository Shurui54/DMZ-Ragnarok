package net.shurui.shuruisutilities.character;

import java.util.function.Supplier;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/** Client -&gt; server: a button (or keybind) in the character picker. Server applies it and re-sends the GUI. */
public class PacketCharacterAction implements ISUPacket
{
    public String action = "";
    public int slot;     // 0-based
    public String name = "";

    public PacketCharacterAction() {}

    public PacketCharacterAction(String action, int slot, String name)
    {
        this.action = action == null ? "" : action;
        this.slot = slot;
        this.name = name == null ? "" : name;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(action);
        buf.writeVarInt(slot);
        buf.writeUtf(name);
    }

    public static PacketCharacterAction decode(FriendlyByteBuf buf)
    {
        return new PacketCharacterAction(buf.readUtf(), buf.readVarInt(), buf.readUtf());
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer p = context.getSender();
        if (p == null || !net.shurui.shuruisutilities.core.config.Features.enabled(
                net.shurui.shuruisutilities.core.config.Features.CHARACTER_SLOTS)
                || APIRegistry.perms == null
                || !APIRegistry.perms.checkPermission(p, CommandCharacter.PERM_USE))
            return;

        String error = null;
        boolean reopen = true;
        switch (action)
        {
            case "open" -> { /* just (re)open below */ }
            case "switch" -> error = CharacterSlots.switchTo(p, slot);
            case "new" -> error = CharacterSlots.create(p, name.isBlank() ? null : name, CommandCharacter.maxSlots(p));
            case "rename" -> error = CharacterSlots.rename(p, slot, name);
            case "delete" -> error = CharacterSlots.delete(p, slot);
            // tournament character (owned by the Tournaments addon; reached softly through the bridge). Create opens
            // the tournament creation GUI on the client, so we must NOT re-send the slots GUI over the top of it.
            case "tour_create" -> { TournamentCharBridge.openCreate(p); reopen = false; }
            case "tour_delete" -> TournamentCharBridge.delete(p);
            default -> { }
        }
        if (error != null)
            ChatOutputHandler.chatError(p, error);
        if (reopen)
            CharacterNet.openGui(p);
    }

    public static void handler(final PacketCharacterAction message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
