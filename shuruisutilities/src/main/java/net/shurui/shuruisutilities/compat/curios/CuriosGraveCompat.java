package net.shurui.shuruisutilities.compat.curios;

import net.minecraft.server.level.ServerPlayer;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.grave.KeepPartialInventory;

import top.theillusivec4.curios.api.event.DropRulesEvent;
import top.theillusivec4.curios.api.type.capability.ICurio;

// Curios integration for KeepPartialInventory. both keepinv tiers keep equipped Curios, so while the rule is on
// we force every equipped curio to ALWAYS_KEEP. Curios' death handler then leaves them on the player and its
// keep path restores them onto the respawn, so no snapshot/restore on our side.
// imports Curios types directly, so ONLY registered from CuriosCompat behind the isModLoaded guard.
public final class CuriosGraveCompat
{
    private CuriosGraveCompat() {}

    // register the forge-bus handler. only called when Curios is present.
    public static void register()
    {
        MinecraftForge.EVENT_BUS.register(CuriosGraveCompat.class);
    }

    @SubscribeEvent
    public static void onCuriosDropRules(DropRulesEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player && KeepPartialInventory.isEnabled(player))
            event.addOverride(stack -> true, ICurio.DropRule.ALWAYS_KEEP);
    }
}
