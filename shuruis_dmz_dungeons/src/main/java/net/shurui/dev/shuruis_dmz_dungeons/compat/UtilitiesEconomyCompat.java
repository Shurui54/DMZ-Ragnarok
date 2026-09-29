package net.shurui.dev.shuruis_dmz_dungeons.compat;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;

import java.lang.reflect.Method;
import java.util.UUID;

// reflection bridge to Shurui's Utilities (shuruisutilities) economy, same shape as DmzTpCompat. target is
// net.shurui.shuruisutilities.economy.EconomyManager static long earn(UUID, long), which is add() plus the earn_zeni task
// count (every payout through here is a crate, boss or spawner reward, so all of it is earned). add, never get()+set(): on a
// network the set was a blocking database write on the server thread for EVERY spawner kill (plus a read when the
// payee was not cached), which is what stalled ow1 and ow2 for minutes at a time on 2026-09-13. add() bumps the
// cached balance at once and queues one atomic increment on the credit thread, and it cannot lose a concurrent credit.
public final class UtilitiesEconomyCompat {

    private static final String ECONOMY_MANAGER = "net.shurui.shuruisutilities.economy.EconomyManager";

    private static boolean initialised;
    private static Method add; // static long earn(UUID, long)

    private UtilitiesEconomyCompat() {
    }

    // shuruisutilities is now part of this same container, so the economy is always present.
    public static boolean isLoaded() {
        return true;
    }

    // add amount to player's balance; true on success, no-op otherwise
    public static boolean addBalance(Player player, long amount) {
        if (player == null || amount <= 0 || !isLoaded()) {
            return false;
        }
        try {
            ensureInit();
            if (add == null) {
                return false;
            }
            UUID id = player.getUUID();
            add.invoke(null, id, amount);
            return true;
        } catch (Throwable t) {
            Shuruis_dmz_dungeons.LOGGER.debug("[{}] Could not add {} balance ({}); utilities economy API mismatch?",
                    Shuruis_dmz_dungeons.MODID, amount, t.toString());
            return false;
        }
    }

    private static synchronized void ensureInit() {
        if (initialised) {
            return;
        }
        initialised = true;
        try {
            Class<?> cls = Class.forName(ECONOMY_MANAGER);
            add = cls.getMethod("earn", UUID.class, long.class);
        } catch (Throwable t) {
            Shuruis_dmz_dungeons.LOGGER.info("[{}] Shurui's Utilities present but EconomyManager not found ({}); balance rewards disabled.",
                    Shuruis_dmz_dungeons.MODID, t.toString());
        }
    }
}
