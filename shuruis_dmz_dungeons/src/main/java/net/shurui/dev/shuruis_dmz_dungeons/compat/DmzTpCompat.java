package net.shurui.dev.shuruis_dmz_dungeons.compat;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;

// reflection bridge to DMZ Training Points. everything reflective + try/catch so it no-ops if DMZ is absent
// or its API shifts. path (cribbed from sdu): StatsCapability.INSTANCE -> StatsData ->
// getResources().addTrainingPoints(float).
public final class DmzTpCompat {

    private static final String STATS_CAPABILITY = "com.dragonminez.common.stats.StatsCapability";

    private static boolean initialised;
    private static Object capabilityToken; // Capability<StatsData>

    private DmzTpCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("dragonminez");
    }

    // grant amount TP; true on success, no-op otherwise
    public static boolean awardTp(Player player, float amount) {
        if (player == null || amount <= 0 || !isLoaded()) {
            return false;
        }
        try {
            ensureInit();
            if (capabilityToken == null) {
                return false;
            }
            @SuppressWarnings("unchecked")
            LazyOptional<?> lazy = player.getCapability((Capability<Object>) capabilityToken);
            Object stats = lazy.resolve().orElse(null);
            if (stats == null) {
                return false;
            }
            Object resources = stats.getClass().getMethod("getResources").invoke(stats);
            resources.getClass().getMethod("addTrainingPoints", float.class).invoke(resources, amount);
            return true;
        } catch (Throwable t) {
            Shuruis_dmz_dungeons.LOGGER.debug("[{}] Could not award {} TP ({}); DMZ TP API mismatch?",
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
            capabilityToken = Class.forName(STATS_CAPABILITY).getField("INSTANCE").get(null);
        } catch (Throwable t) {
            Shuruis_dmz_dungeons.LOGGER.info("[{}] DMZ present but StatsCapability not found ({}); TP rewards disabled.",
                    Shuruis_dmz_dungeons.MODID, t.toString());
        }
    }
}
