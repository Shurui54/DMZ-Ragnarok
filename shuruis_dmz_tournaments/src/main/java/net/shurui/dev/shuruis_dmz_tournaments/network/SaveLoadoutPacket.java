package net.shurui.dev.shuruis_dmz_tournaments.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import net.shurui.dev.shuruis_dmz_tournaments.character.TournamentAttacks;
import net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter;
import net.shurui.dev.shuruis_dmz_tournaments.character.TournamentMoveAccess;

/**
 * C2S. Player saved their attack loadout. Server is authoritative: requires EXACTLY
 * {@link TournamentAttacks#KI_SLOTS} ki and {@link TournamentAttacks#STRIKE_SLOTS} strike, all known, no
 * duplicates, and REJECTS rather than clamps, so a hand-crafted packet can't produce a fighter nobody can
 * explain.
 */
public class SaveLoadoutPacket {
    private final List<String> selected;

    public SaveLoadoutPacket(List<String> selected) {
        this.selected = selected == null ? List.of() : selected;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(selected.size());
        for (String s : selected) buf.writeUtf(s);
    }

    public static SaveLoadoutPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> sel = new ArrayList<>(n);
        for (int i = 0; i < n; i++) sel.add(buf.readUtf());
        return new SaveLoadoutPacket(sel);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp == null) return;
            // exactly KI_SLOTS ki + STRIKE_SLOTS strike, all known, no dupes; reject on any deviation. Every id must
            // also pass the SAME per-player gate the pick list was built from (TournamentMoveAccess), so a crafted
            // packet cannot smuggle in a race-locked or title / unlock gated move the screen never offered.
            List<String> ki = new ArrayList<>();
            List<String> strike = new ArrayList<>();
            for (String id : selected) {
                if (id == null || ki.contains(id) || strike.contains(id)) continue;
                if (!TournamentMoveAccess.mayUse(sp, id)) continue;
                if (TournamentAttacks.isKiAttack(id)) ki.add(id);
                else if (TournamentAttacks.isStrikeAttack(id)) strike.add(id);
            }
            if (ki.size() != TournamentAttacks.KI_SLOTS || strike.size() != TournamentAttacks.STRIKE_SLOTS) {
                sp.sendSystemMessage(Component.translatable("message.dmz_ragnarok.tournaments.loadout.invalid",
                        TournamentAttacks.KI_SLOTS, TournamentAttacks.STRIKE_SLOTS).withStyle(ChatFormatting.RED));
                return;
            }
            List<String> valid = new ArrayList<>(ki);
            valid.addAll(strike);
            // creation sandbox: bake moves, freeze template, restore real character. Otherwise just rebuild the template.
            if (TournamentCharacter.isCreating(sp)) {
                TournamentCharacter.finalizeCreation(sp, valid);
            } else {
                TournamentCharacter.setLoadoutAndBuild(sp, valid);
                sp.sendSystemMessage(Component.translatable(
                        "message.dmz_ragnarok.tournaments.loadout.saved", valid.size()).withStyle(ChatFormatting.GREEN));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
