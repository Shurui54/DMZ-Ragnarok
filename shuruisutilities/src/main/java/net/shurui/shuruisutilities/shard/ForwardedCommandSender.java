package net.shurui.shuruisutilities.shard;

import java.util.ArrayList;
import java.util.List;

import com.mojang.authlib.GameProfile;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.util.FakePlayer;

import net.shurui.shuruisutilities.api.UserIdent;

/**
 * The command source a forwarded command runs under on the server that holds the target. Exists to run with LESS
 * authority, not more: it carries the original sender's identity so THEIR permissions are checked, not the command
 * running unattributed as the server. Where a forwarded command comes from and why it fails closed is in
 * {@code ShardCommandForward}.
 *
 * <p>A forwarded command runs WITH THE ORIGINAL SENDER'S AUTHORITY, re-checked against this server's own (network
 * synced) permission data, and NEVER as console. Console would let anybody who can run any command escalate:
 * {@code /smite}'s {@code .smite.others} sub check falls through to the "console is trusted" branch and fires
 * unconditionally. So the acting identity must resolve to the sender's real {@link UserIdent}.
 *
 * <p>A plain {@link FakePlayer} does not do this in two places, both handled here specifically:
 * <ul>
 *   <li>{@code UserIdent.get(Player)} short circuits any {@link FakePlayer} to a grantless NPC ident.
 *       {@code UserIdent} recognises this class first and returns {@link #getIdent()}, so both the entity and
 *       source stack paths resolve to the sender.</li>
 *   <li>{@code ChatOutputHandler.sendMessageI} LOGS a message aimed at a connectionless fake player rather than
 *       delivering it, so SU feedback would vanish into the log. It recognises this class first and routes to
 *       {@link #captureFeedback} for shipping back to the sender.</li>
 * </ul>
 *
 * <p>Vanilla feedback needs no carve-out: {@code sendSuccess}/{@code sendFailure} call {@code sendSystemMessage},
 * overridden here to capture. Short lived: built for a single synchronous dispatch and discarded, so its identity
 * carve-out cannot leak into an unrelated {@code UserIdent} lookup.
 */
public final class ForwardedCommandSender extends FakePlayer
{
    private final UserIdent ident;
    private final List<Component> captured = new ArrayList<>();

    public ForwardedCommandSender(ServerLevel level, GameProfile profile, UserIdent ident)
    {
        super(level, profile);
        this.ident = ident;
    }

    /** The real identity of the player who typed the command on the origin shard. */
    public UserIdent getIdent()
    {
        return ident;
    }

    /** Everything the command tried to say to the sender, in order, for shipping back over the network. */
    public List<Component> captured()
    {
        return captured;
    }

    public void captureFeedback(Component message)
    {
        if (message != null)
            captured.add(message);
    }

    // Vanilla routes command feedback through the source's sendSystemMessage; capture it rather than drop it.
    @Override
    public void sendSystemMessage(Component message)
    {
        captureFeedback(message);
    }

    // Entity defaults these to false, which would make CommandSourceStack.sendSuccess/sendFailure skip us entirely.
    @Override
    public boolean acceptsSuccess()
    {
        return true;
    }

    @Override
    public boolean acceptsFailure()
    {
        return true;
    }

    // The "run as" broadcast to online admins belongs to the audit trail here, not to a fake player.
    @Override
    public boolean shouldInformAdmins()
    {
        return false;
    }

    @Override
    public Component getDisplayName()
    {
        return Component.literal(ident.getUsernameOrUuid());
    }
}
