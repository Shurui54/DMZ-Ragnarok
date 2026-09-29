package net.shurui.shuruisutilities.core.mixin.stats;

import java.util.Locale;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.common.stats.StatsData;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import net.shurui.shuruisutilities.ritual.WishRitualStore;

/**
 * Server-side purchase gate for Super Saiyan God. The SSG form lives in the {@code godforms} skill, which base DMZ
 * leaves unpriced (empty prices, so unbuyable); the ritual merge gives it a real price so a saiyan can buy it, but ONLY
 * once they have earned the entitlement by taking part in the SSG charge ritual ({@link WishRitualStore#SSG_PURCHASE}).
 *
 * <p>Two choke points mirror the DMZ form-buy flow, both used by the sdu quest gate: {@code computeTpCost} (the tree
 * first-buy and every upgrade; a {@code -1} return fails DMZ's {@code cost >= 0} guard cleanly with no TP spent) and
 * {@code isSkillAllowedForPlayerRace} (the master-NPC buy). Gating both blocks every buy path for an unentitled player.
 * The ritual grant itself raises the skill directly (not through this packet), so it is never blocked. {@code require = 0}
 * so a DMZ rename degrades to no gating; every access is wrapped. Fail-closed for the SSG skill specifically: if the
 * buyer cannot be resolved we still refuse, since nothing else legitimately buys {@code godforms}.
 */
@Mixin(targets = "com.dragonminez.common.network.C2S.UpdateSkillC2S", remap = false)
public abstract class MixinDmzSsgPurchaseGate
{
    private static final String SSG_SKILL = "godforms";

    @Inject(method = "computeTpCost(Lcom/dragonminez/common/stats/StatsData;Ljava/lang/String;I)I",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void su$gateSsgCost(StatsData data, String skillName, int currentLevel,
                                       CallbackInfoReturnable<Integer> cir)
    {
        if (isSsg(skillName) && !entitled(data))
            cir.setReturnValue(-1);
    }

    @Inject(method = "isSkillAllowedForPlayerRace(Lcom/dragonminez/common/stats/StatsData;Ljava/lang/String;)Z",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void su$gateSsgAllowed(StatsData data, String skillName, CallbackInfoReturnable<Boolean> cir)
    {
        if (isSsg(skillName) && !entitled(data))
        {
            notifyLocked(data);
            cir.setReturnValue(false);
        }
    }

    private static boolean isSsg(String skillName)
    {
        return skillName != null && SSG_SKILL.equals(skillName.toLowerCase(Locale.ROOT));
    }

    // Fail-closed: only a buyer we can resolve AND who owns the entitlement passes.
    private static boolean entitled(StatsData data)
    {
        try
        {
            if (data == null)
                return false;
            Player p = data.getPlayer();
            return p instanceof ServerPlayer sp && WishRitualStore.hasSsgPurchase(sp);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static void notifyLocked(StatsData data)
    {
        try
        {
            if (data != null && data.getPlayer() instanceof ServerPlayer sp)
                sp.sendSystemMessage(Component.translatable("ritual.dmz_ragnarok.ssg.locked"));
        }
        catch (Throwable ignored)
        {
        }
    }
}
