package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.compat.DmzSkills;

import java.util.function.Supplier;

/**
 * Client -> server. Skills-menu double-click buy for a DMZ stack skill (kaioken, ultimate). DMZ's own PURCHASE
 * handler silently bails for these here (it demands the per-race FORM price, or treats the level-0 skill as
 * already-owned), so the buy routes through OUR grant path {@link DmzSkills#buyStackSkill}, the same code
 * {@code /rg npc buyskill} uses: charges {@code costs[currentLevel]}, checks {@code getSkillLevel} (not
 * hasSkill), does {@code setSkillLevel(+1)} and syncs. On failure the result message goes to the player.
 */
public class BuyStackSkillC2S {

    private final String skill;

    public BuyStackSkillC2S(String skill) {
        this.skill = skill;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(skill);
    }

    public static BuyStackSkillC2S decode(FriendlyByteBuf buf) {
        return new BuyStackSkillC2S(buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            DmzSkills.BuyResult result = DmzSkills.buyStackSkill(player, skill);
            if (!result.success()) {
                player.sendSystemMessage(result.message());
            }
        });
        context.setPacketHandled(true);
    }
}
