package net.shurui.shuruisutilities.prestige;

import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Stats;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.character.CharacterSlots;
import net.shurui.shuruisutilities.character.CommandCharacter;
import net.shurui.shuruisutilities.character.ZSoulBridge;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

// prestige system. a char with all six DMZ stats maxed can prestige at a prestige NPC: their DMZ progress
// (stats, skills/abilities, mastery, techniques, bonus stats, quest data, dynamic growth) and beyond-cap
// Z-Soul progress get wiped, and each prestige level grants a permanent TP-earned bonus (see totalTpPct).
// prestige is per character slot (CharacterSlots.getActivePrestige), so each char climbs independently.
// bonus and max prestige are configurable (prestigeTpBonusPerLevel, prestigeMax). an extra
// permission-driven TP bonus stacks on top via the su.tpgain value-permission.
//
// S19b: prestige is PRIVATE (owner Q1), so this class is now a facade by FQN. What a player has already earned stays
// here and works keyless: the level (per character slot), the TP-gain and stat-cap bonuses, the name colour, and the
// client syncs of those and of the race locks and hard-difficulty gate (CharacterSlots, StatCapOverrides,
// CommandCharacter and the prestige mixins read them). The ACTIONS (prestiging, rewards, the ledger bookkeeping, the
// floor, the admin reset) live in the Ragnarok Key and are reached through api.key.PrestigeHooks.
public final class PrestigeManager
{
    private PrestigeManager() {}

    /** Value-permission: extra TP gain in percent, added on top of the prestige bonus. Shows in the perm GUI. */
    public static final String TP_GAIN_PROP = "su.tpgain";

    // per-player reward ledger: highest prestige this player was ever REWARDED for. per-user value-permission
    // (same per-UUID store as SLOT_LIMIT_PROP, survives character deletion). when ANY of the player's chars
    // climbs above the ledger, grant one slot per newly-crossed level and bump the ledger. seeded once on
    // first encounter to the current highest prestige, so existing players get no retroactive slots.
    public static final String REWARDED_PROP = "su.prestige.rewarded";

    // group a player joins on reaching max prestige, created on demand
    public static final String CITIZEN_GROUP = "citizen";

    // The commemorative rank for players who helped before the 1.0 launch, and the low-priority permission group
    // that carries it. Both share the stored rank name "veteran" (see rank_index.json, where the persisted NAME,
    // not the "Veteran" display, is the key badges are looked up by). Granted from maybeGrantVeteran; it is never
    // obtainable any other way.
    public static final String VETERAN_GROUP = "veteran";
    public static final String VETERAN_RANK = "veteran";

    // The 1.0 world migration was 2026-09-06: the overworld was replaced, the database cleared and every player
    // repositioned. "Pre-1.0", i.e. "helped pre 1.0", means a player whose SU record (PlayerInfo.firstLogin) was
    // first created before this instant. This cutoff is the WHOLE POINT of the veteran grant and must not be
    // "simplified" away: the prestige floor set by /prestige forceall keeps applying forever, to everyone, the
    // first time they log in, so without this gate a brand new player joining next year would be handed this
    // never-obtainable-again rank the moment the floor first raised them. Change the date here to move the line.
    // Start-of-day UTC on the migration date is the boundary; a first login at or after it is a post-1.0 player.
    public static final long PRE_1_0_CUTOFF_MILLIS =
            java.time.LocalDate.of(2026, 9, 6).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();

    public static int maxPrestige(ServerPlayer p)
    {
        return p.getServer() == null ? Math.max(0, SUConfig.prestigeMax)
                : PrestigeSettings.get(p.getServer()).getMaxPrestige();
    }

    public static int tpBonusPerLevel(ServerPlayer p)
    {
        return p.getServer() == null ? Math.max(0, SUConfig.prestigeTpBonusPerLevel)
                : PrestigeSettings.get(p.getServer()).getTpBonusPerLevel();
    }

    /** The active character slot's prestige level. */
    public static int level(ServerPlayer p)
    {
        return CharacterSlots.getActivePrestige(p);
    }

    /** TP-gain bonus percent from prestige alone (level x per-level %). */
    public static int prestigeTpPct(ServerPlayer p)
    {
        return level(p) * tpBonusPerLevel(p);
    }

    /** TP-gain bonus percent from the {@code su.tpgain} value-permission. */
    public static int permTpPct(ServerPlayer p)
    {
        try
        {
            Integer v = APIRegistry.perms.getUserPermissionPropertyInt(UserIdent.get(p), TP_GAIN_PROP);
            return v == null ? 0 : Math.max(0, v);
        }
        catch (Throwable t)
        {
            return 0;
        }
    }

    /** Total TP-gain bonus percent applied to earned TP (prestige + permission). */
    public static int totalTpPct(ServerPlayer p)
    {
        return prestigeTpPct(p) + permTpPct(p);
    }

    /**
     * Push the player's current SU TP-gain bonus percent to their client so DragonMineZ's "TP Multiplier"
     * X-menu tooltip can show it (and fold it into the shown Total). Call on login, after prestiging, and
     * on character-slot switch. Guarded: a no-op if networking isn't ready.
     */
    public static void sendTpMult(ServerPlayer p)
    {
        try
        {
            NetworkUtils.sendTo(new PacketSuTpMult(totalTpPct(p), globalTpBoostFactor()), p);
            // Piggyback the prestige stat-cap boost so the client-side DMZ clamp / +stat button gating tracks
            // the widened cap. Sent from the same call sites as the TP mult (login, prestige, slot switch).
            NetworkUtils.sendTo(new PacketSuCapMult(PrestigeCaps.capMultPct(p)), p);
            // Push the prestige-gated race lock state so sdu's race-select screen (if installed) greys/blocks
            // races this character can't yet pick. Fires from the exact same login / prestige / slot-switch
            // moments as the TP + cap sync, so the lock set always tracks the active slot's prestige.
            sendRaceLock(p);
            // Same moments: tell the client whether this character may pick the HARD saga difficulty (prestige
            // >= 1), so sdu's saga screen locks HARD for a never-prestiged player. The server refuses HARD
            // through the same HardSagaGateBridge.mayUseHard rule, so the two can't disagree.
            NetworkUtils.sendTo(new PacketHardSagaGate(HardSagaGateBridge.mayUseHard(p)), p);
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * Push the per-player prestige-gated race lock state to {@code p}'s client (the full race-&gt;required map
     * plus the set of races currently locked for this player's active-slot prestige). No-op if networking or
     * the server isn't ready. Sent alongside {@link #sendTpMult}; also fired to every player after an admin
     * edit via {@link #sendRaceLockToAll}.
     */
    public static void sendRaceLock(ServerPlayer p)
    {
        try
        {
            if (p.getServer() == null)
                return;
            java.util.Map<String, Integer> required = PrestigeSettings.get(p.getServer()).getRaceRequiredPrestige();
            int lvl = level(p);
            java.util.Set<String> locked = new java.util.HashSet<>();
            // Prestige axis: locked when the active slot's prestige is below the race's requirement.
            for (java.util.Map.Entry<String, Integer> e : required.entrySet())
                if (e.getValue() != null && lvl < e.getValue())
                    locked.add(e.getKey());
            // Unlock axis (independent of prestige AND of the wish-tracking master switch): the shadow dragon races
            // stay locked, even at max prestige, until the player has earned the matching per-UUID unlock. Folded into
            // the SAME locked set so the existing PacketRaceLockSync greys them client-side with no new packet. Always
            // folded in now: the race files are installed on every DMZ server regardless of the switch, so the races DO
            // exist in the select screen and must be greyed whenever unowned. Reflects real ownership, so an admin
            // grant (which works with the switch off) is honoured client-side too.
            //
            // IMPORTANT: this set means "locked for ANY reason", NOT "prestige-locked". It feeds both SU's
            // RaceLockLocalClient and, by reflection, sdu's RaceLockClient, and sdu's carousel block guard
            // (sdu$blockLockedRaceSelect) depends on these ids being present to keep them unpickable. A consumer must
            // NOT assume a member here has a prestige requirement: that exact assumption is what drew a bogus
            // "Requires Prestige 0" padlock over the ritual-gated shadow dragon. Cross-check requiredLevel > 0 before
            // treating an entry as prestige-locked.
            for (String raceId : net.shurui.shuruisutilities.corrupted.RaceUnlocks.UNLOCK_GATED_RACES)
                if (!net.shurui.shuruisutilities.corrupted.RaceUnlocks.has(p, raceId))
                    locked.add(raceId);
            // Operator axis: a staff-only race is locked for everybody who is not an operator, at any prestige.
            // Folded into the same set for the same reason as the unlock axis: it needs no packet of its own and
            // the carousel's block guard already refuses anything named here. An operator is sent nothing, so the
            // race is pickable for them with no special case client-side.
            if (!p.hasPermissions(2))
                for (String raceId : PrestigeSettings.get(p.getServer()).getOpOnlyRaces())
                    locked.add(raceId);
            NetworkUtils.sendTo(new PacketRaceLockSync(required, locked), p);

            // Sub-race system (batch B): push the POSITIVE unlock set (gated race ids this player OWNS) so the SU
            // race-select filter mixin and the sub-race screen can hide what they have not earned. Separate from the
            // prestige lock set above (which is a NEGATIVE set of what is locked); this one is the player's own
            // entitlements. Sent from the same call sites as the prestige sync. The client cache fails closed when it
            // is missing, so nothing is leaked if wish-tracking is off or the packet never arrives.
            sendRaceUnlocks(p);
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * Push the set of unlock-gated race ids the player currently OWNS to their client (the base {@code shadow_dragon}
     * race and any earned shadow dragon sub-races). {@code half_saiyan} is a free sub-race, not gated, so it is never
     * included. Sent alongside {@link #sendRaceLock}. No-op if networking or the server is not ready. The owned set
     * reflects real per-UUID ownership regardless of the wish-tracking master switch: the race files are installed on
     * every DMZ server now, so a player who owns an unlock (earned via the cinematic when the switch is on, or granted
     * by an admin at any time) must have their client reflect it so they can actually select the race. A player who
     * owns nothing gets an empty "received, nothing unlocked" sync, which keeps gated races fail-closed (padlocked or
     * hidden) rather than being revealed by a missing packet.
     */
    public static void sendRaceUnlocks(ServerPlayer p)
    {
        try
        {
            if (p.getServer() == null)
                return;
            java.util.Set<String> owned = new java.util.HashSet<>();
            // Base gated main races the player owns (currently just shadow_dragon).
            for (String raceId : net.shurui.shuruisutilities.corrupted.RaceUnlocks.UNLOCK_GATED_RACES)
                if (net.shurui.shuruisutilities.corrupted.RaceUnlocks.has(p, raceId))
                    owned.add(raceId);
            // Every registered sub-race the player owns. isRaceUnlockGated is false for free sub-races
            // (half_saiyan), so those are skipped here; the filter treats free sub-races as always available.
            for (String subId : net.shurui.shuruisutilities.subrace.SubRaces.allSubRaceIds())
                if (net.shurui.shuruisutilities.corrupted.RaceUnlocks.isRaceUnlockGated(subId)
                        && net.shurui.shuruisutilities.corrupted.RaceUnlocks.has(p, subId))
                    owned.add(subId);
            NetworkUtils.sendTo(new net.shurui.shuruisutilities.corrupted.network.PacketRaceUnlockSync(owned), p);
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * The live global {@code /tpboost} window as a multiplicative TP factor (1.0 = no active window). This is
     * the same factor the key's {@code TpBoostState} applies to earned TP, surfaced in the stats-screen TP
     * Multiplier tooltip, read through {@link net.shurui.shuruisutilities.api.key.TpBoostHooks}. Per-player shrine
     * TP buffs and sdu TP-token buffs also multiply real TP but are not folded in here (see the batch report).
     * Guarded: 1.0 if TpBoost is absent (keyless, or the module is off).
     */
    private static double globalTpBoostFactor()
    {
        try
        {
            return net.shurui.shuruisutilities.api.key.TpBoostHooks.get().globalFactor();
        }
        catch (Throwable ignored)
        {
        }
        return 1.0;
    }

    /** Re-push the SU TP multiplier (including the current global TP boost) to every online player. Called when
     *  the global {@code /tpboost} window starts, stops, or expires so an open stats screen reflects it live. */
    public static void sendTpMultToAll(MinecraftServer server)
    {
        if (server == null)
            return;
        for (ServerPlayer sp : server.getPlayerList().getPlayers())
            sendTpMult(sp);
    }

    /** Re-push the race lock state to every online player (after an admin edits the gate map). */
    public static void sendRaceLockToAll(MinecraftServer server)
    {
        if (server == null)
            return;
        for (ServerPlayer sp : server.getPlayerList().getPlayers())
            sendRaceLock(sp);
    }

    /**
     * Whether the character is at max LEVEL (the gate required to prestige). Despite the historical name, this
     * no longer checks the six individual stats: prestige eligibility is now "player is at max level". DMZ's
     * getLevel() is internally clamped to getConfiguredMaxValue() (a server config), so comparing the two works
     * in both DMZ progression modes, including maxLevelValueInsteadOfStats where the old per-stat check could
     * never pass.
     */
    public static boolean statsMaxed(StatsData sd)
    {
        int cap = sd.getConfiguredMaxValue();
        if (cap <= 0)
            return false;
        return sd.getLevel() >= cap;
    }


    /**
     * Take the player's ACTIVE character back to scratch: stats, TP, pending points, bonus stats, every DMZ
     * progression subsystem and all saga/quest progress. Does NOT touch the prestige level, the reward ledger or
     * anything the player keeps across a prestige, and does not check whether they are eligible to prestige.
     *
     * <p>The single reset used by all three callers, which is the point of it being one method: a normal
     * {@link #prestige}, the server-wide floor raising somebody, and a parked character being switched to after the
     * floor raised it. Three copies of a reset this wide would drift, and the copy that drifted would be the one
     * that only runs during a once-per-release mass reset.
     *
     * @return true if the wipe ran; false only when the player has no DMZ character to wipe
     */
    public static boolean wipeActiveCharacter(ServerPlayer p)
    {
        StatsData sd = p.getCapability(StatsCapability.INSTANCE).resolve().orElse(null);
        if (sd == null)
            return false;
        wipeActiveCharacter(p, sd);
        return true;
    }

    /** The same reset on an already resolved character (the key's prestige, which checked it first). */
    public static void wipeActiveCharacter(ServerPlayer p, StatsData sd)
    {
        // full DMZ wipe (skills/abilities, mastery/interacted masters, techniques, quest data, dynamic
        // growth). resetPlayerProgress touches many subsystems and can throw mid-way, so guard it then
        // GUARANTEE the reset with plain setters that can't throw: a partial failure leaves the char still
        // maxed, which kills every stat +button ("can't level after prestiging").
        try { sd.resetPlayerProgress(p, null, false, false); }
        catch (Throwable t) { LoggingHandler.sulog.error("[Prestige] resetPlayerProgress failed; resetting manually", t); }

        Stats st = sd.getStats();
        st.setStrength(0);
        st.setStrikePower(0);
        st.setResistance(0);
        st.setVitality(0);
        st.setKiPower(0);
        st.setEnergy(0);
        sd.getResources().setTrainingPoints(0f);
        sd.getResources().setPendingAttributePoints(0);
        try { sd.getBonusStats().clearAllStats(); } catch (Throwable ignored) {}
        // Transformation and stack-form mastery live in the Character's mastery maps, which DMZ's
        // resetPlayerProgress never touches: it clears the ACTIVE form and the interacted masters but leaves
        // the per-form mastery totals intact. Without this, a prestige left Oozaru (a form) and Kaioken x2 (a
        // stack form) at their old, often maxed, mastery on the fresh character (bug 671). Reuse the exact
        // "fresh character" wipe the character-slot code uses so the two lists cannot drift apart.
        try { CharacterSlots.clearMasteries(sd); } catch (Throwable ignored) {}
        // Reset ALL saga/quest progress too (DMZ + our sagas run through DMZ's PlayerQuestData).
        try { sd.getPlayerQuestData().resetAll(); } catch (Throwable ignored) {}

        // Beyond-cap Z-Soul progress is reset too (no-op if Raid Bosses isn't installed).
        ZSoulBridge.reset(p);
    }


    /**
     * The colour this player's name is drawn in, from their active character's prestige, or null for none.
     *
     * <p>Returned as a {@link net.minecraft.ChatFormatting} rather than a string so callers style a Component with
     * it instead of splicing section signs into a name, which is what makes it survive being re-templated by the
     * chat format or the tab list.
     */
    public static net.minecraft.ChatFormatting colourOf(ServerPlayer p)
    {
        try
        {
            if (p == null || p.getServer() == null)
                return null;
            int level = level(p);
            if (level <= 0)
                return null;
            String name = PrestigeSettings.get(p.getServer()).getColourFor(level);
            return parseColour(name);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /**
     * A colour name or legacy code to a {@link net.minecraft.ChatFormatting}, or null.
     *
     * <p>Accepts what an admin is likely to type: {@code gold}, {@code light_purple}, {@code 6}, {@code &6},
     * {@code §6}. Formatting codes that are not colours (bold, obfuscated) are refused - this styles a name, and a
     * name set to "obfuscated" is a support ticket.
     */
    public static net.minecraft.ChatFormatting parseColour(String name)
    {
        if (name == null || name.isBlank())
            return null;
        String cleaned = name.trim();
        if (cleaned.length() == 2 && (cleaned.charAt(0) == '&' || cleaned.charAt(0) == '§'))
            cleaned = cleaned.substring(1);
        net.minecraft.ChatFormatting found = cleaned.length() == 1
                ? net.minecraft.ChatFormatting.getByCode(cleaned.charAt(0))
                : net.minecraft.ChatFormatting.getByName(cleaned);
        return found != null && found.isColor() ? found : null;
    }


    // ---- Prestige ACTIONS: private (owner Q1), in the Ragnarok Key since S19b. These statics keep their signatures
    // ---- so every caller compiles unchanged; they route through PrestigeHooks, whose keyless defaults refuse the
    // ---- prestige and run no reward, ledger write, floor or reset.

    /** Whether this character may prestige now. Keyless: false. */
    public static boolean canPrestige(ServerPlayer p)
    {
        return net.shurui.shuruisutilities.api.key.PrestigeHooks.get().canPrestige(p);
    }

    /** Perform a prestige on the player's active character. Returns null on success, or an error message. */
    public static String prestige(ServerPlayer p)
    {
        return net.shurui.shuruisutilities.api.key.PrestigeHooks.get().prestige(p);
    }

    /** The admin reset of a player's prestige (called from /rgreset). Keyless: nothing. */
    public static void resetPrestige(ServerPlayer p)
    {
        net.shurui.shuruisutilities.api.key.PrestigeHooks.get().resetPrestige(p);
    }

    /** Raise this player to the server-wide prestige floor, if one is set. Keyless: 0, nothing. */
    public static int applyMinimum(ServerPlayer p)
    {
        return net.shurui.shuruisutilities.api.key.PrestigeHooks.get().applyMinimum(p);
    }

    /** Backfill standing access to the level kits this player already reached. Keyless: nothing. */
    public static void backfillLevelKitUnlocks(ServerPlayer p)
    {
        net.shurui.shuruisutilities.api.key.PrestigeHooks.get().backfillLevelKitUnlocks(p);
    }

    /** One-time "only going forward" seed of the reward ledger. Keyless: nothing. */
    public static void seedLedgerIfUnset(ServerPlayer p)
    {
        net.shurui.shuruisutilities.api.key.PrestigeHooks.get().seedLedgerIfUnset(p);
    }
}
