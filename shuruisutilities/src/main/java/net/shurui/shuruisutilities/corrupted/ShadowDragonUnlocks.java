package net.shurui.shuruisutilities.corrupted;

import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.compat.dmz.ShadowDragonFormCompat;
import net.shurui.shuruisutilities.subrace.SubRaces;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The unlock ladder for the shadow dragon feature (phase 4b): grants the tiers earned by fighting the seven shadow
 * dragon bosses, and delivers the reward feedback. This class owns the WHEN of granting; {@link RaceUnlocks} owns the
 * durable store, and {@link ShadowDragonFormCompat} owns the DMZ side of the transformation.
 *
 * <p>The tiers:
 * <ol>
 *   <li><b>Shadow Dragon race</b> ({@link #onBallsDefiled}): unlocked for the player who DEFILES the dragon balls
 *       (triggers the corrupted event), at the moment of defiling. Each player must defile the balls themselves to
 *       earn the base race; merely damaging a dragon no longer grants it.</li>
 *   <li><b>Shadow dragon sub-races</b> ({@link #onDragonDefeated}): the per-slot sub-race (slot N ->
 *       {@code shadow_dragon_Nstar}, slots 2..7). Unlocked for the player who dealt the TOP damage to that dragon and
 *       did NOT die during its fight, not the player who landed the killing blow.</li>
 *   <li><b>Shadow dragon super form</b> ({@link #onKillCredited}): the shadow dragon race's super transformation,
 *       PRIVATE to the Ragnarok Key (feature {@code shadowform}, {@code ShadowFormHooks}). Unlocked when the key's
 *       grant condition is met; records the durable entitlement and raises the DMZ {@code superforms} skill to level 1.
 *       That grant is race-agnostic, so it resolves to whichever form that race defines at level 1;
 *       {@code omega_shenron} is the base race's form and a historical name for the rung.</li>
 * </ol>
 *
 * <p>The defiler is online when they defile (they right-clicked the ball set), so the base-race reward message is
 * delivered immediately. The top-damage survivor may be offline at the dragon's death (they logged off after
 * fighting), so that grant uses the UUID variants and sets a pending-notice flag when offline. The omega form's DMZ
 * side needs the player online and playing the race, so the key applies it eagerly when possible (the killer is online
 * at grant time) and re-applies it at login otherwise.
 */
public final class ShadowDragonUnlocks
{
    private ShadowDragonUnlocks() {}

    /**
     * Grant the base shadow dragon RACE unlock to the player who just defiled the dragon balls. Called from {@link
     * CorruptedEventManager#trigger} at the moment of defiling, which is the qualifying act: each player must defile
     * the balls themselves to earn the base race. The defiler is online (they right-clicked the ball set), so the
     * reward message is delivered now. Idempotent: an already-unlocked defiler is not re-announced.
     */
    public static void onBallsDefiled(MinecraftServer server, ServerPlayer defiler)
    {
        if (server == null || defiler == null)
            return;
        UUID uuid = defiler.getUUID();
        if (RaceUnlocks.has(uuid, RaceUnlocks.SHADOW_DRAGON_RACE))
            return; // already earned; do not re-announce
        RaceUnlocks.grant(uuid, RaceUnlocks.SHADOW_DRAGON_RACE);
        LoggingHandler.sulog.info("[wishtracking] granted shadow_dragon race unlock to {} (defiled the balls)", uuid);
        announceShadowDragonRace(defiler);
        // Re-push the unlock set so the race carousel reveals the base shadow_dragon entry live, no relog.
        net.shurui.shuruisutilities.prestige.PrestigeManager.sendRaceUnlocks(defiler);
    }

    /**
     * Grant the per-slot shadow dragon SUB-RACE to the player who dealt the top damage to a dragon that just died and
     * did NOT die during its fight. Called from the death handler with the dragon's entity UUID so the tracker can be
     * queried for both the damage totals and the death set; the totals must be read BEFORE the tracker entry is
     * cleared. The winner may be offline (logged off after fighting), so the durable property is granted regardless of
     * online state and a pending notice is set for an offline winner. Slot 1 maps to no sub-race, so it never awards.
     */
    public static void onDragonDefeated(MinecraftServer server, int slot, UUID dragonId)
    {
        if (server == null || dragonId == null)
            return;
        if (!net.shurui.shuruisutilities.core.SUConfig.wishTrackingEnabled)
            return;
        String subRaceId = RaceUnlocks.subRaceForSlot(slot);
        if (subRaceId == null || !SubRaces.isSubRace(subRaceId))
            return; // slot 1, or an id with no race folder: nothing to award
        UUID winner = ShadowDragonDamageTracker.topSurvivor(dragonId);
        if (winner == null)
        {
            // nobody qualified: no damage recorded, or every contributor died during the fight.
            LoggingHandler.sulog.info("[wishtracking] no top-damage survivor for slot {}, sub-race '{}' not awarded",
                    slot, subRaceId);
            return;
        }
        if (RaceUnlocks.has(winner, subRaceId))
            return; // already earned; do not re-announce
        RaceUnlocks.grant(winner, subRaceId);
        LoggingHandler.sulog.info("[wishtracking] granted sub-race unlock '{}' to {} (slot {} top-damage survivor)",
                subRaceId, winner, slot);
        ServerPlayer online = server.getPlayerList().getPlayer(winner);
        if (online != null)
        {
            announceSubRace(online, slot);
            // Re-push their unlock set so the newly earned sub-race appears under its parent without a relog.
            net.shurui.shuruisutilities.prestige.PrestigeManager.sendRaceUnlocks(online);
        }
        else
            RaceUnlocks.setFlag(winner, RaceUnlocks.PENDING_SHADOW_DRAGON_FORM_PROP, true);
    }

    /**
     * Called after a killing blow has been recorded for a slot. Records the tallies, then hands over to the Ragnarok
     * Key, which grants the Omega Shenron transformation when its condition is newly met.
     */
    public static void onKillCredited(MinecraftServer server, ServerPlayer killer, ShadowDragonStorage storage, int slot)
    {
        if (server == null || killer == null || storage == null)
            return;
        UUID uuid = killer.getUUID();

        // Keep both tallies fed so the grant CONDITION below can key off either. Lifetime is race-agnostic; the
        // shadow-dragon-scoped tally only advances while the killer is currently that race (a saiyan's kills never
        // count toward it).
        storage.addKillCredit(uuid, slot);
        if (ShadowDragonFormCompat.isShadowDragon(killer))
            storage.addShadowDragonKillCredit(uuid, slot);

        // NOTE: the per-slot sub-race unlock is NO LONGER driven by the killing blow. It is awarded to the top-damage
        // survivor of the fight in onDragonDefeated (rule 2). Omega below still keys off the seven-slot killing-blow
        // tally, which is a separate, deliberately distinct condition; the two are decoupled by design.

        // Omega Shenron transformation: the Ragnarok Key's (feature shadowform). It grants once its condition is met,
        // reading the tallies recorded above. Keyless this is a no-op; the tallies still count.
        net.shurui.shuruisutilities.api.key.ShadowFormHooks.get().afterKillCredited(server, killer, storage, slot);
    }

    private static void announceSubRace(ServerPlayer player, int slot)
    {
        send(player, "&8&l>> &5You have earned the &d" + slot + "-Star Shadow Dragon &5form.");
        send(player, "&7Top damage, and you never fell. That corruption is yours to wear now: choose it from the "
                + "Shadow Dragon sub-forms.");
    }

    // slot-agnostic variant for a sub-race won while offline (the pending flag does not carry the slot).
    private static void announceSubRacePending(ServerPlayer player)
    {
        send(player, "&8&l>> &5You have earned a &dShadow Dragon &5sub-form.");
        send(player, "&7Top damage, and you never fell. That corruption is yours to wear now: choose it from the "
                + "Shadow Dragon sub-forms.");
    }

    /**
     * Cheap login pass. Master switch first; then does nothing at all when the player holds none of the unlock
     * properties. Otherwise:
     * <ul>
     *   <li>Hands the Omega form's pass to the Ragnarok Key: it re-applies the super form skill in DMZ when the player
     *       holds the omega entitlement AND is currently a shadow dragon (a DMZ character reset wipes the skill; the
     *       property is the durable entitlement), and delivers the Omega pending notice. Keyless: nothing.</li>
     *   <li>Delivers any pending reward notices set while the player was offline, then clears them.</li>
     * </ul>
     */
    public static void onLogin(ServerPlayer player)
    {
        if (!net.shurui.shuruisutilities.core.SUConfig.wishTrackingEnabled)
            return;
        if (player == null)
            return;
        UUID uuid = player.getUUID();

        // The Omega form's login pass (re-apply the form skill for an entitled shadow dragon, and its pending notice)
        // is the Ragnarok Key's (feature shadowform). Keyless it is a no-op and the pending flag is kept for a keyed
        // run.
        net.shurui.shuruisutilities.api.key.ShadowFormHooks.get().onLogin(player);

        // Pending notices for unlocks granted while offline.
        if (RaceUnlocks.flag(uuid, RaceUnlocks.PENDING_SHADOW_DRAGON_RACE_PROP))
        {
            announceShadowDragonRace(player);
            RaceUnlocks.setFlag(uuid, RaceUnlocks.PENDING_SHADOW_DRAGON_RACE_PROP, false);
        }
        // A sub-race won as top-damage survivor while offline. The flag does not carry the slot, so this is a
        // slot-agnostic notice; the entitlement itself was already granted, so the sub-form is already selectable.
        if (RaceUnlocks.flag(uuid, RaceUnlocks.PENDING_SHADOW_DRAGON_FORM_PROP))
        {
            announceSubRacePending(player);
            RaceUnlocks.setFlag(uuid, RaceUnlocks.PENDING_SHADOW_DRAGON_FORM_PROP, false);
        }
    }

    // All player-directed messages are pre-formatted through formatColors and sent as a Component, because
    // ChatOutputHandler's Player+String overload does NOT run formatColors (unlike the CommandSourceStack one), so
    // raw & codes would show verbatim. This bug was already hit once in this feature.

    private static void send(ServerPlayer player, String formatted)
    {
        player.sendSystemMessage(Component.literal(ChatOutputHandler.formatColors(formatted)));
    }

    private static void announceShadowDragonRace(ServerPlayer player)
    {
        send(player, "&8&l>> &5You have earned the &dShadow Dragon &5race.");
        send(player, "&7The corruption you unleashed now answers to you. Reset your character to walk its path.");
    }
}
