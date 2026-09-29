package net.shurui.dev.shuruis_dmz_tournaments.character;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.techniques.KiAttackData;
import com.dragonminez.common.stats.techniques.PredefinedTechniques;
import com.dragonminez.common.stats.techniques.StrikeAttackData;
import com.dragonminez.common.stats.techniques.TechniqueData;
import com.dragonminez.common.stats.techniques.Techniques;

/**
 * Bridge to DragonMineZ's predefined attack techniques: exposes the pickable ki and strike attacks and grants a
 * chosen loadout onto a {@link StatsData} (for the tournament character template). All lookups are null-safe, so a
 * server whose DMZ registries are not yet populated never crashes the flow.
 */
public final class TournamentAttacks {
    private TournamentAttacks() {}

    // How many attack ids can be equipped at once (DMZ technique equip slots).
    public static final int EQUIP_SLOTS = Techniques.SLOT_COUNT;

    // A fighter is a blank slate: it picks EXACTLY this many ki and this many strike attacks, free, from the full
    // registries (no ownership/skill/race gating). The two counts together fill the equip slots.
    public static final int KI_SLOTS = 4;
    public static final int STRIKE_SLOTS = 4;

    public static boolean isKiAttack(String id) {
        if (id == null) return false;
        try {
            return PredefinedTechniques.REGISTRY.containsKey(id);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isStrikeAttack(String id) {
        if (id == null) return false;
        try {
            return PredefinedTechniques.STRIKE_REGISTRY.containsKey(id);
        } catch (Throwable t) {
            return false;
        }
    }

    // Every ki attack id DMZ ships, in registry order. Empty if the registry is not populated yet.
    public static List<String> kiIds() {
        try {
            return new ArrayList<>(PredefinedTechniques.REGISTRY.keySet());
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    // Every strike attack id DMZ ships, in registry order.
    public static List<String> strikeIds() {
        try {
            return new ArrayList<>(PredefinedTechniques.STRIKE_IDS);
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    // Ki ids followed by strike ids: the full pick-list for the loadout screen.
    public static List<String> allIds() {
        List<String> out = new ArrayList<>(kiIds());
        out.addAll(strikeIds());
        return out;
    }

    public static boolean isKnownAttack(String id) {
        if (id == null) return false;
        try {
            return PredefinedTechniques.REGISTRY.containsKey(id) || PredefinedTechniques.STRIKE_REGISTRY.containsKey(id);
        } catch (Throwable t) {
            return false;
        }
    }

    // Fallback when a fighter never picked one (see TournamentCharacter.enter): first KI_SLOTS ki then first
    // STRIKE_SLOTS strike, always a valid 4-and-4. The ONLY place a loadout is auto-filled; a player save is
    // rejected rather than padded. Only UNIVERSAL moves are used (see TournamentMoveAccess.isUniversal): this runs
    // with no player context, and the registry now also holds race-locked and title / unlock gated moves, so the raw
    // registry order (a HashMap, unordered) could otherwise bake, say, hakai or a shadow dragon move into a fighter
    // of any race.
    public static List<String> defaultLoadout() {
        List<String> out = new ArrayList<>();
        int ki = 0;
        for (String id : kiIds()) {
            if (ki >= KI_SLOTS) break;
            if (TournamentMoveAccess.isUniversal(id)) { out.add(id); ki++; }
        }
        int strike = 0;
        for (String id : strikeIds()) {
            if (strike >= STRIKE_SLOTS) break;
            if (TournamentMoveAccess.isUniversal(id)) { out.add(id); strike++; }
        }
        return out;
    }

    // Fresh per-player copy of a predefined technique (never the shared registry template), or null.
    private static TechniqueData copyOf(String id) {
        try {
            KiAttackData ki = PredefinedTechniques.REGISTRY.get(id);
            if (ki != null) {
                KiAttackData copy = new KiAttackData();
                copy.load(ki.save());
                return copy;
            }
            StrikeAttackData strike = PredefinedTechniques.STRIKE_REGISTRY.get(id);
            if (strike != null) {
                StrikeAttackData copy = new StrikeAttackData();
                copy.load(strike.save());
                return copy;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Wipe the character's techniques and grant exactly the chosen loadout: each id unlocked and equipped in order
     * (capped at {@link #EQUIP_SLOTS}). Unknown ids skipped, duplicates dropped. Runs on the passed-in StatsData, so
     * it rides inside the tournament template snapshot.
     */
    public static void applyLoadout(StatsData data, List<String> ids) {
        if (data == null) return;
        Techniques techniques = data.getTechniques();
        techniques.clearAllTechniques();
        Set<String> seen = new LinkedHashSet<>();
        int slot = 0;
        for (String id : ids) {
            if (slot >= EQUIP_SLOTS) break;
            if (id == null || !seen.add(id)) continue;
            TechniqueData copy = copyOf(id);
            if (copy == null) continue;
            techniques.unlockTechnique(copy);
            techniques.equipTechnique(slot, copy.getId());
            slot++;
        }
    }
}
