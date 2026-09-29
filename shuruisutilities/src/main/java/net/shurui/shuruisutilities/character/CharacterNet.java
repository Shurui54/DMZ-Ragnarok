package net.shurui.shuruisutilities.character;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

/** Server-side helper: (re)send the character-slot picker to a player. */
public final class CharacterNet
{
    private CharacterNet() {}

    public static void openGui(ServerPlayer p)
    {
        CharacterSlots.ensureInit(p);
        boolean tourFeature = TournamentCharBridge.featureEnabled();
        NetworkUtils.INSTANCE.send(PacketDistributor.PLAYER.with(() -> p),
                new PacketCharacterGui(CharacterSlots.names(p), CharacterSlots.activeIndex(p),
                        CommandCharacter.maxSlots(p),
                        tourFeature,
                        tourFeature && TournamentCharBridge.present(p),
                        tourFeature && TournamentCharBridge.active(p)));
    }
}
