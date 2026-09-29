package net.shurui.shuruisutilities.commands.util;

import java.util.Set;
import java.util.UUID;

import net.shurui.shuruisutilities.util.PlayerInfo;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

import net.minecraft.server.level.ServerPlayer;

/**
 * Keeps the suite's own trusted commands from feeding vanilla's chat-spam counter.
 *
 * <p>Vanilla ({@code ServerGamePacketListenerImpl}) treats a slash command exactly like a chat line:
 * {@code handleChatCommand} runs the command and then calls {@code detectRateSpam()}, which adds 20 to
 * {@code chatSpamTickCount} and disconnects a non-op once it passes 200 (the field decays by 1/tick, so
 * 20/s). Repeatable player actions that ride a slash command (guild bank deposits, {@code /money pay}, chunk
 * claiming, raid/rg tourney sign-ups) therefore accumulate exactly like chat and can kick a player with
 * {@code disconnect.spam} even though they never typed in chat. Ops are exempt, which is why staff never hit
 * it.</p>
 *
 * <p>The fix records "this player just ran one of our allowlisted commands" from Forge's {@code CommandEvent}
 * (see {@link CommandGateHandler#commandEvent}) and consumes it in a HEAD mixin on
 * {@code detectRateSpam} that decrements the counter by exactly 20 (clamped at 0) so vanilla's following
 * {@code += 20} nets to zero for our command. It is NOT zeroed: genuine chat-flood accumulation must survive,
 * so we only decline to count our own trusted commands. Chat never sets this flag, so chat is kicked on the
 * exact old schedule.</p>
 *
 * <p>Thread-safety: both the {@code CommandEvent} (fired from {@code performChatCommand}) and the following
 * {@code detectRateSpam()} run back-to-back on the server thread inside the same {@code server.submit(...)}
 * lambda in {@code handleChatCommand}, so a single-slot flag keyed by the acting player's UUID is race-free.
 * The slot is consume-once: reading it clears it, so a later chat line from the same player is unaffected.</p>
 */
public final class CommandSpamGuard
{
    private CommandSpamGuard() {}

    // Root literals (as typed, lower-cased) of the suite's repeatable player-facing commands and their
    // aliases. Derived from the command registrations, not guessed:
    //   guild / g / guilds  -> CommandGuild.getPrimaryAlias()+getAliases(); covers "guild bank deposit",
    //                          "guild bank withdraw", and claim mashing ("guild claim"/"guild claimat").
    //   money / balance / bal / zeni, pay -> CommandMoney / CommandPay primary + aliases; covers "/money pay"
    //                          routing and the pay command itself.
    //   srb / raidboss      -> RaidCommand root literal + its redirect alias; the raid sign-up GUI runs
    //                          "srb join"/"srb open".
    //   sdt / tournament    -> TournamentCommand root literal + its redirect alias; the tournament sign-up
    //                          GUI runs "tournament open".
    // Only ROOT literals are listed; every subcommand under a listed root is covered.
    private static final Set<String> ALLOWED_ROOTS = Set.of(
            "guild", "g", "guilds",
            "money", "balance", "bal", "zeni",
            "pay",
            "srb", "raidboss",
            "sdt", "tournament");

    // Single-slot flag: the UUID of the player whose last command was allowlisted, or null. Only ever touched
    // on the server thread (see class doc), so no synchronization is needed.
    private static UUID pendingPlayer = null;

    /** True if {@code root} (a raw command root literal, lower-cased) is one of the suite's trusted roots. */
    public static boolean isAllowedRoot(String root)
    {
        return root != null && ALLOWED_ROOTS.contains(root);
    }

    /** Records that {@code playerId} just ran an allowlisted command, to be consumed by the spam mixin. */
    public static void markAllowed(UUID playerId)
    {
        pendingPlayer = playerId;
    }

    /**
     * Consume-once check for the spam mixin: returns true (and clears the flag) exactly when the pending
     * player matches {@code playerId}. Any other player, or a second call, returns false.
     */
    public static boolean consumeAllowed(UUID playerId)
    {
        if (playerId != null && playerId.equals(pendingPlayer))
        {
            pendingPlayer = null;
            return true;
        }
        return false;
    }

    /* Economy action rate limit (part 2) */

    // Named-timeout key on PlayerInfo shared by every repeatable economy action (guild bank deposit/withdraw
    // and /money pay), so the throttle is per-player and covers all three together.
    private static final String ECONOMY_TIMEOUT = "economyAction";

    // Minimum gap between economy actions. With part 1 removing the vanilla kick, this is the only backstop
    // against a modified client hammering a transaction command. 150 ms = at most ~6-7 actions/second, which
    // is below the fastest a human could physically double-click a GUI button yet caps a script from
    // thousands/second down to a handful. It responds with a friendly message instead of disconnecting.
    private static final long ECONOMY_MIN_INTERVAL_MS = 150L;

    /**
     * Returns true if {@code player} may perform an economy action now, and starts the cooldown. When
     * throttled it returns false and sends the player a short warning (translatable, per-player).
     */
    public static boolean allowEconomyAction(ServerPlayer player)
    {
        PlayerInfo pi = PlayerInfo.get(player);
        if (pi == null)
            return true;
        if (!pi.checkTimeout(ECONOMY_TIMEOUT))
        {
            ChatOutputHandler.chatWarning(player, "You are doing that too fast. Wait a moment and try again.");
            return false;
        }
        pi.startTimeout(ECONOMY_TIMEOUT, ECONOMY_MIN_INTERVAL_MS);
        return true;
    }
}
