package net.shurui.dev.sdu.event;

import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;
import com.dragonminez.server.events.DragonBallsHandler;
import com.dragonminez.server.world.data.DragonBallSavedData;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.DmzNpc;

/**
 * Re-scatter dragon balls in dimensions DMZ skipped at startup.
 *
 * <p>DMZ first-scatters each ball set at {@code ServerStartingEvent}, but it (a) races SU registering its
 * MultiWorld dimensions on that same event and (b) tends to resolve only the first valid dim of each set, so
 * late/MultiWorld dims ({@code shuruisutilities:smp}) never get their initial scatter. This runs at
 * {@link ServerStartedEvent}, strictly AFTER SU's dims are registered, and scatters every configured dimension
 * of every known set whose level exists and whose first-spawn has not completed.
 *
 * <p>Idempotent via {@code DragonBallSavedData.isFirstSpawnComplete(setId)}, so it only fills the dims DMZ
 * missed. Every DMZ call is guarded per set so an API mismatch cannot crash server start.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class DragonBallRescatterHandler {

    private DragonBallRescatterHandler() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            int scattered = 0;
            for (DragonBallSetDefinition set : DragonBallDefinitions.getBallSets()) {
                String setId = set.getId();
                for (ResourceLocation dimId : set.getValidDimensions()) {
                    try {
                        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
                        if (level == null) {
                            continue; // dim not registered / not loaded this session
                        }
                        DragonBallSavedData savedData = DragonBallSavedData.get(level);
                        if (savedData.isFirstSpawnComplete(setId)) {
                            continue; // DMZ (or a prior run of this hook) already scattered this dim
                        }
                        DragonBallsHandler.scatterDragonBalls(level, setId);
                        scattered++;
                        DmzNpc.LOGGER.info("[{}] Re-scattered dragon-ball set '{}' in dimension '{}'.",
                                DmzNpc.MODID, setId, dimId);
                    } catch (Throwable perDim) {
                        DmzNpc.LOGGER.warn("[{}] Could not re-scatter set '{}' in dimension '{}': {}",
                                DmzNpc.MODID, setId, dimId, perDim.toString());
                    }
                }
            }
            if (scattered > 0) {
                DmzNpc.LOGGER.info("[{}] Dragon-ball re-scatter complete: filled {} dimension(s) DMZ skipped at startup.",
                        DmzNpc.MODID, scattered);
            }
        } catch (Throwable t) {
            // DMZ API mismatch (renamed/removed symbol): skip gracefully, never crash server start.
            DmzNpc.LOGGER.warn("[{}] Dragon-ball re-scatter hook skipped (DMZ API unavailable): {}",
                    DmzNpc.MODID, t.toString());
        }
    }
}
