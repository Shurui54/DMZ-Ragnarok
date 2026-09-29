package net.shurui.shuruisutilities.api.key;

import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticSlot;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticTransferGate;

/**
 * Core-side hook for the PRIVATE cosmetics (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps the
 * catalogue ({@code CosmeticCatalog}), the stores ({@code CosmeticLedgerData}, {@code CosmeticWardrobeData}), the
 * DTOs, items, entities, packets and every renderer, plus the PUBLIC Patreon wardrobe (S17p/S17q: the "wardrobe" hub
 * row, {@code CosmeticEditorServer.openWardrobe} and {@code WardrobeManager}'s Patreon-only mode). The "Cosmetics"
 * module, the shop, the cosmetic crates, the admin catalogue editor, the mounts and animations screens, token redeem
 * and extract, the trigger animations and the token drop guard live in the key.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false (so
 * {@code WardrobeManager.active()} is false and the wardrobe runs Patreon-only), no animation is ever played or
 * queued, and a token click only says why nothing happened, exactly the message a keyless server gave before the
 * move. Nothing is read from or written to the stores by a default.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class CosmeticHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "cosmetics";

    private CosmeticHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the private cosmetics are live (the key installed them). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /**
         * A player used a cosmetic token item (server thread). Keyless: the token is left alone and the player is
         * told it is invalid (no cosmetic or instance on it) or that cosmetics are disabled here.
         */
        default void redeemToken(ServerPlayer player, ItemStack stack, InteractionHand hand)
        {
            if (player == null || stack == null || stack.isEmpty())
                return;
            String id = CosmeticTransferGate.cosmeticIdOf(stack);
            UUID instance = CosmeticTransferGate.instanceIdOf(stack);
            String key = id == null || instance == null ? "message.dmz_ragnarok.cosmetics.token.invalid"
                    : "message.dmz_ragnarok.cosmetics.token.disabled";
            player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), false);
        }

        /** Whether trigger animations may play here. Keyless: false. */
        default boolean animationsActive()
        {
            return false;
        }

        /** The server finished starting (animation debounce and restart grace). Keyless: ignored. */
        default void onServerStarted()
        {
        }

        /** The server is stopping (drop queued animations). Keyless: ignored. */
        default void onServerStopping()
        {
        }

        /** End of a server tick (play queued animations that are due). Keyless: ignored. */
        default void onServerTick(MinecraftServer server)
        {
        }

        /** Queue a trigger animation for this player a few ticks from now. Keyless: ignored. */
        default void playSoon(ServerPlayer player, CosmeticSlot trigger, String carriedId, int delayTicks)
        {
        }

        /** Play the animation the subject has equipped for this trigger, at this spot. Keyless: ignored. */
        default void play(CosmeticSlot trigger, UUID subjectId, ServerLevel level, Vec3 pos, float yaw)
        {
        }

        /** Play an animation to this player only (the screen and command preview). Keyless: ignored. */
        default void preview(ServerPlayer player, String catalogId, CosmeticSlot trigger)
        {
        }

        /**
         * A client said what cosmetic art pack it holds ({@code PacketCosmeticAssets} HAVE, on join and after every
         * chunk). The key answers by streaming whatever the client is missing. Keyless: ignored, so a keyless server
         * sends no private art at all.
         */
        default void assetReport(ServerPlayer player, int version, int haveChunks)
        {
        }
    }

    /** The keyless default until the key installs its own. Never null. */
    private static volatile Impl impl = new Impl() {};

    /** Install the key's implementation and mark the feature. Called once from {@code RagnarokKeyMod}. */
    public static void install(Impl i)
    {
        if (i == null)
            return;
        impl = i;
        KeyFeatures.mark(FEATURE_ID);
    }

    /** The live implementation (never null: the keyless default until the key installs). */
    public static Impl get()
    {
        return impl;
    }

    /** Whether the private cosmetics are live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
