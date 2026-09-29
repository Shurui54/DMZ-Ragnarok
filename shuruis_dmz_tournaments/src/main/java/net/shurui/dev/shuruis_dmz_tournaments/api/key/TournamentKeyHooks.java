package net.shurui.dev.shuruis_dmz_tournaments.api.key;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Module-side hook for the PRIVATE stat gems of the Tournaments module, whose apply logic lives in the Ragnarok Key
 * ({@code dmz_ragnarok_key}). The module keeps the gem items, the picker screen, the {@code StatGemChoicePacket} codec
 * and id, and every tournament format (all public).
 *
 * <p>It lives in the module's own {@code api.key} package because a module never references the key (or another
 * module). The key installs the implementation only after {@code ModulePresence.tournaments()} says this module is
 * present, so a core-only server plus the key never classloads a tournament type.
 *
 * <p>The {@link Impl} DEFAULT is today's keyless behaviour: a picked stat shows the "needs the key" line and the gem
 * is kept (never consumed).
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class TournamentKeyHooks {

    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "statgems";

    private TournamentKeyHooks() {
    }

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl {

        /**
         * The player picked {@code statKey} (already checked to be one of DMZ's six core stats) for the gem held in
         * {@code hand}: apply the gem's amount and consume it. Keyless: the "needs the key" line, nothing applied, the
         * gem kept.
         */
        default void useStatGem(ServerPlayer player, InteractionHand hand, String statKey) {
            if (player == null) {
                return;
            }
            player.displayClientMessage(Component.translatable("item.dmz_ragnarok.stat_gem.no_key")
                    .withStyle(ChatFormatting.RED), true);
        }
    }

    /** The keyless default until the key installs its own. Never null. */
    private static volatile Impl impl = new Impl() {
    };

    /** Set once the key installs its implementation (a jar that only marks the feature id never sets it). */
    private static volatile boolean installed;

    /** Install the key's implementation and mark the feature. Called once by the key's stat gem feature. */
    public static void install(Impl i) {
        if (i == null) {
            return;
        }
        impl = i;
        installed = true;
        KeyFeatures.mark(FEATURE_ID);
    }

    /** The live implementation (never null: the keyless default until the key installs). */
    public static Impl get() {
        return impl;
    }

    /** Whether the Ragnarok Key installed this hook. Keyless, or a jar installing nothing: false. */
    public static boolean available() {
        return installed;
    }
}
