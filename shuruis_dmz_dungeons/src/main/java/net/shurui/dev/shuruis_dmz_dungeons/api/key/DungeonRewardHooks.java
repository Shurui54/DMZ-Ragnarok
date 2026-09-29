package net.shurui.dev.shuruis_dmz_dungeons.api.key;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.dev.shuruis_dmz_dungeons.item.TpGemItem;

/**
 * Module-side hooks for the PRIVATE dungeon rewards (S22b): the per-player dungeon crates, floor ticket redemption,
 * TP gems and the zeni half of an advanced-spawner kill. Their logic lives in the Ragnarok Key; the Dungeons module
 * keeps the crate blocks and block entities, the ticket and gem items (registered here, only their use bodies route
 * through this hook), the crate and ticket SavedData, the pending reward store and every {@code dungeons:*} sync entry.
 *
 * <p>It lives in the module's own {@code api.key} package because a module never references the key. The key installs
 * the implementation only after {@code ModulePresence.dungeons()} says this module is present.
 *
 * <p>The {@link Impl} DEFAULTS are today's keyless behaviour: a crate block opens nothing, a floor ticket (either
 * item) and a TP gem answer with their "needs the key" message and are not consumed, and a spawner kill pays no zeni.
 * The public halves stay in the module: spawner TP, custom drops and kill commands, the creative crate tier cycle, the
 * ticket tooltip and the portal's ticket and boss-clear checks.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class DungeonRewardHooks {

    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "dungeonrewards";

    private static final String M = "message.dmz_ragnarok.dungeons.";

    private DungeonRewardHooks() {
    }

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl {

        /** Whether the dungeon rewards are live (the key installed them). Keyless: false. */
        default boolean available() {
            return false;
        }

        /**
         * A player right-clicked a crate block (server side). Returns whether it opened their instanced roll. Keyless:
         * nothing opens.
         */
        default boolean openCrate(ServerPlayer player, ServerLevel level, BlockPos pos, int tierOrdinal) {
            return false;
        }

        /** The dungeon floor_ticket item was used (server side). Keyless: the "needs the key" line, not consumed. */
        default InteractionResultHolder<ItemStack> useFloorTicket(ServerPlayer player, ItemStack stack) {
            player.displayClientMessage(Component.translatable(M + "ticket_no_key").withStyle(ChatFormatting.RED), true);
            return InteractionResultHolder.fail(stack);
        }

        /**
         * A floor-tagged ss_ticket was right-clicked (server side; the click is already consumed). Keyless: the
         * "needs the key" line, nothing redeemed.
         */
        default void redeemSuTicket(ServerPlayer player, ItemStack stack, int floor) {
            player.displayClientMessage(Component.translatable(M + "ticket_no_key").withStyle(ChatFormatting.RED), true);
        }

        /** A TP gem was used (server side). Keyless: the "needs the key" line, not consumed. */
        default InteractionResultHolder<ItemStack> useTpGem(Player player, ItemStack stack, TpGemItem gem) {
            player.displayClientMessage(Component.translatable(M + "tp_gem_no_key").withStyle(ChatFormatting.RED), true);
            return InteractionResultHolder.fail(stack);
        }

        /** The zeni half of an advanced-spawner kill, rolled from the stamped balance range. Keyless: no zeni. */
        default void spawnerZeni(Player killer, CompoundTag spawnerData, RandomSource random) {
        }
    }

    /** The keyless default until the key installs its own. Never null. */
    private static volatile Impl impl = new Impl() {
    };

    /** Install the key's implementation and mark the feature. Called once by the key's dungeon rewards feature. */
    public static void install(Impl i) {
        if (i == null) {
            return;
        }
        impl = i;
        KeyFeatures.mark(FEATURE_ID);
    }

    /** The live implementation (never null: the keyless default until the key installs). */
    public static Impl get() {
        return impl;
    }

    /** Whether the dungeon rewards are live on this server. */
    public static boolean available() {
        return impl.available();
    }
}
