package net.shurui.dev.shuruis_dmz_dungeons.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * The loot rolls currently spinning above crates, client side. Pure decoration over an outcome the server already
 * decided and paid out, so it is allowed to be lossy: a missed or interrupted roll costs nothing. That is why none
 * of it is synced back and it lives in a plain map, not on the block entity.
 */
public final class ClientCrateRolls {

    private ClientCrateRolls() {
    }

    /** How long the winning item hangs there after the cycling stops, in ticks. ~3 seconds so a player can read what
     * they got before the reveal ends, even if they open the crate and immediately look away. */
    private static final int LINGER_TICKS = 60;

    public static final class Roll {
        public final List<ItemStack> candidates;
        public final ItemStack result;
        public final int durationTicks;
        public final long startTick;

        Roll(List<ItemStack> candidates, ItemStack result, int durationTicks, long startTick) {
            this.candidates = candidates;
            this.result = result;
            this.durationTicks = Math.max(1, durationTicks);
            this.startTick = startTick;
        }

        /** 0 while cycling, 1 the moment it settles. */
        public float progress(float partialTick) {
            long now = now();
            float elapsed = (now - startTick) + partialTick;
            return Math.min(1.0f, Math.max(0.0f, elapsed / durationTicks));
        }

        public boolean expired() {
            return now() - startTick > durationTicks + LINGER_TICKS;
        }

        /**
         * What to draw right now. Eased out so it blurs through the pool and slows into its answer instead of
         * stopping dead. Once settled it is the real reward.
         */
        public ItemStack current(float partialTick) {
            float t = progress(partialTick);
            if (t >= 1.0f || candidates.isEmpty()) {
                return result;
            }
            float eased = 1.0f - (float) Math.pow(1.0f - t, 3);
            int steps = candidates.size() * 3; // three passes through the pool before it lands
            int index = (int) (eased * steps);
            return candidates.get(index % candidates.size());
        }
    }

    private static final Map<BlockPos, Roll> ROLLS = new HashMap<>();

    private static long now() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0L : mc.level.getGameTime();
    }

    public static void start(BlockPos pos, List<ItemStack> candidates, ItemStack result, int durationTicks) {
        ROLLS.put(pos.immutable(), new Roll(candidates, result, durationTicks, now()));
    }

    /** The roll at a position, or null. Expired rolls are dropped as they are asked for, so nothing accumulates. */
    public static Roll at(BlockPos pos) {
        Roll roll = ROLLS.get(pos);
        if (roll == null) {
            return null;
        }
        if (roll.expired()) {
            ROLLS.remove(pos);
            return null;
        }
        return roll;
    }

    public static void clear() {
        ROLLS.clear();
    }
}
