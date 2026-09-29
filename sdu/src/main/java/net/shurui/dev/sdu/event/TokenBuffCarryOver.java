package net.shurui.dev.sdu.event;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.buff.TokenBuffStore;
import net.shurui.dev.sdu.buff.TokenStatDiscount;

/**
 * The CORE half of the token buffs: data hygiene that must run whether or not the Ragnarok Key is installed. The
 * gem use, the TP-gain multiplier, the login/respawn resync and the pip sweep live in the key (feature
 * {@code tokenbuffs}, see {@link net.shurui.dev.sdu.api.key.TokenBuffHooks}).
 *
 * <p>Never put this on a teardown list: a keyless server must still carry stored tokens across a death, or a player
 * who dies there would arrive on a keyed shard with their tokens gone.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class TokenBuffCarryOver {

    private TokenBuffCarryOver() {
    }

    /**
     * Carry running tokens onto the respawned player.
     *
     * <p>They live at the root of the player's persistent data, and Forge's {@code ServerPlayer.restoreFrom} copies
     * only the {@code PlayerPersisted} sub-tag to the new entity. Without this a death silently deleted every
     * running token on the server while the client kept its copy, so the stat screen went on showing a discount
     * the server no longer had and refused the purchase at full price.
     */
    @SubscribeEvent
    public static void onClone(net.minecraftforge.event.entity.player.PlayerEvent.Clone event) {
        try {
            net.minecraft.nbt.CompoundTag from = event.getOriginal().getPersistentData();
            net.minecraft.nbt.CompoundTag to = event.getEntity().getPersistentData();
            for (TokenBuffStore.Category cat : TokenBuffStore.Category.values()) {
                if (from.contains(cat.nbtKey) && !to.contains(cat.nbtKey)) {
                    to.put(cat.nbtKey, from.get(cat.nbtKey).copy());
                }
            }
        } catch (Throwable t) {
            // a failed copy loses only the tokens, exactly as before this handler existed
        }
    }

    /**
     * Safety net for the stat-discount ThreadLocal. IncreaseStatC2SMixin sets CURRENT_BUYER at the HEAD of
     * DMZ's stat-purchase work and clears it at RETURN. If that clear ever fails to bind, a stale buyer would
     * linger on the server thread and discount unrelated getSingleStatCost calls (getMaxSingleStatCost,
     * calculateRecursiveCost, ...). Clearing unconditionally at every server tick END bounds any leak to one
     * tick: the per-purchase HEAD setter re-publishes the correct buyer before the cost is read. Server-side only.
     */
    @SubscribeEvent
    public static void onServerTickEnd(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            TokenStatDiscount.CURRENT_BUYER.remove();
        }
    }
}
