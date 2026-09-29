package net.shurui.dev.sdu.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.compat.DmzForms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

import java.util.ArrayList;
import java.util.List;

// Relabels our own BonusStats entries in DMZ's per-stat hover tooltip so the form-combat rage buff reads
// "UE Buff" and the racial-skill buff "Racial Buff", not the raw source ids ("sdu form rage" / "sdu racial").
// The buffs already live in DMZ's synced BonusStats, so we only rename the line.
//
// Targets DMZ's private renderStatsInfo. The @Slice pins the ModifyArg to the single renderAdvancedTooltip
// call for a stat row (between the "bonus" header constant and the later "ap" constant), so the AP/TPC
// tooltip lower down is untouched. remap=false / require=0: a DMZ reshape degrades to DMZ's raw line.
@Mixin(targets = "com.dragonminez.client.gui.character.CharacterStatsScreen", remap = false)
public abstract class CharacterStatsScreenMixin {

    // DMZ prints a bonus name as source.replace("_", " "), so match on those display forms.
    private static final String SDU_FORM_DISPLAY = DmzForms.BONUS_SOURCE.replace("_", " ");
    private static final String SDU_RACIAL_DISPLAY = DmzForms.RACIAL_SOURCE.replace("_", " ");

    @ModifyArg(
            method = "renderStatsInfo",
            at = @At(value = "INVOKE",
                    target = "Lcom/dragonminez/client/util/TextUtil;renderAdvancedTooltip(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIILnet/minecraft/network/chat/Component;Ljava/util/List;Ljava/util/List;I)V"),
            slice = @Slice(
                    from = @At(value = "CONSTANT", args = "stringValue=gui.dragonminez.character_stats.bonus"),
                    to = @At(value = "CONSTANT", args = "stringValue=gui.dragonminez.character_stats.ap")),
            index = 8,
            require = 0)
    private List<Component> sdu$relabelBonusLines(List<Component> extras) {
        if (extras == null || extras.isEmpty()) {
            return extras;
        }
        try {
            List<Component> out = new ArrayList<>(extras.size());
            for (Component line : extras) {
                out.add(sdu$relabel(line));
            }
            return out;
        } catch (Throwable ignored) {
            // never break DMZ's tooltip for a cosmetic relabel
            return extras;
        }
    }

    // relabel a line if it's one of our raw source names, keeping DMZ's ": xVALUE" suffix and green
    private static Component sdu$relabel(Component line) {
        String plain = line.getString();
        String key;
        if (plain.contains(SDU_FORM_DISPLAY)) {
            key = "gui.dmz_ragnarok.core.ue_buff";
        } else if (plain.contains(SDU_RACIAL_DISPLAY)) {
            key = "gui.dmz_ragnarok.core.racial_buff";
        } else {
            return line;
        }
        int colon = plain.indexOf(':');
        String suffix = colon >= 0 ? plain.substring(colon) : "";
        return Component.literal("  ")
                .append(Component.translatable(key))
                .append(Component.literal(suffix))
                .withStyle(ChatFormatting.GREEN);
    }
}
