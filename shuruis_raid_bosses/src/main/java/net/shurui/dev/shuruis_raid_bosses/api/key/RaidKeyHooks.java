package net.shurui.dev.shuruis_raid_bosses.api.key;

import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.dev.shuruis_raid_bosses.entity.DimensionalTear;

/**
 * Module-side hooks for the two PRIVATE parts of the Raids module, whose logic lives in the Ragnarok Key
 * ({@code dmz_ragnarok_key}): rifts (the tear scheduler, the tear claim, the per-player rift run and its arena build,
 * the tear waypoints and the {@code /rg rift} admin subtree) and Z-Souls (the beyond-cap overlay, the Curios slot
 * resize, the TP investment and the stat gem overflow grant). The module keeps every registry, packet, screen, the
 * config, the rift and Z-Soul SavedData stores ({@code RiftDefs}, {@code RiftArenaData}, {@code ZSoulData}), the
 * {@code raids:rifts} and {@code zsoul:tuning} sync entries, and every public raid type.
 *
 * <p>It lives in the module's own {@code api.key} package because a module never references the key (or another
 * module). The key installs the implementation only after {@code ModulePresence.raids()} says this module is present,
 * so a core-only server plus the key never classloads a raid type.
 *
 * <p>The {@link Impl} DEFAULTS are today's keyless behaviour: no tear is ever scheduled or opened, so there is no run
 * to tick, no waypoint to list and no schedule to forget; a tear somebody walks into (only possible through a
 * {@code /summon}, since tears are never saved) answers "closed" and vanishes, exactly as a keyless server's empty
 * tear list did; Z-Souls are off, so the overlay, the slot resize and the investment do nothing and an overflow grant
 * lands 0 points. The public {@code /rg raid zsoul} answers and the banked-points export/import for character slots
 * stay in the module.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class RaidKeyHooks {

    /** The {@link KeyFeatures} id marked for rifts. */
    public static final String FEATURE_ID = "rifts";

    /** The {@link KeyFeatures} id marked for Z-Souls. */
    public static final String ZSOUL_FEATURE_ID = "zsouls";

    private RaidKeyHooks() {
    }

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl {

        // --- rifts ---

        /** Server started: the rift manager comes up. Keyless: nothing. */
        default void serverStarted(MinecraftServer server) {
        }

        /** Server stopping: every run fails and every cell is released. Keyless: nothing (there are none). */
        default void serverStopping() {
        }

        /** One server tick, after the raids tick. Keyless: nothing (no tear, no run). */
        default void tickRifts(MinecraftServer server) {
        }

        /** Register the open-tear waypoint provider, once per JVM. Keyless: nothing (there would be no tear to list). */
        default void registerRiftWaypoints() {
        }

        /** Whether the rift manager is up, so a tear may be walked into or clicked. Keyless: true, as before. */
        default boolean tearsLive() {
            return true;
        }

        /**
         * A player walked into or clicked a tear. Keyless: no tear is open on a keyless server, so the tear answers
         * "closed" and removes itself, exactly as the empty tear list did.
         */
        default void enterTear(ServerPlayer player, DimensionalTear tear) {
            if (player == null || tear == null) {
                return;
            }
            player.sendSystemMessage(Component.translatable("rift.dmz_ragnarok.tear.closed"));
            tear.discard();
        }

        /** A rift was saved from the editor: re-roll its wait. Keyless: nothing (no schedule). */
        default void riftEdited(String riftId) {
        }

        /** A rift was deleted from the editor: forget its wait, close its tears. Keyless: nothing. */
        default void riftDeleted(String riftId) {
        }

        // --- Z-Souls ---

        /** Whether Z-Souls run here (config toggle and key). Keyless: false. */
        default boolean zsoulEnabled() {
            return false;
        }

        /** One server tick: re-project the overlay once a second. Keyless: nothing. */
        default void tickZSouls(MinecraftServer server) {
        }

        /** Re-project one player's overlay now (equip, unequip, reset, import). Keyless: nothing. */
        default void refreshZSoul(ServerPlayer player) {
        }

        /** Forget the last overlay pushed for a player (logout, banked import). Keyless: nothing is cached. */
        default void forgetZSoulOverlay(UUID playerId) {
        }

        /** Resize the player's Z-Soul Curios slot to the configured count (login, respawn). Keyless: nothing. */
        default void applyZSoulSlots(ServerPlayer player) {
        }

        /** The stats GUI "add" button: buy beyond-cap points with TP. Keyless: ignored. */
        default void investZSoul(ServerPlayer player, int statOrdinal, int multiplier) {
        }

        /** Grant beyond-cap points to the worn Z-Soul for a DMZ stat key; how many landed. Keyless: 0. */
        default int grantBeyondCap(ServerPlayer player, String dmzStatKey, int amount) {
            return 0;
        }
    }

    /** The keyless default until the key installs its own. Never null. */
    private static volatile Impl impl = new Impl() {
    };

    /** Set once the key installs its implementation. */
    private static volatile boolean installed;

    /** Install the key's implementation and mark both features. Called once by the key's raids feature. */
    public static void install(Impl i) {
        if (i == null) {
            return;
        }
        impl = i;
        installed = true;
        KeyFeatures.mark(FEATURE_ID);
        KeyFeatures.mark(ZSOUL_FEATURE_ID);
    }

    /** The live implementation (never null: the keyless default until the key installs). */
    public static Impl get() {
        return impl;
    }

    /** Whether the Ragnarok Key installed the rift and Z-Soul logic. Keyless, or a jar installing nothing: false. */
    public static boolean available() {
        return installed;
    }
}
