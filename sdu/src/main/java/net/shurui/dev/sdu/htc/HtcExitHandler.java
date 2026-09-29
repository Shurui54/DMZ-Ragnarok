package net.shurui.dev.sdu.htc;

import com.dragonminez.common.init.block.custom.TimeChamberPortalBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge-bus redirect for the EXIT direction of DragonMine Z's Hyperbolic Time Chamber portal.
 *
 * DMZ's {@link TimeChamberPortalBlock#use} teleports on right-click: if the player is NOT already in the HTC
 * dimension it enters (DMZ's default destination), and if already inside it exits (DMZ sends them to Kami's
 * Lookout / world spawn). We only intercept the EXIT case, and only when an admin has configured an override
 * via {@link HtcDestination}; when we do, we cancel DMZ's use() and teleport to the configured spot instead.
 * Entry is never touched.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class HtcExitHandler {

    // DMZ's HTC dimension. The exit direction is "player IS currently in this dimension".
    private static final ResourceKey<Level> TIME_CHAMBER =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("dragonminez", "time_chamber"));

    private HtcExitHandler() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // Server side only; ignore the off-hand duplicate call so we don't fire twice per click.
        if (event.getSide().isClient() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Player p = event.getEntity();
        if (!(p instanceof ServerPlayer player)) {
            return;
        }
        Level level = event.getLevel();
        BlockState state = level.getBlockState(event.getPos());
        // Match the DMZ time-chamber portal block. DMZ is a mandatory dep, so instanceof is safe here.
        if (!(state.getBlock() instanceof TimeChamberPortalBlock)) {
            return;
        }
        // Exit direction only: the player must already be inside the HTC dimension. If they are not, this is
        // the entry direction and we leave DMZ's default entry behaviour alone.
        if (!player.level().dimension().equals(TIME_CHAMBER)) {
            return;
        }
        // Only override when an admin has configured a destination; otherwise let DMZ handle exit normally.
        if (!HtcDestination.isEnabled()) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ResourceKey<Level> destKey = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(HtcDestination.dimension()));
        ServerLevel dest = server.getLevel(destKey);
        if (dest == null) {
            // Configured dimension doesn't resolve; do nothing and let DMZ handle exit normally.
            return;
        }
        // Cancel DMZ's use() so it doesn't ALSO teleport. Set the interaction result so vanilla treats the
        // click as fully handled and doesn't run block use.
        event.setUseBlock(Event.Result.DENY);
        event.setUseItem(Event.Result.DENY);
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        player.teleportTo(dest, HtcDestination.x(), HtcDestination.y(), HtcDestination.z(),
                HtcDestination.yaw(), HtcDestination.pitch());
    }
}
