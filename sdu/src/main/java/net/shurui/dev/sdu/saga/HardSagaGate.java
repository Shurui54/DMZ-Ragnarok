package net.shurui.dev.sdu.saga;

import net.minecraft.server.level.ServerPlayer;

import java.util.function.Predicate;

/**
 * The single eligibility gate for offering and accepting the HARD saga difficulty, owned by sdu so both the
 * client screen gate ({@code QuestTreeScreenMixin}) and the server packet gate ({@code SetStoryDifficultyC2SMixin})
 * ask the SAME question and can never disagree.
 *
 * <p>sdu cannot read prestige (the "sdu imports nothing from shuruisutilities" invariant that keeps the public
 * tier shippable without the admin suite), so this holds a hook that the SU side installs at boot. The arrow
 * points SU to sdu: SU imports this class and fills it, sdu never reaches into SU.
 *
 * <p>Both sides DEFAULT TO ALLOW: sdu running alone (no SU) behaves exactly as DMZ does today, hard for everyone.
 * The gate only bites once SU has installed the prestige check (server side) and pushed the per-player boolean
 * (client side).
 */
public final class HardSagaGate
{
    private HardSagaGate() {}

    // Server-side eligibility. SU installs the real prestige check here at setup; until then hard is allowed, so
    // sdu without the admin suite is unchanged. Read per request, so it always reflects the caller's live prestige.
    private static volatile Predicate<ServerPlayer> serverEligibility = player -> true;

    // Client-side mirror of the local player's eligibility, pushed by SU on login / prestige / slot switch (the
    // same moments the prestige TP + race-lock sync fire). Defaults true so the option shows until told otherwise.
    private static volatile boolean clientMayUseHard = true;

    /** SU installs the prestige check here. Passing null restores the default allow-all. */
    public static void setServerEligibility(Predicate<ServerPlayer> predicate)
    {
        serverEligibility = predicate == null ? player -> true : predicate;
    }

    /** Server side: may this player choose HARD for a saga they are starting? */
    public static boolean mayUseHard(ServerPlayer player)
    {
        if (player == null)
            return false;
        try
        {
            return serverEligibility.test(player);
        }
        catch (Throwable ignored)
        {
            // a broken installed check must not block the whole difficulty packet; fail open to DMZ's behaviour
            return true;
        }
    }

    /** SU pushes the local player's eligibility here from its sync packet's client handler. */
    public static void setClientMayUseHard(boolean value)
    {
        clientMayUseHard = value;
    }

    /** Client side: should the saga screen offer HARD to the local player? */
    public static boolean clientMayUseHard()
    {
        return clientMayUseHard;
    }
}
