package net.shurui.shuruisutilities.compat;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;
import net.shurui.shuruisutilities.util.events.SUModuleEvent.SUModuleServerStartingEvent;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

// server-side control of Xaero's Minimap via its own "disable" effects (no_minimap, no_entity_radar,
// no_waypoints, no_cave_maps under the xaerominimap namespace). while a player has the effect the feature is
// forced off; we apply it to players lacking the matching perm, remove it from those who have it.
// effects resolved by ResourceLocation through ForgeRegistries, so no compile-time dep on Xaero's and it no-ops
// when absent. (re)applied on login + respawn; clearing the effect (milk) regains the feature until relog.
public class CompatXaeroMinimap
{
    public static final String PERM = "su.xaero";
    public static final String PERM_MINIMAP = PERM + ".minimap";
    public static final String PERM_RADAR = PERM + ".radar";
    public static final String PERM_WAYPOINTS = PERM + ".waypoints";
    public static final String PERM_CAVEMAPS = PERM + ".cavemaps";

    private static final ResourceLocation EFFECT_NO_MINIMAP = new ResourceLocation("xaerominimap", "no_minimap");
    private static final ResourceLocation EFFECT_NO_RADAR = new ResourceLocation("xaerominimap", "no_entity_radar");
    private static final ResourceLocation EFFECT_NO_WAYPOINTS = new ResourceLocation("xaerominimap", "no_waypoints");
    private static final ResourceLocation EFFECT_NO_CAVEMAPS = new ResourceLocation("xaerominimap", "no_cave_maps");

    @SubscribeEvent
    public void registerPerms(SUModuleServerStartingEvent e)
    {
        APIRegistry.perms.registerPermissionDescription(PERM, "Xaero's Minimap permissions");
        APIRegistry.perms.registerPermission(PERM_MINIMAP, DefaultPermissionLevel.ALL, "Allow the minimap");
        APIRegistry.perms.registerPermission(PERM_RADAR, DefaultPermissionLevel.ALL, "Allow the entity radar");
        APIRegistry.perms.registerPermission(PERM_WAYPOINTS, DefaultPermissionLevel.ALL, "Allow waypoints");
        APIRegistry.perms.registerPermission(PERM_CAVEMAPS, DefaultPermissionLevel.ALL, "Allow cave maps");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent e)
    {
        if (e.getEntity() instanceof ServerPlayer player)
            applyRestrictions(player);
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent e)
    {
        // effects wiped on death, reapply after respawn
        if (e.getEntity() instanceof ServerPlayer player)
            applyRestrictions(player);
    }

    // (re)apply/clear each disable effect to match perms. public so a perm-change hook can refresh on demand.
    public void applyRestrictions(ServerPlayer player)
    {
        setRestriction(player, EFFECT_NO_MINIMAP, !APIRegistry.perms.checkPermission(player, PERM_MINIMAP));
        setRestriction(player, EFFECT_NO_RADAR, !APIRegistry.perms.checkPermission(player, PERM_RADAR));
        setRestriction(player, EFFECT_NO_WAYPOINTS, !APIRegistry.perms.checkPermission(player, PERM_WAYPOINTS));
        setRestriction(player, EFFECT_NO_CAVEMAPS, !APIRegistry.perms.checkPermission(player, PERM_CAVEMAPS));
    }

    private void setRestriction(ServerPlayer player, ResourceLocation effectId, boolean disable)
    {
        MobEffect effect = ForgeRegistries.MOB_EFFECTS.getValue(effectId);
        if (effect == null)
            return; // Xaero's absent, or this effect doesn't exist in this version
        if (disable)
            // infinite + hidden (ambient, no particles/icon) so it's silently forced off
            player.addEffect(new MobEffectInstance(effect, MobEffectInstance.INFINITE_DURATION, 0, true, false, false));
        else
            player.removeEffect(effect);
    }
}
