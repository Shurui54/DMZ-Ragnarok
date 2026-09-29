package net.shurui.dev.sdu.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.block.GravityChamberBlock;
import net.shurui.dev.sdu.block.GravityChamberBlockEntity;

/**
 * Makes SHIFT right click reach the gravity chamber's WorldEdit region capture.
 *
 * <p>A block's {@code use()} is skipped entirely when the player sneaks while holding something in either hand, and
 * capturing a selection means holding the WorldEdit wand, so the capture could never run from use(): only a player
 * with both hands empty got there, and they have no selection to capture. The sneak half therefore lives on the
 * interaction event, which fires before that decision. A plain right click still reaches use() and opens the config
 * GUI as before.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class ChamberInteractEvents {

    private ChamberInteractEvents() {
    }

    // LOWEST so protection handlers get to cancel first: a cancelled event is not delivered here
    // (receiveCanceled defaults to false).
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getEntity().isShiftKeyDown() || event.getHand() != InteractionHand.MAIN_HAND) {
            return; // a plain click already reaches use(); off-hand would capture twice
        }
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof GravityChamberBlock)) {
            return;
        }
        if (!event.getEntity().isCreative()) {
            return; // op-only feature, same gate use() applies
        }
        // cancelled on both sides: the client must not predict placing the held block against the chamber
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        BlockEntity be = event.getLevel().getBlockEntity(event.getPos());
        if (be instanceof GravityChamberBlockEntity chamber) {
            GravityChamberBlock.captureRegion(sp, chamber);
        }
    }
}
