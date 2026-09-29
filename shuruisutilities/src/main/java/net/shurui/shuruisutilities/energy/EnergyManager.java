package net.shurui.shuruisutilities.energy;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.api.key.RoleHooks;
import net.shurui.shuruisutilities.compat.dmz.ShadowDragonFormCompat;
import net.shurui.shuruisutilities.compat.tournaments.TitleBridge;

/**
 * The role energy resource: what a bar is worth, what a move costs, who has one, and the spend/refuse decision.
 *
 * <p>THE SCALE IS FLAT. Every bar runs 0..{@link #MAX} = 100 and is not derived from any DMZ stat, so a technique
 * costs the same quarter of the bar for a fresh character as for a maxed one. Do not reintroduce a stat-scaled
 * maximum: the point of the flat scale is that role abilities are gated by pace, not by progression, which the
 * player's DMZ stats already gate everywhere else.
 *
 * <p>COSTS. A move costs {@link #COST_STANDARD} unless it either drains continuously (those spend via
 * {@link #drain} per tick) or is specified to consume the whole bar (those spend via {@link #spendAll}). There is
 * deliberately no per-move cost table: four standard casts per full bar is the rule, and a move that wants to differ
 * has to be one of the two named exceptions so the exception stays visible.
 *
 * <p>KEY SPLIT. The god roles (DESTRUCTION, ANGELIC) and their abilities are the Ragnarok Key's (feature
 * {@code roles}, {@link RoleHooks}); the shadow dragon's MALICE is public. So this pool, its regen ({@link EnergySync})
 * and the spend paths stay in core, and without the key only MALICE has access: a MALICE-only pool, with no role
 * energy for anyone else.
 */
public final class EnergyManager
{
    private EnergyManager() {}

    /** Full bar. Flat for every kind and every player; see the class note on why this is not stat-derived. */
    public static final float MAX = 100.0f;

    /** What one ordinary move costs: a quarter of the bar, so a full bar is four casts. */
    public static final float COST_STANDARD = 25.0f;

    /**
     * Refill rate, in bar points per second.
     *
     * <p>At 0.5 a full bar takes 200 seconds and a standard 25-point move comes back every 50. This is the one
     * number to turn if role abilities feel too frequent or too rare; nothing else in this system encodes pacing.
     * Regen runs whether or not the bar's role is currently active, so a player does not lose banked recovery by,
     * say, leaving their shadow dragon race for a while.
     *
     * <p>Halved from 1.0: the continuously draining auras spend five a second, so at the old rate a bar recovered a
     * fifth of what holding one cost, and the moves came round faster than they were meant to.
     */
    public static final float REGEN_PER_SECOND = 0.5f;

    /**
     * The bar shown on this player's HUD, or null when they have no role at all.
     *
     * <p>DISPLAY ONLY. This picks which colour the single track wears when a player qualifies for more than one
     * role; it does NOT decide what they may cast. Use {@link #hasAccess} for that.
     */
    public static EnergyKind activeKind(ServerPlayer player)
    {
        if (player == null)
            return null;
        // The god roles are the Ragnarok Key's (feature roles): without it only a shadow dragon's MALICE bar exists.
        if (!RoleHooks.available())
            return ShadowDragonFormCompat.isShadowDragon(player) ? EnergyKind.MALICE : null;
        String title = TitleBridge.heldTitle(player);
        // Grand Zeno carries both movesets, but a player has ONE HUD track, so the bar is drawn in the God of
        // Destruction's purple. Nothing is lost by the choice: every role spends the same shared pool, and what a
        // Zeno may actually cast is decided by hasAccess, not here.
        if (RoleTitles.GRAND_ZENO.equals(title))
            return EnergyKind.DESTRUCTION;
        if (RoleTitles.ANGEL.equals(title))
            return EnergyKind.ANGELIC;
        if (RoleTitles.GOD_OF_DESTRUCTION.equals(title))
            return EnergyKind.DESTRUCTION;
        if (ShadowDragonFormCompat.isShadowDragon(player))
            return EnergyKind.MALICE;
        return null;
    }

    /**
     * True when this player may spend the given bar.
     *
     * <p>ROLES ARE NOT EXCLUSIVE. A shadow dragon who is also handed the Angel or G.O.D. title keeps their malice
     * moveset: each role is checked on its own terms - the race for malice, the title for the other two - so holding
     * one never takes another away. Grand Zeno is the same idea inside one title: it answers true for BOTH
     * {@link EnergyKind#DESTRUCTION} and {@link EnergyKind#ANGELIC}, which is what makes it grant both movesets.
     *
     * <p>This was originally derived from {@link #activeKind}, which returns exactly one role. The effect was that
     * giving a shadow dragon a title silently refused every one of their dragon techniques, with no message, which
     * is indistinguishable from the moves being broken.
     */
    public static boolean hasAccess(ServerPlayer player, EnergyKind kind)
    {
        if (player == null || kind == null)
            return false;
        return switch (kind)
        {
            case MALICE -> ShadowDragonFormCompat.isShadowDragon(player);
            // The two god roles are the Ragnarok Key's (feature roles); MALICE above is public.
            case DESTRUCTION -> RoleHooks.available() && RoleTitles.grantsDestruction(TitleBridge.heldTitle(player));
            case ANGELIC -> RoleHooks.available() && RoleTitles.grantsAngelic(TitleBridge.heldTitle(player));
        };
    }

    /**
     * The player's energy.
     *
     * <p>ONE POOL, SHARED BY EVERY ROLE. The {@code kind} names which colour and which abilities are in play, not a
     * separate balance: a shadow dragon holding the Angel title spends dragon techniques and angelic abilities out
     * of the same hundred points. Separate pools per role would mean a titled dragon carried two bars and the HUD
     * could only show one of them, so the other would drain invisibly.
     */
    public static float get(ServerPlayer player, EnergyKind kind)
    {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null)
            return 0.0f;
        return EnergyData.get(server).getPool(player.getUUID());
    }

    /** Current value of the player's ACTIVE bar, or 0 when they have no role. */
    public static float getActive(ServerPlayer player)
    {
        EnergyKind kind = activeKind(player);
        return kind == null ? 0.0f : get(player, kind);
    }

    /** Set one bar outright (admin commands, and the grant path when a role is first handed out). */
    public static void set(ServerPlayer player, EnergyKind kind, float value)
    {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null || kind == null)
            return;
        EnergyData.get(server).setPool(player.getUUID(), value);
        EnergySync.push(player);
    }

    /**
     * Spend a fixed amount of the player's ACTIVE bar if they can afford it.
     *
     * <p>ALL OR NOTHING: returns false and spends nothing when the player has no role, the bar is not the one they
     * have access to, or the balance is short. Callers must treat false as "the move did not happen" and not fire
     * any effect, otherwise a technique goes off free on an empty bar.
     */
    public static boolean spend(ServerPlayer player, EnergyKind kind, float amount)
    {
        if (!hasAccess(player, kind) || amount < 0.0f)
            return false;
        MinecraftServer server = player.getServer();
        if (server == null)
            return false;
        EnergyData data = EnergyData.get(server);
        float current = data.getPool(player.getUUID());
        if (current + 1.0e-4f < amount)
            return false;
        data.setPool(player.getUUID(), current - amount);
        EnergySync.push(player);
        return true;
    }

    /** Spend the standard quarter-bar cost of one ordinary move. */
    public static boolean spendStandard(ServerPlayer player, EnergyKind kind)
    {
        return spend(player, kind, COST_STANDARD);
    }

    /**
     * Spend the ENTIRE bar, for the abilities specified to consume all of it (the G.O.D. clash bypass and hakai).
     * Requires a genuinely full bar rather than draining whatever is there, so "costs the whole bar" cannot be
     * satisfied by casting it on an almost-empty one.
     */
    public static boolean spendAll(ServerPlayer player, EnergyKind kind)
    {
        return spend(player, kind, MAX);
    }

    /**
     * Continuous drain for the abilities that run while held, in bar points. Unlike {@link #spend} this is partial:
     * it takes whatever remains when the bar runs short and reports how much it actually took, so a caller can end
     * the effect the tick the bar empties instead of refusing a whole tick's worth.
     *
     * @return the amount actually taken, which is 0 when the player has no access or the bar is already empty.
     */
    public static float drain(ServerPlayer player, EnergyKind kind, float amount)
    {
        if (!hasAccess(player, kind) || amount <= 0.0f)
            return 0.0f;
        MinecraftServer server = player.getServer();
        if (server == null)
            return 0.0f;
        EnergyData data = EnergyData.get(server);
        float current = data.getPool(player.getUUID());
        float taken = Math.min(current, amount);
        if (taken <= 0.0f)
            return 0.0f;
        data.setPool(player.getUUID(), current - taken);
        EnergySync.push(player);
        return taken;
    }

    /** Give energy back (a refused cast that already paid, or an admin top-up). Clamped at {@link #MAX}. */
    public static void refund(ServerPlayer player, EnergyKind kind, float amount)
    {
        if (player == null || kind == null || amount <= 0.0f)
            return;
        set(player, kind, get(player, kind) + amount);
    }
}
