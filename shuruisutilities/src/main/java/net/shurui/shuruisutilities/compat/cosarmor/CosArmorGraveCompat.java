package net.shurui.shuruisutilities.compat.cosarmor;

import net.minecraft.server.level.ServerPlayer;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.grave.KeepPartialInventory;

// CAR integration for KeepPartialInventory. CAR drops its cosmetic slots via its own default-priority
// LivingDropsEvent gated ONLY on vanilla keepInventory (which SU never sets) + CAR's config; it doesn't know
// about our per-world rule, so its cosmetics drop while our rule is on. we cancel LivingDropsEvent at HIGHEST
// (before CAR) when the rule is on. vanilla player drops don't flow through this event (they use dropAll) and
// Curios keep via their own path, so this is effectively CAR-only. CAR keys storage by UUID, so cosmetics just
// stay on the respawn. no snapshot/restore, no dupe risk.
// classload-safe (no CAR types); still only registered from CosArmorCompat behind the isModLoaded guard.
public final class CosArmorGraveCompat
{
    private CosArmorGraveCompat() {}

    // register the forge-bus handler. only called when CAR is present.
    public static void register()
    {
        MinecraftForge.EVENT_BUS.register(CosArmorGraveCompat.class);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDrops(LivingDropsEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player && KeepPartialInventory.isEnabled(player))
            event.setCanceled(true);
    }
}
