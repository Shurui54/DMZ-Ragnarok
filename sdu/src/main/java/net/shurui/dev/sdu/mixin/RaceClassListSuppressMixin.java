package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.config.RaceStatsConfig;
import net.shurui.dev.sdu.race.SuppressedDefaultsClient;
import net.shurui.dev.sdu.race.SuppressedDefaultsConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

// Keeps a suppressed default class (tank) out of every enumeration of a race's classes, on BOTH sides, no matter how
// it got back into the map. This is the durable half of the fix: SuppressedClassesSyncMixin only strips the class from
// the JSON DMZ streams once at login, but DMZ's RaceStatsConfig.getClassStats is a MUTATING getter that re-creates and
// re-inserts any class it is asked for (RaceStatsConfig.java, size < 64 branch). StatsData and ClassPassives call it
// every stat recompute with the player's own stored class, so a single player still on 'tank' resurrects the entry in
// RACE_STATS (server) and SERVER_SYNCED_STATS (client) moments after the strip, and getAllClasses() then feeds it back
// to DMZ's character/class picker and to /dmzclass (suggestions + validation). Filtering the RETURN of getAllClasses()
// closes every one of those selection paths regardless of what the underlying map holds.
//
// getAllClasses() normally returns the live keySet(); every DMZ caller only reads or copies it (the picker wraps it in
// a new ArrayList, /dmzclass unions it into a TreeSet), so returning a filtered copy is safe. Not a hot path: only the
// class picker and the class command consult it, never per-tick stat math (that uses getClassStats directly).
//
// Both suppressed-id sources are consulted so this works wherever it runs: SuppressedDefaultsConfig is the server's
// on-disk list (empty on a plain client, whose config folder has no suppressed_defaults.json), SuppressedDefaultsClient
// is the mirror the server pushes to each client on login. Their union covers server and client alike.
@Mixin(value = RaceStatsConfig.class, remap = false)
public abstract class RaceClassListSuppressMixin {

    @Inject(method = "getAllClasses", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void sdu$filterSuppressedClasses(CallbackInfoReturnable<Collection<String>> cir) {
        Collection<String> classes = cir.getReturnValue();
        if (classes == null || classes.isEmpty()) {
            return;
        }
        Set<String> suppressed = new LinkedHashSet<>(SuppressedDefaultsConfig.classes());
        suppressed.addAll(SuppressedDefaultsClient.classes());
        if (suppressed.isEmpty()) {
            return;
        }
        LinkedHashSet<String> filtered = null;
        for (String id : classes) {
            if (id != null && suppressed.contains(id.toLowerCase(Locale.ROOT))) {
                if (filtered == null) {
                    filtered = new LinkedHashSet<>();
                    for (String kept : classes) {
                        if (kept != null && !suppressed.contains(kept.toLowerCase(Locale.ROOT))) {
                            filtered.add(kept);
                        }
                    }
                }
            }
        }
        if (filtered != null) {
            cir.setReturnValue(filtered);
        }
    }
}
