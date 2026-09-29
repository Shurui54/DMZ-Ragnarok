package net.shurui.shuruisutilities.api.key;

import java.util.Collection;
import java.util.List;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.api.permissions.IPermissionsHelper;
import net.shurui.shuruisutilities.api.permissions.Zone;
import net.shurui.shuruisutilities.permissions.KeylessPermissionHelper;
import net.shurui.shuruisutilities.permissions.gui.PacketPermAction;

/**
 * Core-side hook for the PRIVATE permissions module (engine, zones, groups, user overrides, the permission files, the
 * editor and its commands all live in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps the
 * {@code APIRegistry.perms} interface, the zone model types it exposes, the editor packets (14 and 15), the menu and
 * the client screens.
 *
 * <p>Keyless, {@code APIRegistry.perms} is a {@link KeylessPermissionHelper}: every node answers from the level it
 * was REGISTERED with (ALL allowed, OP to an operator, NONE to nobody), with no zones, groups, user overrides or file
 * I/O. {@link #install} swaps in the key's engine and replays every registration the keyless helper already took,
 * so nothing registered before the swap is lost whatever the construction order.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false and every editor request is
 * ignored. Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class PermissionHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "permissions";

    private PermissionHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the permission engine is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** A {@code PacketPermAction} arrived from the editor (server thread). Keyless: ignored. */
        default void onPermAction(ServerPlayer player, PacketPermAction action)
        {
        }

        /** Every registered node (the chest editor's node list). Keyless: empty. */
        default Collection<String> registeredNodes()
        {
            return List.of();
        }

        /** The chest editor asked for a line of chat input ({@code input} names the field). Keyless: ignored. */
        default void requestGuiInput(ServerPlayer player, String input, String group)
        {
        }

        /** Mute (true) or restore (false) the engine's /p debug echo around a bulk scan. Keyless: nothing to mute. */
        default void suppressDebug(boolean suppress)
        {
        }

        /** The group spawn parser behind /setspawn. Keyless: nothing (there is no stored spawn property). */
        default void parseGroupSpawn(CommandContext<CommandSourceStack> ctx, List<String> args, String group,
                Zone zone, boolean commandSetspawn) throws CommandSyntaxException
        {
        }
    }

    /** The keyless default until the key installs its own. Never null. */
    private static volatile Impl impl = new Impl() {};

    /**
     * Install the key's implementation and its permission engine, and mark the feature. Called once from the key.
     * The engine replaces {@code APIRegistry.perms}; every registration the keyless helper took is replayed into it
     * first, under the helper's lock, so a registration racing the swap is forwarded rather than lost.
     */
    public static void install(Impl i, IPermissionsHelper engine)
    {
        if (i == null || engine == null)
            return;
        KeylessPermissionHelper.handOver(engine);
        impl = i;
        KeyFeatures.mark(FEATURE_ID);
    }

    /** The live implementation (never null: the keyless default until the key installs). */
    public static Impl get()
    {
        return impl;
    }

    /** Whether the permission engine is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
