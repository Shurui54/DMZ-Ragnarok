package net.shurui.shuruisutilities.clone;

import com.dragonminez.common.stats.StatsData;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import net.shurui.shuruisutilities.clone.MiniCloneEntity.Variant;
import net.shurui.shuruisutilities.compat.DmzBridge;

/**
 * Server-side orchestration for the {@code su_mini_clone} technique: decides how many clones a charge spawns, which
 * variant the caster's race gets, and the tint colour, then summons them. Gating (key, operator switch, tournament
 * exclusion) is enforced by the caller ({@code MixinDmzTechniqueDispatcher}) before this runs.
 *
 * <p>DragonMineZ is a mandatory dependency, so the caster's {@link StatsData} is read directly (as elsewhere in the
 * suite) through {@link DmzBridge}. Colours are read as HEX STRINGS ({@code getBodyColor} / {@code getHairColor}) and
 * parsed here on the SERVER; the {@code getRgb*} variants are deliberately not called, because they route through
 * DragonMineZ's client-only {@code ColorUtils} and would classload it on a dedicated server.
 */
public final class MiniClone
{
    private MiniClone() {}

    /** DMZ technique id. PERSISTED into each holder's unlocked-technique map, so never rename. */
    public static final String TECHNIQUE_ID = "su_mini_clone";

    /**
     * DMZ's BLOCK pose, used as the clone's charge animation and (briefly) its cast animation. Not guessed: this is
     * the id behind {@code com.dragonminez.client.animation.BaseAnimations.BLOCK}, which is
     * {@code RawAnimation.begin().thenPlay("base.block")} in {@code animations/entity/races/movement.animation.json}.
     */
    public static final String CHARGE_ANIMATION = "base.block";

    // Race ids whose clone is a themed miniature rather than a copy of the caster's skin.
    private static final String RACE_MAJIN = "majin";
    private static final String RACE_BIOANDROID = "bioandroid";

    private static final int DEFAULT_TINT = 0xFFFFFF;

    /**
     * Summon the caster's clones. Discards any the caster already owns first, so a recast replaces rather than
     * accumulates. One clone for a full charge, a second once the overcharge reaches 175% (raw charge >= 1.74).
     *
     * @param charge the raw DMZ charge (chargePercent / 100), so 1.0 is 100% and 1.75 is the overcharge cap
     * @return true when at least one clone was spawned
     */
    public static boolean spawn(ServerPlayer player, float charge)
    {
        if (player == null || charge < 1.0f)
        {
            return false;
        }
        if (!(player.level() instanceof ServerLevel level))
        {
            return false;
        }

        MinecraftServer server = player.getServer();
        if (server != null)
        {
            MiniCloneRegistry.discardAll(server, player.getUUID());
        }

        Variant variant = variantFor(player);
        int tint = tintFor(player, variant);
        int count = charge >= 1.74f ? 2 : 1;

        int spawned = 0;
        for (int i = 0; i < count; i++)
        {
            BlockPos pos = spawnPosFor(player, i, count);
            MiniCloneEntity clone = MiniCloneEntity.spawnClone(level, pos, player, variant, tint);
            if (clone != null)
            {
                if (variant == Variant.PLAYER_COPY)
                {
                    applyRaceAppearance(clone, player);
                    applyGreenOutline(clone);
                }
                spawned++;
            }
        }
        return spawned > 0;
    }

    /**
     * The scoreboard team that colours the outline. Vanilla takes an entity's outline colour from its TEAM and offers
     * no per-entity lever, so a green outline has to be a green team. One team, made once, reused by every clone.
     */
    private static final String GLOW_TEAM = "rg_clone_glow";

    /**
     * Give a player-copy clone its green outline, so nobody mistakes one for the real player.
     *
     * <p>Only PLAYER_COPY gets this. A Buu or Cell Jr. clone is already unmistakable, and an outline would fight the
     * tint it is drawn with.
     */
    private static void applyGreenOutline(MiniCloneEntity clone)
    {
        MinecraftServer server = clone.getServer();
        if (server == null)
        {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(GLOW_TEAM);
        if (team == null)
        {
            team = scoreboard.addPlayerTeam(GLOW_TEAM);
            team.setColor(ChatFormatting.GREEN);
            team.setSeeFriendlyInvisibles(false);
        }
        clone.setGlowingTag(true);
        scoreboard.addPlayerToTeam(clone.getScoreboardName(), team);
    }

    /**
     * Take a dying clone back off the glow team.
     *
     * <p>This is not tidiness, it is a leak fix. Team membership is keyed by the entity's scoreboard name (its UUID
     * for a non-player) and is PERSISTED in scoreboard.dat, so a clone that joined the team and never left would add
     * a permanent row. At two clones a cast that grows without bound. The team itself is left in place for reuse.
     */
    static void clearGreenOutline(MiniCloneEntity clone)
    {
        MinecraftServer server = clone.getServer();
        if (server == null)
        {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(GLOW_TEAM);
        // Only PLAYER_COPY clones ever join the team (BUU and CELL_JR never do), and a hop or scoreboard reset can drop a
        // member, so remove only an actual member: removePlayerFromTeam THROWS for a non-member, and doing that inside
        // the entity tick crashed the server (26 OW1 crashes, Sep 21-24).
        if (team != null && scoreboard.getPlayersTeam(clone.getScoreboardName()) == team)
        {
            scoreboard.removePlayerFromTeam(clone.getScoreboardName(), team);
        }
    }

    /**
     * Re-apply the green outline to a PLAYER_COPY clone that reappeared outside a fresh cast, for example one respawned
     * on the far side of a shard hop. A themed Buu / Cell Jr. clone is deliberately skipped, exactly as {@link #spawn}
     * only outlines player copies. Reuses the same {@link #applyGreenOutline} path so the outline team and glow are
     * identical to a freshly cast clone.
     */
    public static void reapplyGreenOutline(MiniCloneEntity clone)
    {
        if (clone != null && clone.getVariant() == Variant.PLAYER_COPY)
        {
            applyGreenOutline(clone);
        }
    }

    /** Majin casters get a tinted Buu, bio-androids a tinted Cell Jr., everyone else (including custom races) a copy. */
    private static Variant variantFor(ServerPlayer player)
    {
        String race = raceOf(player);
        if (RACE_MAJIN.equalsIgnoreCase(race))
        {
            return Variant.BUU;
        }
        if (RACE_BIOANDROID.equalsIgnoreCase(race))
        {
            return Variant.CELL_JR;
        }
        return Variant.PLAYER_COPY;
    }

    /**
     * Copy the caster's DragonMineZ race id, body type and body/hair/eye colours onto a player-copy clone, so the client
     * render layer can repaint the vanilla skin with the caster's race appearance (Namekian green, Frost Demon layers,
     * Shadow Dragon, and so on). Colours are read as HEX STRINGS and parsed here on the SERVER, exactly like
     * {@link #tintFor}, so DragonMineZ's client-only {@code ColorUtils} is never classloaded on a dedicated server. Any
     * read failure leaves the clone's default (white, no-tint) appearance, which the client degrades to a plain skin.
     */
    private static void applyRaceAppearance(MiniCloneEntity clone, ServerPlayer player)
    {
        try
        {
            StatsData stats = DmzBridge.stats(player);
            if (stats == null)
            {
                return;
            }
            com.dragonminez.common.stats.character.Character character = stats.getCharacter();
            if (character == null)
            {
                return;
            }
            clone.setRaceAppearance(
                    character.getRaceName(),
                    character.getBodyType(),
                    parseHex(character.getBodyColor()),
                    parseHex(character.getBodyColor2()),
                    parseHex(character.getBodyColor3()),
                    parseHex(character.getHairColor()),
                    parseHex(character.getEye1Color()),
                    parseHex(character.getEye2Color()));
        }
        catch (Throwable t)
        {
            // Leave the default appearance; the client render layer falls back to the plain skin.
        }
    }

    private static int tintFor(ServerPlayer player, Variant variant)
    {
        try
        {
            StatsData stats = DmzBridge.stats(player);
            if (stats == null)
            {
                return DEFAULT_TINT;
            }
            com.dragonminez.common.stats.character.Character character = stats.getCharacter();
            if (character == null)
            {
                return DEFAULT_TINT;
            }
            if (variant == Variant.BUU)
            {
                return parseHex(character.getBodyColor());
            }
            if (variant == Variant.CELL_JR)
            {
                return parseHex(character.getHairColor());
            }
            return DEFAULT_TINT;
        }
        catch (Throwable t)
        {
            return DEFAULT_TINT;
        }
    }

    private static String raceOf(ServerPlayer player)
    {
        try
        {
            StatsData stats = DmzBridge.stats(player);
            if (stats == null)
            {
                return null;
            }
            com.dragonminez.common.stats.character.Character character = stats.getCharacter();
            return character == null ? null : character.getRaceName();
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /** Parse a DMZ hex colour string ("#RRGGBB" or "RRGGBB") to a packed 0xRRGGBB, defaulting to white. */
    private static int parseHex(String hex)
    {
        if (hex == null || hex.isEmpty())
        {
            return DEFAULT_TINT;
        }
        try
        {
            String cleaned = hex.startsWith("#") ? hex.substring(1) : hex;
            return Integer.parseInt(cleaned, 16) & 0xFFFFFF;
        }
        catch (NumberFormatException e)
        {
            return DEFAULT_TINT;
        }
    }

    /** A small ring of positions around the caster, so two clones do not stack on the same block. */
    private static BlockPos spawnPosFor(ServerPlayer player, int index, int count)
    {
        double angle = count <= 1 ? 0.0 : (Math.PI * 2.0 * index) / count;
        double offX = Math.cos(angle) * 1.5;
        double offZ = Math.sin(angle) * 1.5;
        return BlockPos.containing(player.getX() + offX, player.getY(), player.getZ() + offZ);
    }
}
