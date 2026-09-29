package net.shurui.dev.shuruis_dmz_tournaments.character;

import java.util.ArrayList;
import java.util.List;

import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.techniques.KiAttackData;
import com.dragonminez.common.stats.techniques.PredefinedTechniques;
import com.dragonminez.common.stats.techniques.Techniques;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.compat.DmzBridge;
import net.shurui.shuruisutilities.energy.EnergyManager;
import net.shurui.shuruisutilities.god.RoleMove;

/**
 * The single "may this player legitimately equip this attack outside a tournament" gate, reused by BOTH the pick
 * list the server offers and the server-side save validation. Because the list and the validation ask the same
 * question, a hand-crafted save packet cannot pick a move the screen would have hidden.
 *
 * <p>WHY THE TOURNAMENT NEEDS THIS. A tournament fighter is a blank-slate character built from DMZ's predefined
 * technique registry, which the suite ALSO injects its restricted moves into: the shadow dragon kit (race-gated),
 * the god of destruction / angel role moves (hakai and the sphere of destruction, title-gated) and the mini clone
 * (a quest reward). Offering the whole registry handed every player moves they could never cast normally. This
 * filters the registry per player so the list only holds moves that player could hold on a real character.
 *
 * <p>WHAT DECIDES EACH KIND. DMZ tags its own attacks with an {@code allowedRaces} list holding the sentinel
 * {@code "ALL"}; the shadow dragon kit is tagged with its real dragon races; the role moves are registered with an
 * EMPTY list because a title holder can be any race, and the mini clone is registered with an empty list too. So:
 * strikes are ungated, an {@code "ALL"} ki is open to everyone, a real race list is checked against the player's DMZ
 * race, a role move is checked against the live title, and any other empty-list move (the mini clone) must already be
 * unlocked on the player. Fails closed: any lookup failure hides the move rather than leaking it.
 *
 * <p>WHICH RACE / TITLE IS READ. The LIVE player character's, which is correct in every path: during full creation
 * the live character IS the tournament fighter being built (with the race just chosen for it), and the loadout-edit
 * path rebuilds the template from the live real character anyway, so the fighter's race always matches the live one
 * the gate reads. The role title lives on the player, not on a DMZ character, so it reads correctly regardless.
 */
public final class TournamentMoveAccess {
    private TournamentMoveAccess() {}

    /** DMZ's sentinel race, set on every attack it ships, meaning "no race restriction". */
    private static final String RACE_ALL = "ALL";

    /**
     * True when the player could legitimately equip this attack id on a real character. See the class note for the
     * ordering of the checks.
     */
    public static boolean mayUse(ServerPlayer player, String id) {
        if (player == null || id == null) return false;
        try {
            // Strike attacks carry no race or unlock gate in DMZ: everyone may use them.
            if (TournamentAttacks.isStrikeAttack(id)) return true;

            KiAttackData def = PredefinedTechniques.REGISTRY.get(id);
            if (def == null) return false; // not an attack we offer

            // Role moves (god of destruction / angel) are title-gated, not race-gated: they are registered with no
            // allowedRaces, so DMZ has nothing to filter on and the title is the only gate.
            RoleMove role = RoleMove.byId(id);
            if (role != null) return EnergyManager.hasAccess(player, role.kind);

            List<String> races = def.getAllowedRaces();
            if (races != null && !races.isEmpty()) {
                for (String r : races) {
                    if (RACE_ALL.equalsIgnoreCase(r)) return true; // one of DMZ's own moves: open to all
                }
                // A real race list (the shadow dragon kit): the player's DMZ race must be in it.
                String race = raceName(player);
                if (race == null || race.isEmpty()) return false;
                for (String r : races) {
                    if (race.equalsIgnoreCase(r)) return true;
                }
                return false;
            }

            // Empty allowedRaces and not a role move: an unlock-only special such as the mini clone quest reward.
            // Offer it only if the player has actually unlocked it.
            return isUnlocked(player, id);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when the attack is open to every player regardless of race, title or unlock: a strike, or a ki tagged
     * {@code "ALL"}. Used to build the auto-fill default loadout, which has no player context and so must never
     * contain a restricted move.
     */
    public static boolean isUniversal(String id) {
        if (id == null) return false;
        try {
            if (TournamentAttacks.isStrikeAttack(id)) return true;
            KiAttackData def = PredefinedTechniques.REGISTRY.get(id);
            if (def == null) return false;
            if (RoleMove.byId(id) != null) return false; // a title-gated role move is never universal
            List<String> races = def.getAllowedRaces();
            if (races == null || races.isEmpty()) return false; // an unlock-only special is not universal
            for (String r : races) {
                if (RACE_ALL.equalsIgnoreCase(r)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The player's ids from {@code ids}, in order, keeping only those {@link #mayUse} allows. */
    public static List<String> filter(ServerPlayer player, List<String> ids) {
        List<String> out = new ArrayList<>();
        if (ids == null) return out;
        for (String id : ids) {
            if (mayUse(player, id)) out.add(id);
        }
        return out;
    }

    private static String raceName(ServerPlayer player) {
        StatsData stats = DmzBridge.stats(player);
        if (stats == null || stats.getCharacter() == null) return null;
        return stats.getCharacter().getRaceName();
    }

    private static boolean isUnlocked(ServerPlayer player, String id) {
        StatsData stats = DmzBridge.stats(player);
        if (stats == null) return false;
        Techniques techniques = stats.getTechniques();
        return techniques != null && techniques.getUnlockedTechniques().containsKey(id);
    }
}
