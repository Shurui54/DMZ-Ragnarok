package net.shurui.shuruisutilities.chat;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.network.chat.Component;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.minecraft.server.level.ServerPlayer;

/**
 * Per-recipient chat delivery extension point.
 *
 * <p>{@code ModuleChat} is the single owner of chat formatting (nickname header, rank badge) and of
 * delivery (local chat range + the RECEIVE_CHAT region flag). Nothing here changes any of that.
 *
 * <p>Two callers offer recipients here, not one: {@code ModuleChat} for a line said on this server, and
 * {@link net.shurui.shuruisutilities.shard.ShardChat} for one that arrived from another server on the shard
 * network. The second exists because a remote line used to be broadcast straight out, so a handler saw only
 * local chat and the translator translated only the shard the reader happened to be standing on. A remote
 * offer has a NULL sender and a rebuild callback built from the decoration the ORIGIN shipped. This
 * holder just lets an OPTIONAL sibling mod (currently Shurui's Translator) take over delivery for some
 * recipients AFTER the range/RECEIVE_CHAT filtering has already run, so those rules can never be bypassed.
 *
 * <p>The handler is handed only Minecraft/JDK types: the sender, one recipient, the RAW body the player
 * typed, and a rebuild callback. The callback takes a replacement body {@link Component} and returns the
 * fully decorated line (header + badge) exactly as SU would build it, so the handler never needs to touch
 * {@code RankManager}, {@code ChatConfig} or the header logic. The handler returns whether it has taken
 * responsibility for that recipient; SU delivers normally to every recipient it did NOT take.
 *
 * <p>No sibling mod is required: when nothing is registered ({@link #handler} is null) SU delivers to
 * everyone itself, byte for byte as before.
 */
public final class ChatDelivery
{
    /** Implemented by the optional consumer. See {@link ChatDelivery} for the contract. */
    public interface Handler
    {
        /**
         * Offer one recipient to the handler.
         *
         * @param sender       the player who sent the message, or NULL when the line came from another server on
         *                      the shard network. A handler must not assume a sender is present: everything it
         *                      needs about the message is in the other parameters.
         * @param recipient    the player this line is about to be delivered to
         * @param rawBody      the raw message body the sender typed (no header, no badge)
         * @param originalLine the fully decorated line SU would otherwise deliver (header + badge + the
         *                     censored/colored/link-filtered body). The handler MUST deliver this to the
         *                     recipient on any failure path, so chat is never dropped or downgraded.
         * @param rebuild      builds the fully decorated line from a replacement body Component (header + badge)
         * @return true if the handler has taken responsibility for delivering to this recipient; false to
         *         let SU deliver the original decorated line normally.
         */
        boolean deliver(ServerPlayer sender, ServerPlayer recipient, String rawBody,
                Component originalLine, Function<Component, Component> rebuild);

        /**
         * Offer EVERY recipient of one message at once. This is the call SU makes; {@link #deliver} is only the
         * per-recipient fallback the default implementation below is written in terms of.
         *
         * <p>Batching exists for cost, not tidiness. A handler that works per recipient has no way to know that
         * twenty recipients are twenty copies of ONE message, so it does twenty lots of whatever a message costs
         * it. For the translator that was twenty HTTP round trips where three (one per distinct language) would
         * do, all racing an empty cache so none of them could reuse another's answer, queued two at a time behind
         * a bounded pool. Chat arrived seconds late and further behind on every message. Given the whole list, a
         * handler groups it once and pays per language instead of per player.
         *
         * <p>The list has already been through range, RECEIVE_CHAT and vanish filtering, exactly as a single
         * recipient had. Take a SUBSET freely: everyone not in the returned set is delivered the original line by
         * SU, so returning an empty set is the same as taking nobody.
         *
         * @return the recipients this handler has taken responsibility for. Never null.
         */
        default Set<ServerPlayer> deliverAll(ServerPlayer sender, List<ServerPlayer> recipients, String rawBody,
                Component originalLine, Function<Component, Component> rebuild)
        {
            // The old one-at-a-time behaviour, so a handler written before this method existed keeps working
            // unchanged (it is just as slow as it was). ServerPlayer does not override equals, so this is an
            // identity set, which is what "this exact player object" means here.
            Set<ServerPlayer> taken = new HashSet<>();
            for (ServerPlayer p : recipients)
            {
                if (deliver(sender, p, rawBody, originalLine, rebuild))
                    taken.add(p);
            }
            return taken;
        }
    }

    private static volatile Handler handler;

    private ChatDelivery()
    {
    }

    /**
     * Register (or replace) the delivery handler. Pass null to clear it.
     *
     * <p>Logged, because whether a sibling mod took over chat delivery is otherwise completely invisible. Chat that
     * has lost its rank badge and its format looks identical whether the consumer registered and is rebuilding lines
     * badly, or never registered and fell back to cancelling chat itself. One line here says which, and it costs one
     * log at startup.
     */
    public static void setHandler(Handler h)
    {
        handler = h;
        try
        {
            if (h == null)
                LoggingHandler.sulog.info("[Chat] Delivery handler cleared; SU delivers chat itself.");
            else
                LoggingHandler.sulog.info("[Chat] Delivery handler registered by {}; that mod now owns per-recipient "
                        + "delivery and SU keeps owning the formatting.", h.getClass().getName());
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * Offer a recipient to the registered handler, if any. Returns true only when a handler is present AND
     * it took responsibility for this recipient; in every other case SU must deliver normally. Any exception
     * thrown by the handler is swallowed and treated as "not taken", so a broken consumer never drops chat.
     */
    /**
     * Offer every recipient of one message to the registered handler, if any. Returns the set the handler took;
     * SU must deliver the original line to everyone else. An absent handler, or one that throws, takes nobody, so
     * chat is delivered in full either way.
     *
     * <p>This is what {@code ModuleChat} and
     * {@link net.shurui.shuruisutilities.shard.ShardChat} call. See {@link Handler#deliverAll} for why a message
     * is offered once rather than once per player.
     *
     * @param sender may be null: see {@link Handler#deliver}.
     */
    public static Set<ServerPlayer> offerAll(ServerPlayer sender, List<ServerPlayer> recipients, String rawBody,
            Component originalLine, Function<Component, Component> rebuild)
    {
        Handler h = handler;
        if (h == null || recipients == null || recipients.isEmpty())
            return Collections.emptySet();
        try
        {
            Set<ServerPlayer> taken = h.deliverAll(sender, recipients, rawBody, originalLine, rebuild);
            return taken == null ? Collections.emptySet() : taken;
        }
        catch (Throwable t)
        {
            // Never let a delivery extension break core chat: SU delivers to everyone.
            return Collections.emptySet();
        }
    }

    /** @param sender may be null: see {@link Handler#deliver}. */
    public static boolean offer(ServerPlayer sender, ServerPlayer recipient, String rawBody,
            Component originalLine, Function<Component, Component> rebuild)
    {
        Handler h = handler;
        if (h == null)
            return false;
        try
        {
            return h.deliver(sender, recipient, rawBody, originalLine, rebuild);
        }
        catch (Throwable t)
        {
            // Never let a delivery extension break core chat: fall back to SU delivery for this recipient.
            return false;
        }
    }
}
