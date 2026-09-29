package net.shurui.dev.sdu.mixin.cnpc;

import net.minecraft.nbt.CompoundTag;
import net.shurui.dev.sdu.compat.cnpc.SduHairHolder;
import net.shurui.dev.sdu.compat.cnpc.SduNpcData;
import noppes.npcs.entity.data.DataDisplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Adds a DMZ hair code (+ stat fields) to Custom NPCs' DataDisplay, persisted/synced through the NPC's own
// display NBT, same technique the CNPC-Gecko-Addon uses for its CustomModelData. save/readToNBT are part of
// Custom NPCs' save/sync, so the code survives clone save/load and reaches clients (where the render hook
// draws the hair). remap=false (Custom NPCs methods, not vanilla). Runs both sides.
@Mixin(value = DataDisplay.class, remap = false)
public class DataDisplayHairMixin implements SduHairHolder, SduNpcData {

    @Unique
    private String sdu$hairCode = "";

    @Unique
    private String sdu$hairColor = "";

    @Unique
    private int sdu$battlePower;
    @Unique
    private double sdu$health;
    @Unique
    private double sdu$kiBlastDamage;
    @Unique
    private double sdu$moveSpeed;
    @Unique
    private double sdu$meleeDamage;
    @Unique
    private double sdu$defense;
    @Unique
    private int sdu$aiTier = -1;
    @Unique
    private String sdu$kiMoves = "";
    @Unique
    private boolean sdu$noRanged;

    @Inject(method = "save", at = @At("HEAD"))
    private void sdu$saveHair(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        if (sdu$hairCode != null && !sdu$hairCode.isEmpty()) {
            tag.putString("sdu_dmz_hair", sdu$hairCode);
        }
        if (sdu$hairColor != null && !sdu$hairColor.isEmpty()) {
            tag.putString("sdu_dmz_hair_color", sdu$hairColor);
        }
        tag.putInt("sdu_dmz_bp", sdu$battlePower);
        tag.putDouble("sdu_dmz_health", sdu$health);
        tag.putDouble("sdu_dmz_ki_dmg", sdu$kiBlastDamage);
        tag.putDouble("sdu_dmz_move_speed", sdu$moveSpeed);
        tag.putDouble("sdu_dmz_melee", sdu$meleeDamage);
        tag.putDouble("sdu_dmz_defense", sdu$defense);
        tag.putInt("sdu_dmz_ai_tier", sdu$aiTier);
        tag.putString("sdu_dmz_ki_moves", sdu$kiMoves == null ? "" : sdu$kiMoves);
        tag.putBoolean("sdu_dmz_no_ranged", sdu$noRanged);
    }

    @Inject(method = "readToNBT", at = @At("HEAD"))
    private void sdu$readHair(CompoundTag tag, CallbackInfo ci) {
        sdu$hairCode = tag.contains("sdu_dmz_hair") ? tag.getString("sdu_dmz_hair") : "";
        sdu$hairColor = tag.contains("sdu_dmz_hair_color") ? tag.getString("sdu_dmz_hair_color") : "";
        sdu$battlePower = tag.getInt("sdu_dmz_bp");
        sdu$health = tag.getDouble("sdu_dmz_health");
        sdu$kiBlastDamage = tag.getDouble("sdu_dmz_ki_dmg");
        sdu$moveSpeed = tag.getDouble("sdu_dmz_move_speed");
        sdu$meleeDamage = tag.getDouble("sdu_dmz_melee");
        sdu$defense = tag.getDouble("sdu_dmz_defense");
        sdu$aiTier = tag.contains("sdu_dmz_ai_tier") ? tag.getInt("sdu_dmz_ai_tier") : -1;
        sdu$kiMoves = tag.contains("sdu_dmz_ki_moves") ? tag.getString("sdu_dmz_ki_moves") : "";
        sdu$noRanged = tag.getBoolean("sdu_dmz_no_ranged");
    }

    @Override @Unique public int sdu$getBattlePower() { return sdu$battlePower; }
    @Override @Unique public void sdu$setBattlePower(int v) { sdu$battlePower = v; }
    @Override @Unique public double sdu$getHealth() { return sdu$health; }
    @Override @Unique public void sdu$setHealth(double v) { sdu$health = v; }
    @Override @Unique public double sdu$getKiBlastDamage() { return sdu$kiBlastDamage; }
    @Override @Unique public void sdu$setKiBlastDamage(double v) { sdu$kiBlastDamage = v; }
    @Override @Unique public double sdu$getMoveSpeed() { return sdu$moveSpeed; }
    @Override @Unique public void sdu$setMoveSpeed(double v) { sdu$moveSpeed = v; }
    @Override @Unique public double sdu$getMeleeDamage() { return sdu$meleeDamage; }
    @Override @Unique public void sdu$setMeleeDamage(double v) { sdu$meleeDamage = v; }
    @Override @Unique public double sdu$getDefense() { return sdu$defense; }
    @Override @Unique public void sdu$setDefense(double v) { sdu$defense = v; }
    @Override @Unique public int sdu$getAiTier() { return sdu$aiTier; }
    @Override @Unique public void sdu$setAiTier(int v) { sdu$aiTier = v; }
    @Override @Unique public String sdu$getKiMoves() { return sdu$kiMoves == null ? "" : sdu$kiMoves; }
    @Override @Unique public void sdu$setKiMoves(String csv) { sdu$kiMoves = csv == null ? "" : csv; }
    @Override @Unique public boolean sdu$isNoRanged() { return sdu$noRanged; }
    @Override @Unique public void sdu$setNoRanged(boolean v) { sdu$noRanged = v; }

    @Override
    @Unique
    public String sdu$getHairCode() {
        return sdu$hairCode;
    }

    @Override
    @Unique
    public void sdu$setHairCode(String code) {
        sdu$hairCode = code == null ? "" : code;
    }

    @Override
    @Unique
    public String sdu$getHairColor() {
        return sdu$hairColor;
    }

    @Override
    @Unique
    public void sdu$setHairColor(String hex) {
        sdu$hairColor = hex == null ? "" : hex;
    }
}
