package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.compat.cnpc.DmzCnpcCompat;
import net.shurui.dev.sdu.entity.SduDmzFighter;
import net.shurui.dev.sdu.registry.ModEntities;

import java.util.function.Supplier;

/**
 * C2S from the DMZ tab's "Make saga fighter" button: replace Custom NPC {@code entityId} with a real DMZ saga
 * fighter ({@code SduDmzFighter}, true saga AI + animations), configured from the values the client read off the
 * NPC's DMZ tab (so it works without saving the NPC first). Fighter spawns at the source's position, source is
 * removed. Handler is Custom-NPCs-free (only touches our own entity), gated on op + the CNPC stack present.
 */
public class SpawnFighterPacket {

    private final int entityId;
    private final String model;      // custom geo model name (e.g. "sdu_wide")
    private final int skinType;      // 0 texture, 1 player, 2 url
    private final String skinValue;
    private final String hair;
    private final String hairColor;
    private final int battlePower;
    private final double health;
    private final double kiDamage;
    private final double moveSpeed;
    private final double melee;
    private final int aiTier;
    private final String kiMoves;
    private final boolean noRanged;
    private final double defense;

    public SpawnFighterPacket(int entityId, String model, int skinType, String skinValue, String hair,
                              String hairColor, int battlePower, double health, double kiDamage, double moveSpeed,
                              double melee, int aiTier, String kiMoves, boolean noRanged, double defense) {
        this.entityId = entityId;
        this.model = model;
        this.skinType = skinType;
        this.skinValue = skinValue;
        this.hair = hair;
        this.hairColor = hairColor;
        this.battlePower = battlePower;
        this.health = health;
        this.kiDamage = kiDamage;
        this.moveSpeed = moveSpeed;
        this.melee = melee;
        this.aiTier = aiTier;
        this.kiMoves = kiMoves;
        this.noRanged = noRanged;
        this.defense = defense;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeUtf(model);
        buf.writeVarInt(skinType);
        buf.writeUtf(skinValue);
        buf.writeUtf(hair);
        buf.writeUtf(hairColor);
        buf.writeVarInt(battlePower);
        buf.writeDouble(health);
        buf.writeDouble(kiDamage);
        buf.writeDouble(moveSpeed);
        buf.writeDouble(melee);
        buf.writeVarInt(aiTier);
        buf.writeUtf(kiMoves);
        buf.writeBoolean(noRanged);
        buf.writeDouble(defense);
    }

    public static SpawnFighterPacket decode(FriendlyByteBuf buf) {
        return new SpawnFighterPacket(buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readUtf(),
                buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readVarInt(), buf.readUtf(), buf.readBoolean(),
                buf.readDouble());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !player.hasPermissions(2) || !DmzCnpcCompat.cnpcAvailable()) {
                return;
            }
            ServerLevel level = player.serverLevel();
            SduDmzFighter fighter = ModEntities.DMZ_FIGHTER.get().create(level);
            if (fighter == null) {
                return;
            }
            fighter.setModelName(model);
            fighter.setSkin(skinType, skinValue);
            fighter.setHairCode(hair);
            fighter.setHairColor(hairColor);
            fighter.applyDmzStats(battlePower, health, kiDamage, moveSpeed, melee, aiTier, kiMoves, noRanged, defense);

            Entity src = level.getEntity(entityId);
            if (src != null) {
                if (src.getCustomName() != null) {
                    fighter.setCustomName(src.getCustomName());
                } else {
                    fighter.setCustomName(src.getName());
                }
                fighter.setCustomNameVisible(true);
                fighter.moveTo(src.getX(), src.getY(), src.getZ(), src.getYRot(), src.getXRot());
                src.discard();
            } else {
                fighter.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0f);
            }
            level.addFreshEntity(fighter);
            player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.spawn_fighter.ok"), true);
        });
        context.setPacketHandled(true);
    }
}
