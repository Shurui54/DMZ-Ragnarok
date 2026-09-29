package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.entity.ShenronDisplayEntity;
import net.shurui.dev.sdu.shenron.ShrineColor;
import net.shurui.dev.sdu.shenron.ShrineConfig;
import net.shurui.dev.sdu.shenron.ShrineSummon;
import net.shurui.dev.sdu.shenron.ShrineWish;

import java.util.function.Supplier;

/**
 * Client -> server. The summoner chose a wish in the {@code WishSelectScreen}. The server validates: sender ==
 * recorded summoner, entity alive, wish not yet granted, and the wish is allowed for that colour; then runs the
 * wish's commands as console and starts the entity's short despawn. Exactly one wish per summon.
 */
public class ShrineWishC2S {

    private final int entityId;
    private final ShrineColor color;
    private final String wishId;

    public ShrineWishC2S(int entityId, ShrineColor color, String wishId) {
        this.entityId = entityId;
        this.color = color;
        this.wishId = wishId;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeEnum(color);
        buf.writeUtf(wishId == null ? "" : wishId);
    }

    public static ShrineWishC2S decode(FriendlyByteBuf buf) {
        return new ShrineWishC2S(buf.readVarInt(), buf.readEnum(ShrineColor.class), buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ShenronDisplayEntity shenron = ShrineSummon.findShenron(player, entityId);
            if (shenron == null || !shenron.isAlive()) {
                return;
            }
            if (!player.getUUID().equals(shenron.getSummoner())) {
                return; // only the summoner may wish.
            }
            if (shenron.hasGrantedWish()) {
                player.displayClientMessage(Component.translatable("gui.dmz_ragnarok.npc.shrine.already_wished"), true);
                return;
            }
            if (!ShrineConfig.colorAllowsWish(color, wishId)) {
                return;
            }
            ShrineWish wish = ShrineConfig.wishById(wishId);
            if (wish == null) {
                return;
            }
            ShrineSummon.grantWish(player, shenron, wish);
        });
        context.setPacketHandled(true);
    }
}
