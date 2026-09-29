package net.shurui.shuruisutilities.api.key;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.MutableComponent;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.api.UserIdent;

/**
 * Core-side hook for the PRIVATE moderation commands (S18a: AFK, freeze, temp bans, warns, history, invsee, doas,
 * seen, command lookup; logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core reads the AFK state here (the
 * {@code MixinEntity} push guard and the chat module's AFK notice).
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: nobody is ever AFK and no AFK notice is sent. That matches
 * a keyless server before the move: {@code /afk} was never registered there, and the auto-AFK timeout is a permission
 * property that only {@code /afk}'s registration declares, so it never fired keyless.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class ModerationHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "moderation";

    private ModerationHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the moderation commands are live (the key installed them). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** Whether this player is AFK. Keyless: never. */
        default boolean isAfk(UserIdent ident)
        {
            return false;
        }

        /** Tell the sender when a message goes to (or names) an AFK player. Keyless: nothing to tell. */
        default void checkAfkMessage(CommandSourceStack target, MutableComponent message) throws CommandSyntaxException
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

    /** Whether the moderation commands are live on this server. */
    public static boolean available()
    {
        return impl.available();
    }

    public static boolean isAfk(UserIdent ident)
    {
        return impl.isAfk(ident);
    }
}
