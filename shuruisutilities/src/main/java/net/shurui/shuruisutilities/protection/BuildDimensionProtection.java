package net.shurui.shuruisutilities.protection;

import java.util.Set;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.guilds.GuildProtectionHandler;
import net.shurui.shuruisutilities.regions.RegionBypass;
import net.shurui.shuruisutilities.regions.RegionEventHandler;
import net.shurui.shuruisutilities.util.events.ServerEventHandler;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Blanket build protection for hand-built FIXED dimensions that are not player property and cannot be self-claimed.
 *
 * <p>A generated planet gets its ownership from {@link net.shurui.shuruisutilities.space.GeneratedPlanetClaims}, but a
 * hand-authored fixed dimension (Planet Vegeta at {@code dmz_ragnarok:planet_vegeta}, and hand-built dungeon/theme
 * dimensions) has no generated id and so cannot be claimed at all: players reported breaking its blocks and punching its
 * decorative NPCs with nothing stopping them (bugs 730/709, and the Planet Vegeta half of 663). Regions cannot cover a
 * whole dimension here either, because {@link net.shurui.shuruisutilities.regions.RegionManager} indexes a region by every
 * chunk its bounds span, so a dimension-sized region would blow up the chunk index.
 *
 * <p>This handler instead reads an operator-controlled allow-list of protected dimension ids
 * ({@link SUConfig#protectedBuildDimensions}) and, for any player who is not staff, refuses to let them alter the build:
 * <ul>
 *   <li>breaking or placing blocks (dragon balls stay exempt, exactly as they are for regions and claims, so a scattered
 *       set can still be gathered and the seven placed to call Shenron),</li>
 *   <li>damaging non-hostile NPCs and decoration entities (armor stands, item frames, paintings),</li>
 *   <li>stripping or rearranging armor stands and item frames.</li>
 * </ul>
 *
 * <p>What is deliberately left alone so the dimension still plays: fighting hostile / saga quest mobs (anything that is an
 * {@link Enemy}, which every {@code DBSagasEntity} is, since it extends {@code Monster}), PvP between players, using
 * training machines and other blocks (interaction is not touched here), and every environmental system. Staff bypass
 * either by holding the {@code su.protection.builddim.bypass} permission (op by default) or by being in region build
 * bypass ({@code /rg bypass}), the same toggle they already use to edit protected land.
 *
 * <p>Registered on the Forge bus by {@link ServerEventHandler}'s constructor, and instantiated by core
 * ({@code ShuruisUtilities} common setup) on every server, keyless included: it guards a public dimension. Before S12
 * the Protection module built it, and it outlived the keyless teardown. Its bypass node is registered by the
 * Protection module (Ragnarok Key) or, without the key, by core.
 */
public class BuildDimensionProtection extends ServerEventHandler
{
    /** Op-by-default node. A player holding it edits protected build dimensions freely, like an admin editing a claim. */
    public static final String PERM_BYPASS = "su.protection.builddim.bypass";

    private static boolean isProtectedDimension(Level level)
    {
        Set<String> dims = SUConfig.protectedBuildDimensions;
        if (dims == null || dims.isEmpty())
            return false;
        return dims.contains(level.dimension().location().toString());
    }

    private static boolean bypasses(Player player)
    {
        if (player == null)
            return false;
        if (RegionBypass.isBypassing(player.getUUID()))
            return true;
        return APIRegistry.perms.checkPermission(player, PERM_BYPASS);
    }

    /** True when this player must not alter the build at this level: protected dimension and no staff bypass. */
    private static boolean guarded(Player player, Level level)
    {
        return player != null && !level.isClientSide && isProtectedDimension(level) && !bypasses(player);
    }

    /**
     * An entity a non-staff player may not harm inside a protected build: a non-hostile NPC (shopkeepers, villagers, the
     * custom cast placed as scenery) or a decoration entity (armor stand, item frame, painting). Hostile and saga combat
     * mobs are {@link Enemy} and are excluded so the dimension can still be fought through, and a training dummy such as
     * DMZ's punch machine is neither an NPC nor a decoration, so it stays hittable.
     */
    private static boolean isProtectedTarget(Entity target)
    {
        if (target instanceof Player || target instanceof Enemy)
            return false;
        if (target instanceof ArmorStand || target instanceof HangingEntity)
            return true;
        return target instanceof LivingEntity living && GuildProtectionHandler.isProtectedNpc(living);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBreak(BlockEvent.BreakEvent event)
    {
        Player player = event.getPlayer();
        if (player == null || !guarded(player, player.level()))
            return;
        // Balls scatter into any dimension and must stay pickable, matching RegionEventHandler / claim rules.
        if (RegionEventHandler.isDragonBall(event.getState()))
            return;
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlace(BlockEvent.EntityPlaceEvent event)
    {
        if (!(event.getEntity() instanceof Player player) || !guarded(player, player.level()))
            return;
        if (RegionEventHandler.isDragonBall(event.getPlacedBlock()))
            return;
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMultiPlace(BlockEvent.EntityMultiPlaceEvent event)
    {
        if (!(event.getEntity() instanceof Player player) || !guarded(player, player.level()))
            return;
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAttackEntity(AttackEntityEvent event)
    {
        Player player = event.getEntity();
        if (!guarded(player, player.level()))
            return;
        if (isProtectedTarget(event.getTarget()))
            event.setCanceled(true);
    }

    // Ki blasts hurt through DMZ's own damage event, not a vanilla melee, so guard that path too (as RegionEventHandler
    // does for its flags). Only NPC-type victims are covered here; decoration entities are handled by the melee/interact
    // paths above.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDmzDamage(com.dragonminez.common.events.DMZEvent.DamageModifyEvent event)
    {
        LivingEntity victim = event.getVictim();
        Player attacker = event.getAttacker();
        if (victim == null || attacker == null || !guarded(attacker, victim.level()))
            return;
        if (isProtectedTarget(victim))
            event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event)
    {
        guardEntityInteract(event, event.getTarget());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event)
    {
        guardEntityInteract(event, event.getTarget());
    }

    // Stripping / re-dressing an armor stand and rotating or looting an item frame are interactions, not attacks, so they
    // are refused separately here. Living NPCs are left alone so shop trading and quest talk still work in the dimension.
    private void guardEntityInteract(PlayerInteractEvent event, Entity target)
    {
        Player player = event.getEntity();
        if (!guarded(player, player.level()))
            return;
        if (target instanceof ArmorStand || target instanceof HangingEntity)
            event.setCanceled(true);
    }
}
