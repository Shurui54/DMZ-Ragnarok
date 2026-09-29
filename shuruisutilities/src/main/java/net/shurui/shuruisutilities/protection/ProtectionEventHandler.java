package net.shurui.shuruisutilities.protection;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.commons.selections.WorldPoint;
import net.shurui.shuruisutilities.util.PlayerInfo;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The player-versus-player answers every PvP layer shares, kept in core under the name callers already use.
 *
 * <p>The permission-zone protection itself (the Forge handlers that enforce the {@code su.protection.*} nodes) moved
 * into the Ragnarok Key with the Protection module (S12, {@code net.shurui.ragnarokkey.protection
 * .ProtectionEnforcement}). What stays here are the statics other features call without owning the protection: the
 * key's hakai and the guild claim flag ask {@link #pvpAllowedBetween} and {@link #grantPvpEnableDeathGrace}, and the
 * region, guild and protection damage handlers resolve a spar partner through {@link #resolvePvpAttacker}. They are
 * the same code as before, reading only core state (the permission helper, the stored PvP toggle and the public
 * tournament and dungeon bridges), so their answers are unchanged with or without the key.
 */
public final class ProtectionEventHandler
{
    private ProtectionEventHandler() {}


    // effective PvP for the toggle: stored isPvpEnabled() OR bounty-forced (a pool >= FORCE_PVP_THRESHOLD forces
    // it on) OR an active tournament fighter (a live match forces both fighters on so a toggle-off player is still
    // hittable in the arena) OR carrying a dragon ball inside a dungeon (the ball is what makes them fair game
    // there; standing in a dungeon empty-handed does not). Bounty module disabled -> null manager -> pvpForced
    // treated as false. Tournaments / dungeons absent, or anything thrown -> the bridges fail closed to false, so
    // PvP falls back to the player's own toggle. All four are stateless live queries OR-ed into the decision,
    // nothing is persisted, so nobody is ever left permanently force-PvP'd once the pool clears, the match ends
    // or the ball leaves their inventory.
    /**
     * The same player-versus-player decision the damage events make, for abilities that never raise a damage event.
     *
     * <p>Hakai is the reason this is public: it ERASES its target rather than hurting it, so no {@code LivingHurt}
     * or attack event ever fires and none of the checks above would run. Anything else that kills or removes a
     * player outside the damage pipeline has to ask here too, or the PvP toggle stops meaning anything.
     *
     * <p>The same three gates as the event path, in the same order: the permission (which covers regions and guild
     * land, because it is checked at the victim's position), then both sides' effective toggle.
     */
    public static boolean pvpAllowedBetween(Player attacker, Player victim)
    {
        try
        {
            if (attacker == null || victim == null)
                return false;
            UserIdent attackerIdent = UserIdent.get(attacker);
            if (!APIRegistry.perms.checkUserPermission(UserIdent.get(victim), ProtectionPerms.PERM_PVP)
                    || !APIRegistry.perms.checkUserPermission(attackerIdent, ProtectionPerms.PERM_PVP)
                    || !APIRegistry.perms.checkUserPermission(attackerIdent, new WorldPoint(victim),
                            ProtectionPerms.PERM_PVP))
                return false;
            return effectivePvp(attacker) && effectivePvp(victim);
        }
        catch (Throwable t)
        {
            // Fail CLOSED. An ability that cannot prove PvP is allowed must not go through with it.
            return false;
        }
    }

    /**
     * Grant DragonMineZ's force-kill grace to a player who has just ENABLED PvP (their own toggle, or a guild
     * claim's PvP flag flipping on over them), so the first incoming hit that momentarily leaves
     * {@code getHealth() <= 0} cannot be turned into a spurious, unattributed force-kill by DMZ's per-tick janitor
     * before health regen reconciles.
     *
     * <p>This is the same DMZ bug ShardSync's arrival grace exists for: {@code TickHandler
     * .shouldForceKillForInvalidHealth} kills an alive, non-dying player sitting at {@code <= 0} health, rendering
     * the vanilla "was killed" / "was killed whilst fighting" (genericKill) death, and DMZ only ever registers the
     * 40-tick grace on a respawn. Enabling PvP un-gates player damage that the key's protection handlers
     * ({@code ProtectionEnforcement}) were cancelling while PvP was off, so the very next hit is the one most likely to
     * catch that transient. A genuinely dead player (no regen left) still dies once the grace elapses, so no real
     * death is hidden. Logs the current health so a subsequent death is diagnosable: already {@code <= 0} at the
     * toggle is a genuine death exposed by un-gating, a positive value that still dies inside the window is the
     * transient this grace covers.
     *
     * <p>Guarded and silent on failure, like ShardSync's arrival grace: DMZ is a mandatory dependency so this
     * resolves, but a grace that failed to register is at worst one spurious death, never a thrown command.
     */
    public static void grantPvpEnableDeathGrace(ServerPlayer player, String reason)
    {
        if (player == null)
            return;
        try
        {
            com.dragonminez.server.events.players.TickHandler.registerForceKillGrace(player.getUUID());
            LoggingHandler.sulog.info(
                    "[pvp] Granted the force-kill grace to {} on enabling PvP ({}); health {} / {}.",
                    player.getGameProfile().getName(), reason, player.getHealth(), player.getMaxHealth());
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[pvp] Could not register the PvP-enable death grace for {}: {}",
                    player.getGameProfile().getName(), t.toString());
        }
    }

    public static boolean effectivePvp(Player p)
    {
        if (PlayerInfo.get(p).isPvpEnabled())
            return true;
        if (net.shurui.shuruisutilities.compat.tournaments.TournamentPvpBridge.isActiveFighter(p.getUUID()))
            return true;
        if (net.shurui.shuruisutilities.compat.dungeons.DungeonPvpBridge.carryingBallInDungeon(p))
            return true;
        return net.shurui.shuruisutilities.api.key.BountyHooks.get().pvpForced(p.getUUID());
    }

    /**
     * Resolve the ORIGINATING player attacker of a damage source for the PvP-deny layers: the causing entity when
     * that is a player (melee, and DMZ ki which sets the firer as the cause), otherwise the owner of a ki
     * projectile when the source only exposes the projectile. Null when no player is behind the hit. Shared so the
     * region and guild safe-zone handlers resolve a spar partner exactly the way the key's protection handler
     * ({@code ProtectionEnforcement}) does, ki and all, rather than each re-deriving it.
     */
    public static Player resolvePvpAttacker(net.minecraft.world.damagesource.DamageSource src)
    {
        if (src == null)
            return null;
        if (src.getEntity() instanceof Player p)
            return p;
        if (src.getDirectEntity() instanceof com.dragonminez.common.init.entities.ki.AbstractKiProjectile proj
                && proj.getOwner() instanceof Player owner)
            return owner;
        return null;
    }
}
