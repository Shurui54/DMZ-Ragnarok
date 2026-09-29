package net.shurui.dev.sdu.passive;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.dragonminez.common.passives.ClassPassives;

import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.race.RaceData;
import net.shurui.dev.sdu.race.RaceFileManager;

/**
 * Registers a {@link SduClassPassive} for every class an admin has created, so custom classes have working
 * passives instead of a toggle that does nothing.
 *
 * <h2>Why registration is needed at all</h2>
 * DragonMineZ resolves a player's passive by class id through its own registry, which it fills in a static block
 * with its seven handlers. A class id it has never heard of resolves to {@code ClassPassives.NONE}: the config
 * can carry an enabled passive with values in it and nothing will ever read them. Registering one generic handler
 * per custom id is what connects the two.
 *
 * <h2>Never DMZ's own seven</h2>
 * The registry is a plain map, so registering an id DMZ already owns would REPLACE its handler and silently
 * delete a bespoke mechanic (the warrior's Fury stacks, the paladin's redirection) in favour of a config-driven
 * stand-in. Those ids are skipped, always. An admin who names a class "warrior" gets DMZ's warrior passive, which
 * is the better of the two outcomes and cannot be confused for a bug the way a silently gutted passive would be.
 */
public final class SduClassPassives {

    private SduClassPassives() {
    }

    /** DMZ's own class ids, which own their handlers and must never be replaced. */
    private static final Set<String> DMZ_OWNED = Set.of(
            "warrior", "spiritualist", "martialartist", "berserker", "paladin", "tank", "cleric");

    /** What we have already registered, so a re-scan after a race save is idempotent. */
    private static final Set<String> REGISTERED = new HashSet<>();

    /**
     * Scan every race config and register a passive for each class id that does not have one.
     *
     * <p>Safe to call repeatedly: it is called at server start and again after a race is saved, because a class
     * added in the editor has to work without a restart. Registering the same id twice would merely overwrite our
     * own handler with an identical one, but the guard keeps the log honest about what is new.
     */
    public static void refresh() {
        int added = 0;
        try {
            for (RaceData race : RaceFileManager.loadAll()) {
                if (race == null || race.classes == null) {
                    continue;
                }
                for (String id : race.classes.keySet()) {
                    if (id == null || id.isBlank()) {
                        continue;
                    }
                    String key = id.toLowerCase(Locale.ROOT);
                    if (DMZ_OWNED.contains(key) || !REGISTERED.add(key)) {
                        continue;
                    }
                    ClassPassives.register(new SduClassPassive(key));
                    added++;
                }
            }
        } catch (Throwable t) {
            // A passive that fails to register costs a class its passive; it must never stop the server starting.
            DmzNpc.LOGGER.error("[sdu] Could not register custom class passives: {}", t.toString());
            return;
        }
        if (added > 0) {
            DmzNpc.LOGGER.info("[sdu] Registered {} custom class passive(s); {} total.", added, REGISTERED.size());
        }
    }
}
