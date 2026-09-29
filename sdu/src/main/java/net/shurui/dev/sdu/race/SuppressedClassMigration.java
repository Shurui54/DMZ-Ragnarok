package net.shurui.dev.sdu.race;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.RaceStatsConfig;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;
import com.dragonminez.common.stats.character.Character;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import net.shurui.dev.sdu.DmzNpc;

import java.util.Locale;

/**
 * Moves a player OFF a class that no longer has real stat-scaling config the moment they log in: a suppressed
 * default class (in practice {@code tank}) OR a class an admin deleted from the race entirely. Both leave DMZ
 * synthesizing a default {@code ClassStats} whose scalings are all 1.0, which the stats screen shows as a flat
 * {@code +1} per point (bug 750).
 *
 * <p>Hiding {@code tank} from the picker and the {@code /dmzclass} command (see
 * {@link net.shurui.dev.sdu.mixin.RaceClassListSuppressMixin}) stops anyone NEW from taking it, but a player whose
 * saved {@code Character.Class} is already {@code tank} keeps it forever: DMZ persists the class per player and never
 * revalidates it against the live class list. Those players are also the live trigger that resurrects the class into
 * DMZ's in-memory maps every stat recompute (DMZ's {@code getClassStats} re-creates any class it is asked for), so
 * migrating them is what actually makes the class stop coming back, not only the enumeration filter.
 *
 * <p>Mirrors DMZ's own {@code ClassCommand.applyClass}: snapshot the multiplier resources, set the new class, restore
 * the gains, then push a {@link StatsSyncS2C} to the player and trackers. Server side only; runs once per login, so an
 * offline player is migrated on their next join and a player already online when this ships on their next relog.
 */
public final class SuppressedClassMigration {

    /**
     * Where players on a suppressed class land: berserker, the owner's choice for former tanks (2026-09-13). Every race
     * that ever offered tank also offers berserker. The code still refuses it if it is itself suppressed or missing
     * for a race, and then falls back to the first class the race still offers.
     */
    private static final String FALLBACK_CLASS = "berserker";

    private SuppressedClassMigration() {
    }

    public static void migrateIfSuppressed(ServerPlayer player) {
        if (player == null || !ModList.get().isLoaded("dragonminez")) {
            return;
        }
        StatsData data = StatsProvider.<StatsData>get(StatsCapability.INSTANCE, player).resolve().orElse(null);
        if (data == null) {
            return;
        }
        Character character = data.getCharacter();
        if (character == null) {
            return;
        }
        String current = character.getCharacterClass();
        if (current == null || current.isBlank()) {
            return;
        }
        // Two reasons to move a player off their stored class, both of which leave DMZ synthesizing a default
        // ClassStats (every stat scaling 1.0, so the stats screen shows a flat +1 per point):
        //   suppressed - a default DMZ class we retired (tank); stripped from DMZ's in-memory maps by DmzCompat but
        //                still present on disk, so getClassStats finds nothing and synthesizes the default.
        //   removed    - a class an admin deleted from the race entirely; absent from stats.json on disk, so the same
        //                synthesis happens. Checked against the AUTHORITATIVE on-disk class list (not getAllClasses(),
        //                which DMZ's mutating getClassStats pollutes with the very stand-in we are trying to detect).
        boolean suppressed = SuppressedDefaultsConfig.isClassSuppressed(current);
        boolean removed = false;
        if (!suppressed) {
            java.util.Set<String> defined = RaceFileManager.definedClassIds(character.getRaceName());
            // Only act when we actually have a class list to compare against, so an unreadable or classless race file
            // never migrates a player off a class that is in fact valid.
            removed = !defined.isEmpty() && !defined.contains(current.toLowerCase(Locale.ROOT));
        }
        if (!suppressed && !removed) {
            return;
        }
        String reason = suppressed ? "suppressed" : "removed";
        String replacement = pickReplacement(character.getRaceName(), current);
        if (replacement == null) {
            DmzNpc.LOGGER.warn("[{}] {} is on {} class '{}' but no replacement class is available for race '{}'; left unchanged.",
                    DmzNpc.MODID, player.getGameProfile().getName(), reason, current, character.getRaceName());
            return;
        }
        float[] snapshot = data.snapshotMultiplierResources();
        character.setCharacterClass(replacement);
        data.restoreMultiplierGains(player, snapshot);
        NetworkHandler.sendToTrackingEntityAndSelf(new StatsSyncS2C(player), player);
        DmzNpc.LOGGER.info("[{}] Migrated {} off {} class '{}' to '{}'.",
                DmzNpc.MODID, player.getGameProfile().getName(), reason, current, replacement);
    }

    // Prefer FALLBACK_CLASS, but only if it is a real, non-suppressed class for this race. Otherwise take the first
    // class the race still offers (getAllClasses is already filtered of suppressed ids by RaceClassListSuppressMixin).
    private static String pickReplacement(String raceName, String current) {
        RaceStatsConfig stats = ConfigManager.getRaceStats(raceName);
        if (stats == null) {
            return isSuppressed(FALLBACK_CLASS) ? null : FALLBACK_CLASS;
        }
        java.util.Collection<String> available = stats.getAllClasses();
        for (String id : available) {
            if (FALLBACK_CLASS.equalsIgnoreCase(id) && !isSuppressed(id)) {
                return id;
            }
        }
        for (String id : available) {
            if (id != null && !isSuppressed(id) && !id.equalsIgnoreCase(current)) {
                return id;
            }
        }
        return null;
    }

    private static boolean isSuppressed(String id) {
        return id != null && SuppressedDefaultsConfig.isClassSuppressed(id.toLowerCase(Locale.ROOT));
    }
}
