package net.shurui.shuruisutilities.disguise.client;

import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.shurui.shuruisutilities.disguise.DisguiseView;
import net.shurui.shuruisutilities.patreon.client.CrownClientCache;
import net.shurui.shuruisutilities.ranks.RankManager;
import net.shurui.shuruisutilities.ranks.client.RankClientCache;

/**
 * The single point every client identity surface (tab list, name tag, chat echo, crown) asks "who is this player,
 * really, to ME". When a player is disguised it answers with the TARGET's identity (rank, crown, name), except for a
 * staff viewer the server flagged as able to see through, who additionally gets a marker.
 *
 * <p>This keeps the disguise logic in one place: the existing rank / crown / name draw code changes from calling
 * {@code RankClientCache.rankOf} / {@code CrownClientCache.codepointOf} directly to calling the equivalents here, and
 * a non-disguised player falls straight through to the same answer as before.
 */
@OnlyIn(Dist.CLIENT)
public final class DisguiseIdentity
{
    private DisguiseIdentity() {}

    /** The rank whose badge should be drawn for this player: the disguise target's when disguised, else the real one. */
    public static RankManager.Rank rankOf(UUID id)
    {
        DisguiseView v = DisguiseClientCache.get(id);
        if (v == null)
            return RankClientCache.rankOf(id);
        if (v.rankId == null || v.rankId.isEmpty())
            return null;
        return RankManager.get(v.rankId);
    }

    /** The crown codepoint to draw for this player (0 = none): the target's when disguised, else the real one. */
    public static int crownOf(UUID id)
    {
        DisguiseView v = DisguiseClientCache.get(id);
        if (v != null)
            return v.crownCodepoint;
        return CrownClientCache.codepointOf(id);
    }

    /**
     * The base display NAME for this player: the disguise target's name when disguised (with a small staff-only
     * marker appended when this client may see through), else the original name unchanged. The target's name carries
     * the TARGET's scoreboard team formatting (prefix, colour, suffix: where a title like [G.O.D.] lives) when this
     * client knows a team for that name, so neither the staff member's own team styling nor their title leaks through.
     */
    public static Component nameFor(UUID id, Component original)
    {
        DisguiseView v = DisguiseClientCache.get(id);
        if (v == null)
            return original;
        Component name = Component.literal(v.targetName);
        try
        {
            net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
            net.minecraft.world.scores.PlayerTeam team =
                    level == null ? null : level.getScoreboard().getPlayersTeam(v.targetName);
            if (team != null)
                name = net.minecraft.world.scores.PlayerTeam.formatNameForTeam(team, name);
        }
        catch (Throwable ignored)
        {
            // Plain target name.
        }
        if (DisguiseClientCache.canSeeReal())
            name = name.copy().append(Component.literal(" (" + v.realName + ")").withStyle(ChatFormatting.DARK_GRAY));
        return name;
    }

    /** Whether this player is disguised on this client (a cheap gate before doing any override work). */
    public static boolean isDisguised(UUID id)
    {
        return DisguiseClientCache.isDisguised(id);
    }
}
